package org.databasebrowser.core.application;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Connections.Session;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.ChangePlanId;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Schema.SchemaObject;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Sql;
import org.databasebrowser.core.port.DatabasePorts;

/**
 * Одно окно = одна сессия базы данных: все сценарии делят {@code SessionScope}, состояние,
 * блокировку и карты ID, которые живут дольше одного сценария.
 * Экземпляр создаёт адаптер в {@link AdapterFactory#open}, передаёт в {@link Usecases#bind} и закрывает через {@link Usecases#close()}.
 * {@link AdapterRegistry#open} подключает к нему общий {@link SharedIds}, поэтому ID уникальны между окнами.
 * <p>
 * Пример: {@code
 * SessionScope scope = new SessionScope(session, ports, rules);
 * Usecases usecases = Usecases.bind(scope, exports);
 * usecases.sql().execute(request);
 * usecases.close();
 * }
 */
public final class SessionScope implements AutoCloseable {
    /**
     * Маркер одной незавершённой SQL-операции в этом окне.
     * Флаг {@link #active()} говорит {@link SessionScope#close()}, нужно ли отменять операцию при закрытии сессии.
     * {@link #deactivate()} вызывают после завершения, чтобы закрытие не отменяло операцию повторно.
     */
    public static final class Operation {
        private volatile boolean active = true;

        /**
         * {@code true} означает, что закрытие сессии должно вызвать cancel для этого {@link OperationId}.
         */
        public boolean active() {
            return active;
        }

        /**
         * Снимает требование cancel; повторный вызов ничего не меняет.
         */
        public void deactivate() {
            active = false;
        }
    }

    /**
     * Хранит состояние курсора результата: закрыт ли он, завершена ли выдача и какие колонки известны.
     * {@link #claimed()} показывает, что результат уже отдан одному потребителю.
     * {@link #markClosed()} вызывается при закрытии сессии, а не каждым читателем, и не меняет владельца ID.
     */
    public static final class ResultState {
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean complete;
        private boolean claimed;
        private final List<Sql.ResultColumn> columns;

        public ResultState(boolean complete, List<Sql.ResultColumn> columns) {
            this.complete = complete;
            this.columns = List.copyOf(columns);
        }

        public boolean closed() {
            return closed.get();
        }

        /**
         * Отмечает курсор закрытым при завершении {@link SessionScope#close()} сессии.
         */
        public void markClosed() {
            closed.set(true);
        }

        public boolean complete() {
            return complete;
        }

        /**
         * Обновляет признак полной выдачи строк.
         */
        public void complete(boolean value) {
            complete = value;
        }

        /**
         * {@code true}, если результат уже выдан одному потребителю.
         */
        public boolean claimed() {
            return claimed;
        }

        /**
         * Помечает, что результат выдан потребителю; повторный вызов ничего не меняет.
         */
        public void claim() {
            claimed = true;
        }

        public List<Sql.ResultColumn> columns() {
            return columns;
        }
    }

    /**
     * Хранит {@link Prepared} и атомарно отмечает единственную попытку применения.
     * {@link #tryAttempt()} возвращает {@code false}, если план уже пытались применить.
     * Экземпляр связывается с {@link ChangePlanId} в этом окне; сам {@link Prepared} не копируется.
     */
    public static final class StoredPlan {
        private final Prepared prepared;
        private final AtomicBoolean attempted = new AtomicBoolean();

        public StoredPlan(Prepared prepared) {
            this.prepared = prepared;
        }

        public Prepared prepared() {
            return prepared;
        }

        /**
         * Атомарно забирает право на попытку; {@code false} означает, что попытка уже была.
         */
        public boolean tryAttempt() {
            return attempted.compareAndSet(false, true);
        }
    }

    private final Session session;
    private final DatabasePorts ports;
    private final IdentifierRules rules;
    private final ChangeValidator validator;
    private final ReentrantLock work = new ReentrantLock(true);
    private static final Operation FOREIGN_OPERATION = new Operation();
    private static final ResultState FOREIGN_RESULT = new ResultState(true, List.of());
    private static final StoredPlan FOREIGN_PLAN = new StoredPlan(null);

