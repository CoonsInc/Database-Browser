package org.databasebrowser.core.application.sql;

import static org.databasebrowser.core.application.AdapterCalls.safely;
import static org.databasebrowser.core.application.AdapterCalls.safelyRun;
import static org.databasebrowser.core.domain.Errors.error;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.databasebrowser.core.application.session.SessionRegistry.SessionState;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Exports.RowScope;
import org.databasebrowser.core.domain.Exports.RowSource;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.QueryPlans.Mode;
import org.databasebrowser.core.domain.QueryPlans.QueryPlan;
import org.databasebrowser.core.domain.Sql.CancelResult;
import org.databasebrowser.core.domain.Sql;
import org.databasebrowser.core.port.SqlExecutionPort;

/** Выполняет SQL и EXPLAIN, управляет отменой и курсорами. */
public final class SqlService {
    private final SessionRegistry sessions;
    private final Map<OperationId, SqlActivity> operations = new ConcurrentHashMap<>();
    private final Map<ResultId, SessionId> owners = new ConcurrentHashMap<>();
    private final Map<ResultId, Cursor> cursors = new ConcurrentHashMap<>();

    /** Использует подключения общего реестра сессий. */
    public SqlService(SessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions);
        this.sessions.onSessionClosed(this::releaseSession);
    }

    /** Резервирует идентификатор для запуска и отмены запроса. */
    public OperationId newOperationId() {
        OperationId id;
        do {
            id = new OperationId(UUID.randomUUID());
        } while (operations.putIfAbsent(id, new SqlActivity()) != null);
        return id;
    }

    /** Выполняет SQL и возвращает первую порцию или результат команды. */
    public Sql.Execution execute(SessionId session, Sql.Request request) {
        return runSql(session, request, (state, activity, capabilities) ->
            activity.run(state, request.operationId(), () -> {
                Sql.Execution execution = state.ports().sql().execute(session, request);
                registerResult(session, activity, execution);

                return execution;
            }));
    }

    /** Запрашивает EXPLAIN с учётом возможностей сессии. */
    public QueryPlan explain(SessionId session, Sql.Request request, Mode mode) {
        if (mode == null) {
            throw error(Code.INVALID_INPUT, "Explain mode is required");
        }

        return runSql(session, request, (state, activity, capabilities) -> {
            if (!capabilities.queryPlan() || mode == Mode.ANALYZE && !capabilities.analyze()) {
                throw error(Code.UNSUPPORTED, "Query plan mode unsupported");
            }

            return activity.run(state, request.operationId(),
                () -> state.ports().plans().explain(session, request, mode));
        });
    }

    /** Запрашивает отмену SQL, EXPLAIN или догрузки по идентификатору запроса. */
    public Sql.CancelResult cancel(SessionId session, OperationId operation) {
        sessions.requireKnown(session);
        if (operation == null) {
            throw error(Code.INVALID_INPUT, "Operation ID is required");
        }

        SqlActivity activity = operations.get(operation);
        return activity == null ? Sql.CancelResult.NOT_ACTIVE : activity.cancel(session);
    }

    /** Дочитывает следующую порцию из результата выбранной сессии. */
    public Sql.Batch fetchRows(SessionId session, ResultId result, int size) {
        return sessions.work(session, state -> fetchRows(state, result, size));
    }

    /** Освобождает курсор результата. */
    public void closeResult(SessionId session, ResultId result) {
        sessions.work(session, state -> {
            closeResult(state, result);
            return null;
        });
    }

    /** Передаёт источнику ещё не прочитанные строки результата. */
    public RowSource remainingRows(SessionId session, ResultId result) {
        return sessions.work(session, state -> transferResult(state, result));
    }

    /** Регистрирует операцию, проверяет возможности и запускает действие. */
    private <T> T runSql(SessionId session, Sql.Request request, Statement<T> action) {
        validateSqlRequest(request);
        sessions.requireKnown(session);

        SqlActivity activity = operations.computeIfAbsent(request.operationId(),
            id -> new SqlActivity());
        activity.submit(session);

        try {
            return sessions.work(session, state -> {
                activity.checkPendingCancellation();
                Capabilities capabilities = state.ports().info().getCapabilities(session);
                activity.checkPendingCancellation();
                validateCapabilities(capabilities, request.options());

                return action.run(state, activity, capabilities);
            });
        } finally {
            activity.endSubmission();
        }
    }

    /** Проверяет режим выполнения и транзакционную политику. */
    private static void validateCapabilities(Capabilities capabilities, Sql.Options options) {
        if (!capabilities.executionModes().contains(options.mode())
            || !capabilities.transactions().contains(options.transaction())) {
            throw error(Code.UNSUPPORTED, "Unsupported execution or transaction mode");
        }
    }

    /** Проверяет параметры запроса до вызова адаптера. */
    private static void validateSqlRequest(Sql.Request request) {
        if (request == null || request.operationId() == null || request.sql() == null
            || request.sql().isBlank() || request.options() == null) {
            throw error(Code.INVALID_INPUT, "SQL, operation ID and options are required");
        }

        Sql.Options options = request.options();
        if (options.mode() == null || options.transaction() == null || options.initialBatchSize() < 1
            || options.timeout() == null || options.timeout().isNegative()) {
            throw error(Code.INVALID_INPUT, "Invalid SQL options");
        }

        Set<Integer> positions = new HashSet<>();
        for (Sql.Parameter parameter : request.parameters()) {
            if (parameter.position() < 1 || !positions.add(parameter.position())) {
                throw error(Code.INVALID_INPUT, "Parameter positions must be positive and unique");
            }
        }
    }

    /** Закрепляет открытый результат за его сессией и операцией. */
    private void registerResult(SessionId session, SqlActivity activity, Sql.Execution execution) {
        execution.result().ifPresent(id -> {
            Cursor cursor = new Cursor(session, activity, execution.columns(),
                execution.firstBatch().complete());
            if (owners.putIfAbsent(id, session) != null) {
                throw error(Code.INTERNAL, "Adapter reused result ID");
            }

            cursors.put(id, cursor);
        });
    }

    /** Проверяет состояние курсора перед чтением из интерфейса. */
    private Sql.Batch fetchRows(SessionState session, ResultId id, int size) {
        Cursor cursor = findResult(session, id);
        if (cursor == null || cursor.closing) {
            throw error(Code.NOT_FOUND, "Result is closed");
        }
        if (cursor.transferred) {
            throw error(Code.CONFLICT, "Result is owned by CSV source");
        }

        return readBatch(session, id, cursor, size);
    }

    /** Закрывает курсор или повторяет очистку после сбоя. */
    private void closeResult(SessionState session, ResultId id) {
        Cursor cursor = findResult(session, id);
        if (cursor == null) {
            return;
        }
        if (cursor.transferred && !cursor.closing) {
            throw error(Code.CONFLICT, "Result is owned by CSV source");
        }

        releaseResult(session, id, cursor);
    }

    /** Передаёт курсор в исключительное владение источнику строк. */
    private RowSource transferResult(SessionState session, ResultId id) {
        Cursor cursor = findResult(session, id);
        if (cursor == null || cursor.closing || cursor.transferred) {
            throw error(Code.CONFLICT, "Result unavailable for transfer");
        }

        cursor.transferred = true;
        return new TransferredRows(session, id, cursor);
    }

    /** Находит открытый курсор и проверяет его сессию. */
    private Cursor findResult(SessionState session, ResultId id) {
        SessionId owner = id == null ? null : owners.get(id);
        if (owner == null) {
            throw error(Code.NOT_FOUND, "Unknown result");
        }
        if (!owner.equals(session.session().id())) {
            throw error(Code.WRONG_SESSION, "Result belongs to another session");
        }

        return cursors.get(id);
    }

    /** Дочитывает курсор под отдельным идентификатором отмены. */
    private Sql.Batch readBatch(SessionState session, ResultId id, Cursor cursor, int size) {
        if (size < 1) {
            throw error(Code.INVALID_INPUT, "Batch size must be positive");
        }
        if (cursor.complete) {
            return new Sql.Batch(List.of(), true);
        }

        OperationId readOperation = new OperationId(UUID.randomUUID());
        Sql.Batch batch = cursor.activity.run(session, readOperation,
            () -> session.ports().sql().fetchRows(session.session().id(), id, readOperation, size));
        cursor.complete = batch.complete();
        return batch;
    }

    /** Освобождает курсор у адаптера и удаляет его из реестра. */
    private void releaseResult(SessionState session, ResultId id, Cursor cursor) {
        cursor.closing = true;
        safelyRun(() -> session.ports().sql().closeResult(session.session().id(), id));
        cursors.remove(id, cursor);
    }

    /** Удаляет курсоры сессии после закрытия подключения. */
    private void releaseSession(SessionId session) {
        cursors.values().removeIf(cursor -> cursor.owner.equals(session));
    }

    /** Действие после проверки запроса и возможностей адаптера. */
    @FunctionalInterface
    private interface Statement<T> {
        T run(SessionState state, SqlActivity activity, Capabilities capabilities);
    }

    /** Хранит состояние SQL и догрузки для отмены по одному идентификатору. */
    private static final class SqlActivity {
        /** Состояния операции от резервирования до завершения. */
        private enum Phase { RESERVED, PENDING, RUNNING, IDLE }

        private Phase phase = Phase.RESERVED;
        private SessionId owner;
        private boolean cancellationRequested;
        private SqlExecutionPort port;
        private OperationId adapterOperation;

        /** Закрепляет идентификатор за однократным запуском. */
        private synchronized void submit(SessionId session) {
            checkOwner(session);
            if (phase != Phase.RESERVED) {
                throw error(Code.CONFLICT, "Operation ID already used");
            }

            owner = session;
            phase = Phase.PENDING;
        }

        /** Завершает попытку, остановленную до вызова адаптера. */
        private synchronized void endSubmission() {
            if (phase == Phase.PENDING) {
                phase = Phase.IDLE;
            }
        }

        /** Останавливает ещё не запущенную отменённую операцию. */
        private synchronized void checkPendingCancellation() {
            if (cancellationRequested) {
                throw error(Code.CANCELLED, "SQL operation cancelled before execution");
            }
        }

        /** Выполняет запрос или догрузку с возможностью отмены. */
        private <T> T run(SessionState session, OperationId operation, Supplier<T> action) {
            session.startWork(() -> begin(session.ports().sql(), operation),
                () -> cancel(session.session().id()));

            try {
                return action.get();
            } finally {
                finish();
                session.finishWork();
            }
        }

        /** Запоминает отмену или передаёт её активному адаптеру. */
        private synchronized CancelResult cancel(SessionId session) {
            checkOwner(session);
            switch (phase) {
                case RESERVED, PENDING -> {
                    owner = session;
                    cancellationRequested = true;
                    return CancelResult.REQUESTED;
                }
                case RUNNING -> {
                    if (cancellationRequested) {
                        return CancelResult.REQUESTED;
                    }

                    // Держим фазу неизменной, пока порт принимает запрос отмены.
                    CancelResult result = safely(() -> port.cancel(owner, adapterOperation));
                    cancellationRequested = result == CancelResult.REQUESTED;
                    return result;
                }
                default -> {
                    return CancelResult.NOT_ACTIVE;
                }
            }
        }

        /** Связывает текущую фазу с операцией адаптера. */
        private synchronized void begin(SqlExecutionPort activePort, OperationId operation) {
            checkPendingCancellation();
            port = activePort;
            adapterOperation = operation;
            phase = Phase.RUNNING;
        }

        /** Снимает активную фазу после завершения вызова. */
        private synchronized void finish() {
            port = null;
            adapterOperation = null;
            cancellationRequested = false;
            phase = Phase.IDLE;
        }

        /** Защищает операцию от вызовов из другой сессии. */
        private void checkOwner(SessionId session) {
            if (owner != null && !owner.equals(session)) {
                throw error(Code.WRONG_SESSION, "Operation belongs to another session");
            }
        }
    }

    /** Хранит метаданные и состояние открытого курсора. */
    private static final class Cursor {
        final SessionId owner;
        final SqlActivity activity;
        final List<Sql.ResultColumn> columns;
        boolean complete;
        boolean transferred;
        boolean closing;

        Cursor(SessionId owner, SqlActivity activity, List<Sql.ResultColumn> columns,
            boolean complete) {
            this.owner = owner;
            this.activity = activity;
            this.columns = columns;
            this.complete = complete;
        }
    }

    /** Даёт экспорту исключительный доступ к оставшимся строкам. */
    private final class TransferredRows implements RowSource {
        private final SessionState session;
        private final ResultId id;
        private final Cursor cursor;
        private boolean closing;

        TransferredRows(SessionState session, ResultId id, Cursor cursor) {
            this.session = session;
            this.id = id;
            this.cursor = cursor;
        }

        /** Возвращает метаданные колонок курсора. */
        @Override
        public List<Sql.ResultColumn> columns() {
            return cursor.columns;
        }

        /** Обозначает источник оставшихся строк. */
        @Override
        public RowScope scope() {
            return RowScope.REMAINING;
        }

        /** Читает следующую порцию переданного курсора. */
        @Override
        public synchronized Sql.Batch fetch(int size) {
            if (closing) {
                throw error(Code.NOT_FOUND, "Source closed");
            }
            return session.work(state -> SqlService.this.readBatch(state, id, cursor, size));
        }

        /** Освобождает курсор с возможностью повторной очистки. */
        @Override
        public synchronized void close() {
            closing = true;
            session.cleanup(() -> {
                if (cursors.get(id) == cursor) {
                    releaseResult(session, id, cursor);
                }
            });
        }
    }
}
