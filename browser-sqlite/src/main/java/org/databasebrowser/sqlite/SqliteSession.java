package org.databasebrowser.sqlite;

import static org.databasebrowser.core.domain.Errors.error;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Connections.SupportedType;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.port.ConnectionPort;
import org.databasebrowser.core.port.DatabaseInfoPort;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteOpenMode;

/** Хранит одно JDBC-подключение и освобождает ресурсы его результатов. */
final class SqliteSession implements ConnectionPort, DatabaseInfoPort {
    private static final int DEFAULT_BUSY_TIMEOUT_MS = 3000;
    private static final Capabilities CAPABILITIES = new Capabilities(Map.of(), false, false,
        Set.of(ExecutionMode.SINGLE_STATEMENT), Set.of(TransactionPolicy.AUTOCOMMIT));
    private static final List<SupportedType> TYPES = List.of("INTEGER", "INT", "REAL", "TEXT",
        "BLOB", "NUMERIC", "ANY").stream()
        .map(name -> new SupportedType(name, List.of(), Optional.empty()))
        .toList();

    private final SqliteQueries queries = new SqliteQueries(this);
    private volatile SessionId id;
    private volatile SQLiteConnection connection;
    private DatabaseInfo databaseInfo;

    SqliteQueries queries() {
        return queries;
    }

    @Override
    public synchronized Opened connect(Settings settings) {
        if (id != null) {
            throw error(Code.CONFLICT, "SQLite ports already belong to a session");
        }
        if (settings == null || !SqliteAdapterProvider.ID.equals(settings.adapter())) {
            throw error(Code.INVALID_INPUT, "SQLite connection settings are required");
        }

        Map<String, String> properties = settings.properties();
        String location = properties.get("location");
        if (location == null || location.isBlank()) {
            throw error(Code.INVALID_INPUT, "SQLite location is required");
        }
        boolean readOnly = parseReadOnly(properties.getOrDefault("readOnly", "false"));
        int busyTimeout = parseBusyTimeout(properties.getOrDefault("busyTimeoutMs",
            Integer.toString(DEFAULT_BUSY_TIMEOUT_MS)));
        String url = jdbcUrl(location, readOnly);

        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(readOnly);
        config.setOpenMode(SQLiteOpenMode.OPEN_URI);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(busyTimeout);

        try {
            SQLiteConnection opened = JDBC.createConnection(url, config.toProperties());
            try {
                // Обращение к схеме проверяет, что файл действительно читается как SQLite.
                try (var statement = opened.createStatement();
                     var metadata = statement.executeQuery("PRAGMA main.schema_version")) {
                    metadata.next();
                }
                databaseInfo = new DatabaseInfo(SqliteAdapterProvider.ID, "SQLite",
                    opened.getMetaData().getDatabaseProductVersion(),
                    Map.of("readOnly", Boolean.toString(readOnly)));
            } catch (SQLException | RuntimeException e) {
                try {
                    opened.close();
                } catch (SQLException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }

            SessionId session = new SessionId(UUID.randomUUID());
            connection = opened;
            id = session;
            return new Opened(session, databaseInfo);
        } catch (SQLException e) {
            throw SqliteErrors.failure(e, Code.CONNECTION);
        }
    }

    @Override
    public boolean checkConnection(SessionId sessionId) {
        try {
            return connection(sessionId).isValid(0);
        } catch (SQLException e) {
            throw SqliteErrors.failure(e, Code.CONNECTION);
        }
    }

    @Override
    public synchronized void disconnect(SessionId sessionId) {
        checkOwner(sessionId);
        SQLiteConnection current = connection;
        if (current == null) {
            return;
        }

        SQLException failure = null;
        try {
            queries.closeResults();
        } catch (SQLException e) {
            failure = e;
        }
        try {
            current.close();
            connection = null;
            queries.clearClosedResults();
        } catch (SQLException e) {
            if (failure == null) {
                failure = e;
            } else if (failure != e) {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw SqliteErrors.failure(failure, Code.CONNECTION);
        }
    }

    @Override
    public DatabaseInfo getDatabaseInfo(SessionId sessionId) {
        connection(sessionId);
        return databaseInfo;
    }

    @Override
    public Capabilities getCapabilities(SessionId sessionId) {
        connection(sessionId);
        return CAPABILITIES;
    }

    @Override
    public List<SupportedType> getSupportedTypes(SessionId sessionId) {
        connection(sessionId);
        return TYPES;
    }

    SQLiteConnection connection(SessionId sessionId) {
        checkOwner(sessionId);
        SQLiteConnection current = connection;
        if (current == null) {
            throw error(Code.UNKNOWN_SESSION, "SQLite session is closed");
        }
        return current;
    }

    void checkOwner(SessionId sessionId) {
        SessionId current = id;
        if (current == null) {
            throw error(Code.UNKNOWN_SESSION, "SQLite session has not been opened");
        }
        if (!current.equals(sessionId)) {
            throw error(Code.WRONG_SESSION, "SQLite ports belong to another session");
        }
    }

    boolean isOpen() {
        return connection != null;
    }

    static boolean knownType(String name) {
        return TYPES.stream().anyMatch(type -> SqliteSql.sameIdentifier(type.name(), name));
    }

    private static boolean parseReadOnly(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw error(Code.INVALID_INPUT, "SQLite readOnly must be true or false");
    }

    private static int parseBusyTimeout(String value) {
        try {
            int timeout = Integer.parseInt(value);
            if (timeout >= 0) {
                return timeout;
            }
        } catch (NumberFormatException ignored) {
            // Здесь и для отрицательных значений возвращается одна ошибка настройки.
        }
        throw error(Code.INVALID_INPUT, "SQLite busyTimeoutMs must be a non-negative integer");
    }

    private static String jdbcUrl(String location, boolean readOnly) {
        if (location.equals(":memory:")) {
            if (readOnly) {
                throw error(Code.INVALID_INPUT, "An in-memory SQLite database requires write access");
            }
            return "jdbc:sqlite::memory:";
        }

        try {
            Path path = Path.of(location).toAbsolutePath().normalize();
            return "jdbc:sqlite:" + path.toUri().toASCIIString();
        } catch (InvalidPathException e) {
            throw error(Code.INVALID_INPUT, "SQLite location is not a valid path");
        }
    }
}
