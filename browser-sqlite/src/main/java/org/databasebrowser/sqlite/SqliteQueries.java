package org.databasebrowser.sqlite;

import static org.databasebrowser.core.domain.Errors.error;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Sql;
import org.databasebrowser.core.port.SqlExecutionPort;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.CoreStatement;

/** Выполняет SQL и хранит JDBC-курсоры одного подключения. */
final class SqliteQueries implements SqlExecutionPort {
    private final SqliteSession session;
    private final SqliteOperations operations = new SqliteOperations();
    private final Map<ResultId, Cursor> cursors = new HashMap<>();

    SqliteQueries(SqliteSession session) {
        this.session = session;
    }

    @Override
    public Sql.Execution execute(SessionId id, Sql.Request request) {
        SQLiteConnection connection = session.connection(id);
        validateRequest(request);
        boolean writesRows = SqliteSql.validateSingleStatement(request.sql());

        ResultId resultId = new ResultId(UUID.randomUUID());
        try {
            return operations.run(connection, request.operationId(), request.options().timeout(),
                operation -> execute(connection, request, writesRows, resultId, operation));
        } catch (RuntimeException e) {
            closeAfterFailure(resultId, e);
            throw e;
        }
    }

    @Override
    public Sql.Batch fetchRows(SessionId id, ResultId resultId, int size) {
        return fetchRows(id, resultId, new OperationId(UUID.randomUUID()), size);
    }

    @Override
    public Sql.Batch fetchRows(SessionId id, ResultId resultId, OperationId operationId, int size) {
        SQLiteConnection connection = session.connection(id);
        if (size < 1 || operationId == null) {
            throw error(Code.INVALID_INPUT, "A positive batch size and operation ID are required");
        }
        Cursor cursor = cursors.get(resultId);
        if (cursor == null || cursor.failed) {
            throw error(Code.NOT_FOUND, "SQLite result is closed");
        }

        try {
            return operations.run(connection, operationId, cursor.timeout,
                operation -> cursor.read(size, operation));
        } catch (RuntimeException e) {
            cursor.failed = true;
            closeAfterFailure(resultId, e);
            throw e;
        }
    }

    @Override
    public Sql.CancelResult cancel(SessionId id, OperationId operationId) {
        session.checkOwner(id);
        return session.isOpen() ? operations.cancel(operationId) : Sql.CancelResult.NOT_ACTIVE;
    }

    @Override
    public void closeResult(SessionId id, ResultId resultId) {
        session.checkOwner(id);
        try {
            closeResult(resultId);
        } catch (SQLException e) {
            throw SqliteErrors.failure(e, Code.SQL);
        }
    }

