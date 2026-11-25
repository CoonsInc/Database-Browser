package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Sql;

/**
 * SINGLE_STATEMENT отклоняет скрипты целиком средствами движка, без разбиения по точке с запятой.
 * У каждого результата уникальный UUID. Отключение освобождает все ресурсы и активные операции.
 * Адаптер выполняет переданный SQL без переписывания и автоматических повторов.
 */
public interface SqlExecutionPort {
    Sql.Execution execute(SessionId sessionId, Sql.Request request);

    Sql.Batch fetchRows(SessionId sessionId, ResultId resultId, int batchSize);

    /**
     * Читает порцию под отдельным идентификатором. Для отменяемого чтения адаптер
     * переопределяет этот метод и регистрирует идентификатор для cancel.
     */
    default Sql.Batch fetchRows(
        SessionId sessionId, ResultId resultId, OperationId operationId, int batchSize) {
        return fetchRows(sessionId, resultId, batchSize);
    }

    /**
     * Отменяет конкретную операцию. Принимает запрос до регистрации в движке
     * и во время execute, explain или fetchRows, без ожидания завершения.
     */
    Sql.CancelResult cancel(SessionId sessionId, OperationId operationId);

    /** Освобождает курсор. Вызов можно повторить после сбоя или частичной очистки. */
    void closeResult(SessionId sessionId, ResultId resultId);
}
