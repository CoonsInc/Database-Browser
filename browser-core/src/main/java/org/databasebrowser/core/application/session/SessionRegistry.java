package org.databasebrowser.core.application.session;

import static org.databasebrowser.core.application.AdapterCalls.safely;
import static org.databasebrowser.core.application.AdapterCalls.safelyRun;
import static org.databasebrowser.core.domain.Errors.error;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Connections.Session;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.port.AdapterProvider.AdapterPorts;
import org.databasebrowser.core.port.AdapterProvider;

/** Открывает и закрывает сессии и задаёт порядок операций внутри подключения. */
public final class SessionRegistry implements ConnectionService, AutoCloseable {
    private final Map<AdapterId, AdapterProvider> providers;
    private final List<Consumer<SessionId>> onClosed = new CopyOnWriteArrayList<>();

    private final Map<SessionId, SessionState> sessions = new ConcurrentHashMap<>();
    private final Set<SessionId> usedIds = new HashSet<>();
    private final Object lifecycle = new Object();
    private int opening;
    private volatile boolean shuttingDown;

    /** Регистрирует доступные адаптеры. */
    public SessionRegistry(Collection<AdapterProvider> adapters) {
        Map<AdapterId, AdapterProvider> byId = new HashMap<>();
        for (AdapterProvider adapter : adapters) {
            if (byId.putIfAbsent(adapter.id(), adapter) != null) {
                throw error(Code.INVALID_INPUT, "Duplicate adapter ID");
            }
        }

        providers = Map.copyOf(byId);
    }

    /** Добавляет обработчик очистки после успешного отключения сессии. */
    public void onSessionClosed(Consumer<SessionId> cleanup) {
        onClosed.add(Objects.requireNonNull(cleanup));
    }

    /** Открывает сессию через адаптер из настроек. */
    @Override
    public Session connect(Settings settings) {
        if (settings == null || settings.displayName() == null || settings.displayName().isBlank()) {
            throw error(Code.INVALID_INPUT, "Connection display name is required");
        }

        AdapterProvider provider = providers.get(settings.adapter());
        if (provider == null) {
            throw error(Code.UNKNOWN_ADAPTER, "Unknown adapter: " + settings.adapter());
        }

        synchronized (lifecycle) {
            if (shuttingDown) {
                throw error(Code.CONFLICT, "Core is shutting down");
            }
            opening++;
        }

        try {
            AdapterPorts ports = safely(() -> Objects.requireNonNull(provider.ports()));
            IdentifierRules rules = safely(() -> Objects.requireNonNull(provider.identifierRules()));
            Opened opened = safely(() -> ports.connection().connect(settings));
            if (opened == null || opened.id() == null) {
                throw error(Code.INTERNAL, "Adapter returned no session ID");
            }

            Session session = new Session(opened.id(), settings.adapter(), settings.displayName(),
                opened.info());
            SessionState state = new SessionState(session, ports, rules);

            synchronized (lifecycle) {
                // Повторный идентификатор может принадлежать исходному подключению.
                if (!usedIds.add(session.id())) {
                    throw error(Code.INTERNAL, "Adapter reused a session ID");
                }
                if (shuttingDown || opened.info() == null) {
                    state.markClosing();
                }
                sessions.put(session.id(), state);
                if (!state.isClosing()) {
                    return session;
                }
                if (shuttingDown) {
                    throw error(Code.CONFLICT, "Core is shutting down");
                }
            }

            var failure = error(Code.INTERNAL, "Adapter returned invalid connection metadata");
            try {
                disconnect(session.id());
            } catch (RuntimeException cleanupError) {
                failure.addSuppressed(cleanupError);
            }
            throw failure;
        } finally {
            synchronized (lifecycle) {
                opening--;
                lifecycle.notifyAll();
            }
        }
    }

    /** Возвращает открытые сессии. */
    @Override
    public List<Session> list() {
        return sessions.values().stream()
            .filter(state -> !state.isClosing())
            .map(state -> state.session)
            .sorted(Comparator.comparing(session -> session.id().value()))
            .toList();
    }

    /** Проверяет доступность открытого подключения. */
    @Override
    public boolean checkConnection(SessionId id) {
        return work(id, state -> state.ports.connection().checkConnection(id));
    }

    /** Получает название и версию СУБД. */
    @Override
    public DatabaseInfo databaseInfo(SessionId id) {
        return work(id, state -> state.ports.info().getDatabaseInfo(id));
    }

    /** Получает возможности адаптера для сессии. */
    @Override
    public Capabilities capabilities(SessionId id) {
        return work(id, state -> state.ports.info().getCapabilities(id));
    }