    void closeResults() throws SQLException {
        SQLException failure = null;
        for (var iterator = cursors.values().iterator(); iterator.hasNext();) {
            try {
                iterator.next().close();
                iterator.remove();
            } catch (SQLException e) {
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

    void clearClosedResults() {
        cursors.clear();
    }

    private Sql.Execution execute(SQLiteConnection connection, Sql.Request request,
        boolean writesRows, ResultId resultId, SqliteOperations.Active operation) throws SQLException {
        long startedAt = System.nanoTime();
        PreparedStatement statement = connection.prepareStatement(request.sql());
        try {
            bindParameters(statement, request.parameters());
            operation.checkStopped();
            if (!statement.execute()) {
                OptionalLong affected = writesRows
                    ? OptionalLong.of(connection.getDatabase().changes()) : OptionalLong.empty();
                statement.close();
                return success(startedAt, Optional.empty(), List.of(),
                    new Sql.Batch(List.of(), true), affected);
            }

            ResultSet rows = statement.getResultSet();
            List<Sql.ResultColumn> columns = readColumns(statement, rows.getMetaData());
            Cursor cursor = new Cursor(statement, rows, columns.size(), request.options().timeout());
            Sql.Batch first = cursor.read(request.options().initialBatchSize(), operation);
            cursors.put(resultId, cursor);
            return success(startedAt, Optional.of(resultId), columns, first, OptionalLong.empty());
        } catch (SQLException | RuntimeException e) {
            try {
                statement.close();
            } catch (SQLException cleanup) {
                e.addSuppressed(SqliteErrors.failure(cleanup, Code.SQL));
            }
            throw e;
        }
    }

    private void closeAfterFailure(ResultId resultId, RuntimeException failure) {
        try {
            closeResult(resultId);
        } catch (SQLException e) {
            failure.addSuppressed(SqliteErrors.failure(e, Code.SQL));
        }
    }

    private void closeResult(ResultId resultId) throws SQLException {
        Cursor cursor = cursors.get(resultId);
        if (cursor != null) {
            cursor.close();
            cursors.remove(resultId);
        }
    }

    private static void validateRequest(Sql.Request request) {
        if (request == null || request.operationId() == null || request.options() == null) {
            throw error(Code.INVALID_INPUT, "SQLite request, operation ID and options are required");
        }
        Sql.Options options = request.options();
        if (options.mode() != ExecutionMode.SINGLE_STATEMENT
            || options.transaction() != TransactionPolicy.AUTOCOMMIT) {
            throw error(Code.UNSUPPORTED, "SQLite supports single statements in autocommit mode");
        }
        if (options.initialBatchSize() < 1 || options.timeout() == null
            || options.timeout().isNegative()) {
            throw error(Code.INVALID_INPUT, "Invalid SQLite batch size or timeout");
        }
    }

    /** Проверяет позиции и привязывает значения к подготовленной команде. */
    private static void bindParameters(PreparedStatement statement, List<Sql.Parameter> parameters)
        throws SQLException {
        int count = statement.getParameterMetaData().getParameterCount();
        if (count != parameters.size()) {
            throw error(Code.INVALID_INPUT, "SQL parameter count does not match the request");
        }

        boolean[] bound = new boolean[count];
        for (Sql.Parameter parameter : parameters) {
            int position = parameter.position();
            if (position < 1 || position > count || bound[position - 1]) {
                throw error(Code.INVALID_INPUT, "SQL parameter position is invalid or duplicated");
            }
            bindValue(statement, position, parameter.value());
            bound[position - 1] = true;
        }
    }

    private static void bindValue(PreparedStatement statement, int position, Sql.Value value)
        throws SQLException {
        if (value instanceof Sql.NullValue) {
            statement.setNull(position, Types.NULL);
        } else if (value instanceof Sql.TextValue text) {
            statement.setString(position, text.value());
        } else if (value instanceof Sql.BinaryValue binary) {
            statement.setBytes(position, binary.value());
        } else if (value instanceof Sql.NumberValue number) {
            bindNumber(statement, position, number.value());
        } else if (value instanceof Sql.BooleanValue bool) {
            statement.setBoolean(position, bool.value());
        } else if (value instanceof Sql.DateValue date) {
            statement.setString(position, date.value().toString());
        } else if (value instanceof Sql.TimeValue time) {
            statement.setString(position, time.value().toString());
        } else if (value instanceof Sql.TimestampValue timestamp) {
            statement.setString(position, timestamp.value().toString());
        } else if (value instanceof Sql.OffsetTimestampValue timestamp) {
            statement.setString(position, timestamp.value().toString());
        }
    }

    private static void bindNumber(PreparedStatement statement, int position, BigDecimal value)
        throws SQLException {
        try {
            statement.setLong(position, value.longValueExact());
        } catch (ArithmeticException e) {
            double real = value.doubleValue();
            if (!Double.isFinite(real) || real == 0 && value.signum() != 0) {
                throw error(Code.INVALID_INPUT, "Number is outside the SQLite numeric range");
            }
            statement.setDouble(position, real);
        }
    }

    /** Получает объявленный тип, если колонка результата пришла из таблицы. */
    private static List<Sql.ResultColumn> readColumns(PreparedStatement statement,
        ResultSetMetaData metadata) throws SQLException {
        var pointer = ((CoreStatement) statement).pointer;
        int count = metadata.getColumnCount();
        List<Sql.ResultColumn> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            int index = i - 1;
            String type = pointer.safeRun((db, ptr) -> db.column_decltype(ptr, index));
            columns.add(new Sql.ResultColumn(metadata.getColumnLabel(i),
                Optional.ofNullable(type).filter(name -> !name.isBlank()), Optional.empty()));
        }
        return columns;
    }

    private static Sql.Value readValue(Object value) {
        if (value == null) {
            return new Sql.NullValue();
        }
        if (value instanceof byte[] binary) {
            return new Sql.BinaryValue(binary);
        }
        if (value instanceof Integer || value instanceof Long) {
            return new Sql.NumberValue(BigDecimal.valueOf(((Number) value).longValue()));
        }
        if (value instanceof Double real && Double.isFinite(real)) {
            return new Sql.NumberValue(BigDecimal.valueOf(real));
        }
        if (value instanceof String text) {
            return new Sql.TextValue(text);
        }
        throw error(Code.UNSUPPORTED, "SQLite returned a value outside the supported types");
    }

    private static Sql.Execution success(long startedAt, Optional<ResultId> resultId,
        List<Sql.ResultColumn> columns, Sql.Batch first, OptionalLong affected) {
        long received = first.rows().size();
        OptionalLong total = resultId.isPresent() && first.complete()
            ? OptionalLong.of(received) : OptionalLong.empty();
        var statistics =
            new Sql.Statistics(Duration.ofNanos(System.nanoTime() - startedAt), received, affected, total);
        return new Sql.Execution(Sql.Status.SUCCEEDED, resultId, columns, first, statistics, List.of());
    }

    /** Держит одну строку вперёд, чтобы точно определить конец результата. */
    private static final class Cursor {
        private final PreparedStatement statement;
        private final ResultSet rows;
        private final int width;
        private final Duration timeout;
        private boolean started;
        private boolean hasRow;
        private boolean closed;
        private boolean failed;

        private Cursor(PreparedStatement statement, ResultSet rows, int width, Duration timeout) {
            this.statement = statement;
            this.rows = rows;
            this.width = width;
            this.timeout = timeout;
        }

        private Sql.Batch read(int size, SqliteOperations.Active operation) throws SQLException {
            if (closed) {
                return new Sql.Batch(List.of(), true);
            }
            List<Sql.Row> batch = new ArrayList<>();
            if (!started) {
                advance(operation);
                started = true;
            }
            while (hasRow && batch.size() < size) {
                List<Sql.Value> values = new ArrayList<>(width);
                for (int i = 1; i <= width; i++) {
                    values.add(readValue(rows.getObject(i)));
                }
                batch.add(new Sql.Row(values));
                advance(operation);
            }
            if (!hasRow) {
                close();
            }
            return new Sql.Batch(batch, !hasRow);
        }

        private void advance(SqliteOperations.Active operation) throws SQLException {
            operation.checkStopped();
            hasRow = rows.next();
            operation.checkStopped();
        }

        private void close() throws SQLException {
            if (!closed) {
                statement.close();
                closed = true;
            }
        }
    }
}
