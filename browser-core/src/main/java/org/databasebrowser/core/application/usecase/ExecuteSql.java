package org.databasebrowser.core.application.usecase;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.databasebrowser.core.application.SessionScope;
import org.databasebrowser.core.application.SessionScope.Operation;
import org.databasebrowser.core.application.SessionScope.ResultState;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Exports.RowScope;
import org.databasebrowser.core.domain.Exports.RowSource;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Sql;

/**
 * Юзкейс выполняет SQL, выдаёт результат страницами и управляет владением курсора и отменой.
 * Операции и результаты привязаны к {@link SessionScope}: другая сессия не может их использовать.
 * Используется как {@code Usecases.sql()}; ID операции создаёте через {@link #newOperationId()}.
 */
public final class ExecuteSql {
    private final SessionScope scope;

    /** Создаёт юзкейс для заданной сессии; {@code null} запрещён. */
    public ExecuteSql(SessionScope scope) {
        this.scope = Objects.requireNonNull(scope);
    }

    /**
     * Создаёт свежий {@link OperationId} на основе случайного UUID.
     * Идентификатор не регистрируется до выполнения; повторное использование уже занятого ID приведёт к ошибке.
     */
    public OperationId newOperationId() {
        return new OperationId(UUID.randomUUID());
    }

    /**
     * Проверяет запрос и возможности адаптера, выполняет SQL и регистрирует результат в сессии.
     * Возвращает {@link Sql.Execution} с первым пакетом; остальные строки доступны через {@link #fetchRows(ResultId, int)} или {@link #remainingRows(ResultId)}.
     * {@code OperationId} из запроса одноразовый: повторное использование даёт {@link Code#CONFLICT}.
     */
    public Sql.Execution execute(Sql.Request request) {
        return scope.work(() -> operation(request, () -> {
            Sql.Execution out = scope.ports().sql().execute(scope.session().id(), request);
            out.result().ifPresent(r -> {
                if (scope.putResultIfAbsent(
                        r, new ResultState(out.firstBatch().complete(), out.columns()))
                    != null)
                    throw error(Code.INTERNAL, "Adapter reused result ID");
            });
            return out;
        }));
    }

    /**
     * Выполняет {@code action} под уникальным {@code OperationId} из {@code request}.
     * Возвращает результат {@code action}; операция снимается с активной даже при исключении.
     * ID другой сессии даёт {@link Code#WRONG_SESSION}, уже использованный в этой — {@link Code#CONFLICT}.
     */
    public <T> T operation(Sql.Request request, Supplier<T> action) {
        validateSql(request);
        Operation op = new Operation();
        Operation prior;
        synchronized (scope) {
            if (scope.isClosing())
                throw error(Code.UNKNOWN_SESSION, "Session is closing");
            prior = scope.putOperationIfAbsent(request.operationId(), op);
        }
        if (prior != null)
            throw error(scope.ownedOperation(request.operationId()) ? Code.CONFLICT
                : Code.WRONG_SESSION, "Operation ID already used");
        try {
            return action.get();
        } finally {
            scope.deactivate(op);
        }
    }

    private void validateSql(Sql.Request r) {
        if (r == null || r.operationId() == null || r.sql() == null || r.sql().isBlank()
            || r.options() == null)
            throw error(Code.INVALID_INPUT, "SQL, operation ID and options are required");
        Sql.Options o = r.options();
        if (o.mode() == null || o.transaction() == null || o.initialBatchSize() < 1
            || o.timeout() == null || o.timeout().isNegative())
            throw error(Code.INVALID_INPUT, "Invalid SQL options");
        Set<Integer> positions = new HashSet<>();
        for (Sql.Parameter p : r.parameters())
            if (p.position() < 1 || !positions.add(p.position()))
                throw error(Code.INVALID_INPUT, "Parameter positions must be positive and unique");
        var c = scope.ports().info().getCapabilities(scope.session().id());
        if (!c.executionModes().contains(o.mode()) || !c.transactions().contains(o.transaction()))
            throw error(Code.UNSUPPORTED, "Unsupported execution or transaction mode");
    }