    /** Запрашивает отмену работы и освобождает ресурсы сессии. */
    @Override
    public void disconnect(SessionId id) {
        SessionState state;
        synchronized (lifecycle) {
            if (!usedIds.contains(id)) {
                throw error(Code.UNKNOWN_SESSION, "Unknown session");
            }
            state = sessions.get(id);
        }
        if (state == null) {
            return;
        }

        Runnable cancel = state.markClosing();
        RuntimeException cancelFailure = null;
        try {
            if (cancel != null) {
                cancel.run();
            }
        } catch (RuntimeException e) {
            cancelFailure = e;
        }

        try {
            state.cleanup(() -> {
                safelyRun(() -> state.ports.connection().disconnect(id));
                state.markClosed();
                try {
                    notifyClosed(id);
                } finally {
                    sessions.remove(id, state);
                }
            });
        } catch (RuntimeException e) {
            if (cancelFailure != null && cancelFailure != e) {
                e.addSuppressed(cancelFailure);
            }
            throw e;
        }

        if (cancelFailure != null) {
            throw cancelFailure;
        }
    }

    /** Закрывает открытые и завершающиеся подключения. */
    @Override
    public void close() {
        Set<SessionId> registered;
        synchronized (lifecycle) {
            shuttingDown = true;
            registered = Set.copyOf(sessions.keySet());
        }

        // Новые подключения могут ждать завершения уже запущенных операций.
        RuntimeException failure = disconnectAll(registered, null);

        boolean interrupted = false;
        try {
            List<SessionId> late;
            synchronized (lifecycle) {
                while (opening > 0) {
                    try {
                        lifecycle.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                late = sessions.keySet().stream()
                    .filter(id -> !registered.contains(id))
                    .toList();
            }

            failure = disconnectAll(late, failure);
            if (failure != null) {
                throw failure;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Проверяет, что идентификатор выдавался этим сервисом. */
    public void requireKnown(SessionId id) {
        synchronized (lifecycle) {
            if (!usedIds.contains(id)) {
                throw error(Code.UNKNOWN_SESSION, "Unknown session");
            }
        }
    }

    /** Выполняет действие под блокировкой открытой сессии. */
    public <T> T work(SessionId id, Function<SessionState, T> action) {
        SessionState state = id == null ? null : sessions.get(id);
        if (state == null) {
            throw error(Code.UNKNOWN_SESSION, "Unknown session");
        }
        return state.work(action);
    }

    /** Выполняет очистку всех сервисов и собирает ошибки. */
    private void notifyClosed(SessionId id) {
        RuntimeException failure = null;
        for (Consumer<SessionId> cleanup : onClosed) {
            try {
                safelyRun(() -> cleanup.accept(id));
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    /** Закрывает список сессий и собирает ошибки. */
    private RuntimeException disconnectAll(Iterable<SessionId> ids, RuntimeException failure) {
        for (SessionId id : ids) {
            try {
                disconnect(id);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }

    /** Хранит подключение и синхронизирует его операции. */
    public final class SessionState {
        private final Session session;
        private final AdapterPorts ports;
        private final IdentifierRules identifierRules;

        private final ReentrantLock lock = new ReentrantLock(true);
        private volatile boolean closing;
        private boolean closed;
        private Runnable cancellation;

        private SessionState(Session session, AdapterPorts ports, IdentifierRules rules) {
            this.session = session;
            this.ports = ports;
            identifierRules = rules;
        }

        /** Возвращает описание открытого подключения. */
        public Session session() {
            return session;
        }

        /** Возвращает порты, закреплённые за подключением. */
        public AdapterPorts ports() {
            return ports;
        }

        /** Возвращает правила сравнения имён для этой сессии. */
        public IdentifierRules identifierRules() {
            return identifierRules;
        }

        /** Выполняет действие в открытой сессии. */
        public <T> T work(Function<SessionState, T> action) {
            lock.lock();
            try {
                checkOpen();
                return safely(() -> action.apply(this));
            } finally {
                lock.unlock();
            }
        }

        /** Даёт завершить очистку при закрывающейся сессии. */
        public void cleanup(Runnable action) {
            lock.lock();
            try {
                if (!closed) {
                    action.run();
                }
            } finally {
                lock.unlock();
            }
        }

        /** Регистрирует обработчик отмены до запуска операции. */
        public synchronized void startWork(Runnable begin, Runnable cancel) {
            checkOpen();
            begin.run();
            cancellation = cancel;
        }

        /** Снимает обработчик отмены завершённой операции. */
        public synchronized void finishWork() {
            cancellation = null;
        }

        /** Запрещает новые операции и возвращает обработчик отмены. */
        private synchronized Runnable markClosing() {
            closing = true;
            return cancellation;
        }

        // Вызывается при очистке после успешного отключения.
        private void markClosed() {
            closed = true;
        }

        /** Показывает, что начато отключение. */
        private boolean isClosing() {
            return closing;
        }

        /** Проверяет, доступна ли сессия для работы. */
        private void checkOpen() {
            if (closing || shuttingDown) {
                throw error(Code.UNKNOWN_SESSION, "Session is closing or closed");
            }
        }
    }
}