    private final ConcurrentMap<OperationId, Operation> operations = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResultId, ResultState> results = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChangePlanId, StoredPlan> plans = new ConcurrentHashMap<>();
    private volatile SharedIds ids = new SharedIds();
    private volatile boolean closing;
    private boolean closed;

    public SessionScope(Session session, DatabasePorts ports, IdentifierRules rules) {
        this.session = Objects.requireNonNull(session);
        this.ports = Objects.requireNonNull(ports);
        this.rules = Objects.requireNonNull(rules);
        this.validator = new ChangeValidator(rules);
    }

    public Session session() {
        return session;
    }

    public DatabasePorts ports() {
        return ports;
    }

    public ChangeValidator validator() {
        return validator;
    }

    public IdentifierRules rules() {
        return rules;
    }

    /**
     * Подключает общий реестр ID до запуска сценариев; обычно вызывается реестром, не адаптером.
     * После этого {@code put*IfAbsent} проверяют владельца через {@link SharedIds}, а не только локальную карту.
     */
    public void attachIds(SharedIds shared) {
        this.ids = Objects.requireNonNull(shared);
    }

    /**
     * Выполняет действие под справедливой блокировкой окна и запрещает его во время закрытия.
     * Если сессия закрывается или закрыта, бросает {@link Code#UNKNOWN_SESSION}.
     * Ошибки действия нормализуются через {@code Errors.safe}.
     */
    public <T> T work(Supplier<T> action) {
        work.lock();
        try {
            if (closing)
                throw error(Code.UNKNOWN_SESSION, "Session is closing or closed");
            return org.databasebrowser.core.domain.Errors.safe(action);
        } finally {
            work.unlock();
        }
    }

    /**
     * Выполняет действие под той же блокировкой, но без проверки {@link #isClosing()}.
     * Блокировка реентерабельна, поэтому вызов уместен внутри {@link #work} и при закрытии.
     */
    public <T> T locked(Supplier<T> action) {
        work.lock();
        try {
            return action.get();
        } finally {
            work.unlock();
        }
    }

    /**
     * {@code true}, когда закрытие уже начато.
     * Новые пользовательские действия через {@link #work} после этого запрещены.
     */
    public boolean isClosing() {
        return closing;
    }

    /**
     * Читает схему и проверяет, что снимок принадлежит этой сессии и её диалекту.
     * Каждый объект снимка дополнительно проходит {@link #requireObject}.
     */
    public Snapshot snapshot() {
        Snapshot snap = ports.schema().readSchema(session.id());
        if (!snap.session().equals(session.id()) || !snap.dialect().equals(session.info().dialect()))
            throw error(Code.INTERNAL, "Invalid snapshot provenance");
        for (SchemaObject o : snap.objects()) requireObject(o.id());
        return snap;
    }

    /**
     * Проверяет, что объект не {@code null} и принадлежит этой сессии.
     * Иначе бросает {@link Code#INVALID_INPUT} или {@link Code#WRONG_SESSION}.
     */
    public void requireObject(ObjectId object) {
        if (object == null)
            throw error(Code.INVALID_INPUT, "Object is required");
        if (!object.session().equals(session.id()))
            throw error(Code.WRONG_SESSION, "Object belongs to another session");
    }

    /**
     * Регистрирует операцию локально, затем закрепляет её ID в {@link SharedIds}.
     * Возвращает {@code null} при успехе, прежнюю операцию при локальном дубле и {@code FOREIGN_OPERATION} при чужом владельце.
     * При отказе глобального владения локальная запись удаляется.
     */
    public Operation putOperationIfAbsent(OperationId oid, Operation op) {
        Operation prior = operations.putIfAbsent(oid, op);
        if (prior != null)
            return prior;
        if (!ids.claimOperation(oid, session.id())) {
            operations.remove(oid, op);
            return FOREIGN_OPERATION;
        }
        return null;
    }

    public Operation operation(OperationId oid) {
        return operations.get(oid);
    }

    /**
     * {@code true}, если ID когда-либо закреплён в реестре, даже после удаления локальной записи.
     */
    public boolean knownOperation(OperationId oid) {
        return ids.knownOperation(oid);
    }

