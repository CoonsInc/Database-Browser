package org.databasebrowser.sqlite;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.CoreException;
import org.databasebrowser.core.domain.Errors.Diagnostic;
import org.sqlite.SQLiteErrorCode;

/** Преобразует ошибки драйвера без раскрытия путей, текста SQL и значений. */
final class SqliteErrors {
    private SqliteErrors() {}

    static CoreException failure(SQLException exception, Code fallback) {
        SQLiteErrorCode primaryCode = SQLiteErrorCode.getErrorCode(exception.getErrorCode() & 0xff);
        return switch (primaryCode) {
            case SQLITE_BUSY, SQLITE_LOCKED ->
                failure(exception, Code.BUSY_TIMEOUT, "SQLite database is busy or locked");
            case SQLITE_CONSTRAINT ->
                failure(exception, Code.CONSTRAINT, "SQLite constraint failed");
            case SQLITE_INTERRUPT ->
                failure(exception, Code.CANCELLED, "SQLite operation was interrupted");
            case SQLITE_READONLY ->
                failure(exception, Code.SQL, "SQLite database is read-only");
            case SQLITE_CANTOPEN, SQLITE_NOTADB, SQLITE_CORRUPT ->
                failure(exception, Code.CONNECTION, "Cannot use the SQLite database");
            case SQLITE_IOERR, SQLITE_FULL ->
                failure(exception, Code.IO, "SQLite could not read or write the database");
            default ->
                failure(exception, fallback, "SQLite operation failed");
        };
    }

    static CoreException failure(SQLException exception, Code code, String message) {
        int vendorCode = exception.getErrorCode();
        Diagnostic diagnostic = new Diagnostic(
            vendorCode == 0 ? Optional.empty() : Optional.of(Integer.toString(vendorCode)),
            Optional.ofNullable(exception.getSQLState()), message);
        return new CoreException(code, message, List.of(), Optional.of(diagnostic));
    }
}