    /**
     * Возвращает следующий {@link Sql.Batch} результата размером до {@code size} строк.
     * Полный результат отдаёт пустой пакет с флагом завершения; закрытый даёт {@link Code#NOT_FOUND}.
     * Размер должен быть положительным; результат, переданный в CSV через {@link #remainingRows(ResultId)}, недоступен для прямой выборки.
     */
    public Sql.Batch fetchRows(ResultId rid, int size) {
        return fetch(rid, size, false);
    }

    private Sql.Batch fetch(ResultId rid, int size, boolean owned) {
        if (size < 1)
            throw error(Code.INVALID_INPUT, "Batch size must be positive");
        return scope.work(() -> {
            ResultState r = scope.result(rid);
            if (r.closed())
                throw error(Code.NOT_FOUND, "Result is closed");
            if (r.claimed() && !owned)
                throw error(Code.CONFLICT, "Result is owned by CSV source");
            if (r.complete())
                return new Sql.Batch(List.of(), true);
            Sql.Batch batch = scope.ports().sql().fetchRows(scope.session().id(), rid, size);
            r.complete(batch.complete());
            return batch;
        });
    }

    /**
     * Закрывает курсор результата и освобождает ресурсы адаптера.
     * Повторное закрытие ничего не меняет; результат, переданный в CSV, нельзя закрывать напрямую — {@link Code#CONFLICT}.
     */
    public void closeResult(ResultId rid) {
        closeResult(rid, false);
    }

    private void closeResult(ResultId rid, boolean owned) {
        scope.work(() -> {
            ResultState r = scope.result(rid);
            if (r.claimed() && !owned)
                throw error(Code.CONFLICT, "Result is owned by CSV source");
            if (!r.closed()) {
                scope.ports().sql().closeResult(scope.session().id(), rid);
                r.markClosed();
            }
            return null;
        });
    }

    /**
     * Возвращает {@link RowSource} на оставшиеся строки с областью {@link RowScope#REMAINING}.
     * Пакеты, уже прочитанные для UI, намеренно исключены.
     * Источник можно получить только один раз: повторный вызов даёт {@link Code#CONFLICT}.
     * Вызывающая сторона обязана закрыть источник или передать его в {@code ExportCsv}, который берёт владение на себя.
     * {@link RowSource#close()} закрывает курсор; выбор после закрытия даёт {@link Code#NOT_FOUND}.
     */
    public RowSource remainingRows(ResultId rid) {
        return scope.work(() -> {
            ResultState r = scope.result(rid);
            if (r.closed() || r.claimed())
                throw error(Code.CONFLICT, "Result unavailable for transfer");
            r.claim();
            return new RowSource() {
                boolean closed;

                /** Возвращает колонки закреплённого результата. */
                public List<Sql.ResultColumn> columns() {
                    return r.columns();
                }

                /** Сообщает область {@link RowScope#REMAINING}. */
                public RowScope scope() {
                    return RowScope.REMAINING;
                }

                /** Проверяет закрытие и читает пакет с правом владельца курсора. */
                public synchronized Sql.Batch fetch(int size) {
                    if (closed)
                        throw error(Code.NOT_FOUND, "Source closed");
                    return ExecuteSql.this.fetch(rid, size, true);
                }

                /** Закрывает курсор один раз, если сессия ещё не закрывается. */
                public synchronized void close() {
                    if (closed)
                        return;
                    closed = true;
                    scope.locked(() -> {
                        if (!scope.isClosing()) closeResult(rid, true);
                        return null;
                    });
                }
            };
        });
    }

    /**
     * Отмена идёт вне блокировки работы сессии; адаптер должен поддерживать параллельную отмену.
     * Пытается отменить активную операцию {@code oid} и возвращает {@link Sql.CancelResult}.
     * Неизвестный или уже неактивный ID даёт {@code NOT_ACTIVE}; операция другой сессии даёт {@link Code#WRONG_SESSION}.
     */
    public Sql.CancelResult cancel(OperationId oid) {
        if (oid == null || !scope.knownOperation(oid))
            return Sql.CancelResult.NOT_ACTIVE;
        if (!scope.ownedOperation(oid))
            throw error(Code.WRONG_SESSION, "Operation belongs to another session");
        Operation op = scope.operation(oid);
        if (op == null || !op.active())
            return Sql.CancelResult.NOT_ACTIVE;
        return org.databasebrowser.core.domain.Errors.safe(
            () -> scope.ports().sql().cancel(scope.session().id(), oid));
    }
}