    /**
     * Проверяет владельца ID в {@link SharedIds}; используется перед отменой операции.
     * Наличие операции в локальной карте само по себе владельца не доказывает.
     */
    public boolean ownedOperation(OperationId oid) {
        return ids.ownedOperation(oid, session.id());
    }

    /**
     * Снимает флаг активности: {@link #close()} больше не будет отменять эту операцию.
     */
    public void deactivate(Operation op) {
        op.deactivate();
    }

    /**
     * Регистрирует курсор локально и закрепляет {@link ResultId} в {@link SharedIds}.
     * Возвращает {@code null} при успехе, прежнее состояние при дубле и {@code FOREIGN_RESULT} для чужого ID.
     * При отказе владения локальная запись удаляется.
     */
    public ResultState putResultIfAbsent(ResultId rid, ResultState state) {
        ResultState prior = results.putIfAbsent(rid, state);
        if (prior != null)
            return prior;
        if (!ids.claimResult(rid, session.id())) {
            results.remove(rid, state);
            return FOREIGN_RESULT;
        }
        return null;
    }

    /**
     * Возвращает курсор этой сессии.
     * Для чужого, но известного ID бросает {@link Code#WRONG_SESSION}, для неизвестного — {@link Code#NOT_FOUND}.
     */
    public ResultState result(ResultId rid) {
        ResultState r = results.get(rid);
        if (r == null)
            throw error(ids.knownResult(rid) ? Code.WRONG_SESSION : Code.NOT_FOUND,
                "Unknown result in this session");
        return r;
    }

    /**
     * Регистрирует подготовленный план локально и закрепляет его ID в {@link SharedIds}.
     * Возвращает {@code null} при успехе, прежний план при дубле и {@code FOREIGN_PLAN} для чужого ID.
     * При отказе владения локальная запись удаляется.
     */
    public StoredPlan putPlanIfAbsent(ChangePlanId id, StoredPlan plan) {
        StoredPlan prior = plans.putIfAbsent(id, plan);
        if (prior != null)
            return prior;
        if (!ids.claimPlan(id, session.id())) {
            plans.remove(id, plan);
            return FOREIGN_PLAN;
        }
        return null;
    }

    /**
     * Возвращает план этой сессии.
     * Для чужого, но известного ID бросает {@link Code#WRONG_SESSION}, для неизвестного — {@link Code#NOT_FOUND}.
     */
    public StoredPlan plan(ChangePlanId id) {
        StoredPlan stored = plans.get(id);
        if (stored == null)
            throw error(ids.knownPlan(id) ? Code.WRONG_SESSION : Code.NOT_FOUND,
                "Unknown prepared plan in this session");
        return stored;
    }

    /**
     * Закрывает сессию: сначала отмечает начало закрытия и отменяет активные операции этого окна.
     * Затем disconnect выполняется один раз, курсоры помечаются закрытыми; чужие ID в локальные карты не попадают.
     * Ошибки отмены собираются: первая выбрасывается, остальные добавляются в {@code suppressed}.
     */
    @Override
    public void close() {
        synchronized (this) {
            closing = true;
        }
        List<RuntimeException> failures = new ArrayList<>();
        for (Map.Entry<OperationId, Operation> e : operations.entrySet()) {
            OperationId oid = e.getKey();
            if (e.getValue().active())
                try {
                    org.databasebrowser.core.domain.Errors.safe(() -> {
                        ports.sql().cancel(session.id(), oid);
                        return true;
                    });
                } catch (RuntimeException ex) {
                    synchronized (failures) {
                        failures.add(ex);
                    }
                }
        }
        work.lock();
        try {
            if (closed)
                return;
            org.databasebrowser.core.domain.Errors.safe(() -> {
                ports.connection().disconnect(session.id());
                return true;
            });
            closed = true;
            results.values().forEach(ResultState::markClosed);
        } finally {
            work.unlock();
        }
        if (!failures.isEmpty()) {
            RuntimeException e = failures.get(0);
            failures.stream().skip(1).forEach(e::addSuppressed);
            throw e;
        }
    }
}
