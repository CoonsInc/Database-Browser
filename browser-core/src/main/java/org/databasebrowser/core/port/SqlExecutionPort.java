package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Sql;

/**
 * SINGLE_STATEMENT must reject scripts reliably using engine facilities; never split on semicolon,
 * silently discard subsequent results, or run only the first statement. Register cancellation
 * before starting engine work and tolerate cancel racing registration. Result IDs must be unique
 * UUIDs. disconnect releases all resources including in-flight work. No implicit retry or SQL
 * rewriting.
 */
public interface SqlExecutionPort {
    Sql.Execution execute(SessionId sessionId, Sql.Request request);

    Sql.Batch fetchRows(SessionId sessionId, ResultId resultId, int batchSize);

    Sql.CancelResult cancel(SessionId sessionId, OperationId operationId);

    void closeResult(SessionId sessionId, ResultId resultId);
}
