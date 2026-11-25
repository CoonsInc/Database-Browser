package org.databasebrowser.sqlite;

import static org.databasebrowser.core.domain.Errors.error;

import java.sql.SQLException;
import java.time.Duration;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Sql.CancelResult;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;

/** Прерывает текущую команду или чтение курсора внутри SQLite. */
final class SqliteOperations {
    private Active active;
    private OperationId earlyCancellation;
    private OperationId lastFinished;

    <T> T run(SQLiteConnection connection, OperationId id, Duration timeout, Work<T> work) {
        Active operation = begin(connection, id, timeout);
        try (operation) {
            operation.checkStopped();
            operation.installProgressHandler();
            return work.run(operation);
        } catch (SQLException e) {
            int primaryCode = e.getErrorCode() & 0xff;
            if (primaryCode == SQLiteErrorCode.SQLITE_INTERRUPT.code
                || primaryCode == SQLiteErrorCode.SQLITE_BUSY.code
                || primaryCode == SQLiteErrorCode.SQLITE_LOCKED.code) {
                if (operation.cancelled) {
                    throw SqliteErrors.failure(e, Code.CANCELLED, "SQLite operation was cancelled");
                }
                if (operation.timedOut()) {
                    throw SqliteErrors.failure(e, Code.BUSY_TIMEOUT, "SQLite operation timed out");
                }
            }
            throw SqliteErrors.failure(e, Code.SQL);
        } finally {
            finish(operation);
        }
    }

    synchronized CancelResult cancel(OperationId id) {
        if (id == null) {
            throw error(Code.INVALID_INPUT, "Operation ID is required");
        }
        if (active == null) {
            if (id.equals(lastFinished)) {
                return CancelResult.NOT_ACTIVE;
            }
            earlyCancellation = id;
            return CancelResult.REQUESTED;
        }
        if (!active.id.equals(id)) {
            return CancelResult.NOT_ACTIVE;
        }

        active.cancelled = true;
        try {
            active.connection.getDatabase().interrupt();
        } catch (SQLException e) {
            throw SqliteErrors.failure(e, Code.SQL);
        }
        return CancelResult.REQUESTED;
    }

    private synchronized Active begin(SQLiteConnection connection, OperationId id, Duration timeout) {
        if (active != null) {
            throw error(Code.CONFLICT, "Another SQLite operation is active");
        }
        active = new Active(connection, id, timeout, id.equals(earlyCancellation));
        earlyCancellation = null;
        return active;
    }

    private synchronized void finish(Active operation) {
        lastFinished = operation.id;
        active = null;
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Active operation) throws SQLException;
    }

    /** Проверяет отмену и срок выполнения между шагами SQLite. */
    static final class Active extends ProgressHandler implements AutoCloseable {
        private final SQLiteConnection connection;
        private final OperationId id;
        private final long startedAt = System.nanoTime();
        private final long timeoutNanos;
        private volatile boolean cancelled;
        private boolean progressHandlerInstalled;

        private Active(SQLiteConnection connection, OperationId id, Duration timeout,
            boolean cancelled) {
            this.connection = connection;
            this.id = id;
            this.cancelled = cancelled;
            this.timeoutNanos = timeoutNanos(timeout);
        }

        private void installProgressHandler() throws SQLException {
            ProgressHandler.setHandler(connection, 1000, this);
            progressHandlerInstalled = true;
        }

        void checkStopped() {
            if (cancelled) {
                throw error(Code.CANCELLED, "SQLite operation was cancelled");
            }
            if (timedOut()) {
                throw error(Code.BUSY_TIMEOUT, "SQLite operation timed out");
            }
        }

        private boolean timedOut() {
            return timeoutNanos > 0 && System.nanoTime() - startedAt >= timeoutNanos;
        }

        @Override
        protected int progress() {
            return cancelled || timedOut() ? 1 : 0;
        }

        @Override
        public void close() throws SQLException {
            if (progressHandlerInstalled) {
                ProgressHandler.clearHandler(connection);
            }
        }

        private static long timeoutNanos(Duration timeout) {
            try {
                return timeout.toNanos();
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }
    }
}
