package org.databasebrowser.sqlite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.databasebrowser.core.application.schema.SchemaService;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.application.sql.SqlService;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.CoreException;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema;
import org.databasebrowser.core.domain.Sql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.Function;

/** Проверяет адаптер через сервисы приложения на реальных файлах SQLite. */
class SqliteAdapterTest {
    private static final Sql.Options OPTIONS =
        new Sql.Options(ExecutionMode.SINGLE_STATEMENT, TransactionPolicy.AUTOCOMMIT, 2, Duration.ZERO);

    @TempDir
    Path folder;

    private final SessionRegistry sessions = new SessionRegistry(List.of(new SqliteAdapterProvider()));
    private final SqlService sql = new SqlService(sessions);
    private final SchemaService schema = new SchemaService(sessions, (target, text, options) -> {
        throw new AssertionError("The SQLite adapter must not write export files");
    });

    @AfterEach
    void closeSessions() {
        sessions.close();
    }

    @Test
    void readsSchemaAndReopensFileWithSpecialCharacters() {
        Path file = folder.resolve("тест #50%.sqlite");
        var session = sessions.connect(settings(file, false));
        SessionId id = session.id();

        assertEquals("SQLite", session.info().product());
        assertTrue(sessions.checkConnection(id));
        String before = schema.readSchema(id).stateToken();
        execute(id, """
            CREATE TABLE "order; items" (
                id INTEGER PRIMARY KEY,
                note UNIQUE,
                price DECIMAL(10, 2) DEFAULT 0
            )
            """);
        execute(id, """
            CREATE TABLE child (
                parent_id INTEGER REFERENCES "order; items"(id) ON DELETE CASCADE
            )
            """);
        execute(id, """
            CREATE INDEX priced ON "order; items" (price DESC) WHERE price > 0
            """);
        execute(id, """
            CREATE VIEW priced_items AS SELECT id, price FROM "order; items" WHERE price > 0
            """);

        Schema.Snapshot snapshot = schema.readSchema(id);
        assertNotEquals(before, snapshot.stateToken());
        assertEquals(snapshot.stateToken(), schema.readSchema(id).stateToken());
        assertEquals(List.of("main"), snapshot.namespaces().stream().map(Schema.QualifiedName::name).toList());
        var table = (Schema.Table) object(snapshot, "order; items");
        assertEquals(Optional.of("main"), table.name().namespace());
        assertEquals(Schema.DataType.undeclared(), table.columns().get(1).type());
        assertEquals(List.of("10", "2"), table.columns().get(2).type().parameters());
        assertEquals(Optional.of("0"), table.columns().get(2).defaultExpression());
        assertEquals(List.of(new Schema.PrimaryKey(Optional.empty(), List.of("id")),
            new Schema.Unique(Optional.empty(), List.of("note"))), table.constraints());
        assertEquals(table.definition(), schema.readObjectDefinition(id, table.id()));

        var index = (Schema.Index) object(snapshot, "priced");
        assertEquals(table.id(), index.table());
        assertEquals(Optional.of("price > 0"), index.predicate());
        assertEquals(Schema.Direction.DESC, index.terms().get(0).direction());
        var view = (Schema.View) object(snapshot, "priced_items");
        assertEquals(List.of("id", "price"), view.columnNames());

        var child = (Schema.Table) object(snapshot, "child");
        var foreignKey = (Schema.ForeignKey) child.constraints().get(0);
        assertEquals(table.name(), foreignKey.referencedTable());
        assertEquals(Schema.ForeignKeyAction.CASCADE, foreignKey.onDelete());
        assertTrue(schema.findDependencies(id, table.id()).edges().stream()
            .anyMatch(edge -> edge.dependent().equals(child.id())));
        assertFalse(snapshot.dependencies().complete());
        assertEquals(Code.CONSTRAINT, assertThrows(CoreException.class,
            () -> execute(id, "INSERT INTO child VALUES (999)")).code());

        assertEquals(2, execute(id, """
            INSERT INTO "order; items" (note, price) VALUES ('первый', 3), ('второй', 7)
            """).statistics().affectedRows().orElseThrow());
        sessions.disconnect(id);
        assertTrue(Files.isRegularFile(file));

        SessionId reopened = sessions.connect(settings(file, true)).id();
        assertNotEquals(id, reopened);
        assertEquals(2, numberAt(execute(reopened, "SELECT COUNT(*) FROM priced_items"), 0));
        CoreException readOnly = assertThrows(CoreException.class,
            () -> execute(reopened, "DELETE FROM child"));
        assertEquals(Code.SQL, readOnly.code());
        assertTrue(readOnly.diagnostic().isPresent());
        assertFalse(readOnly.getMessage().contains(file.toString()));
    }

    @Test
    void fetchesBatchesInOrderAndMarksTheExactLastBatch() {
        SessionId id = open("pages.sqlite");
        Sql.Execution result = execute(id, """
            SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            """);

        assertEquals(List.of("n"), result.columns().stream().map(Sql.ResultColumn::label).toList());
        assertEquals(List.of(1L, 2L), numbers(result.firstBatch()));
        assertFalse(result.firstBatch().complete());
        assertTrue(result.statistics().totalRows().isEmpty());
        var cursor = result.result().orElseThrow();
        Sql.Batch last = sql.fetchRows(id, cursor, 2);
        assertEquals(List.of(3L, 4L), numbers(last));
        assertTrue(last.complete());
        assertEquals(new Sql.Batch(List.of(), true), sql.fetchRows(id, cursor, 2));
        sql.closeResult(id, cursor);
        sql.closeResult(id, cursor);
        assertEquals(Code.NOT_FOUND,
            assertThrows(CoreException.class, () -> sql.fetchRows(id, cursor, 2)).code());
    }

    @Test
    void returnsColumnsForEmptyResultsAndPreservesSqliteValueTypes() {
        SessionId id = open("types.sqlite");
        execute(id, "CREATE TABLE data (s TEXT, n INTEGER, b BLOB, nullable)");
        Sql.Execution empty = execute(id, "SELECT s AS label, n FROM data");
        assertEquals(List.of("label", "n"), empty.columns().stream().map(Sql.ResultColumn::label).toList());
        assertEquals(Optional.of("TEXT"), empty.columns().get(0).declaredType());
        assertEquals(new Sql.Batch(List.of(), true), empty.firstBatch());
        assertEquals(0, empty.statistics().totalRows().orElseThrow());

        byte[] bytes = {0, 7, -1};
        String text = "тест'; -- внутри значения";
        List<Sql.Parameter> parameters = List.of(parameter(4, new Sql.NullValue()),
            parameter(1, new Sql.TextValue(text)), parameter(3, new Sql.BinaryValue(bytes)),
            parameter(2, new Sql.NumberValue(BigDecimal.valueOf(Long.MAX_VALUE))));
        var request = new Sql.Request(sql.newOperationId(), "INSERT INTO data VALUES (?, ?, ?, ?)",
            parameters, OPTIONS);
        assertEquals(1, sql.execute(id, request).statistics().affectedRows().orElseThrow());

        Sql.Execution rows = execute(id, "SELECT *, n / 2.0 + 0.5 AS divided FROM data");
        List<Sql.Value> values = rows.firstBatch().rows().get(0).values();
        assertEquals(new Sql.TextValue(text), values.get(0));
        assertEquals(new Sql.NumberValue(BigDecimal.valueOf(Long.MAX_VALUE)), values.get(1));
        assertArrayEquals(bytes, ((Sql.BinaryValue) values.get(2)).value());
        assertEquals(new Sql.NullValue(), values.get(3));
        assertTrue(rows.columns().get(4).declaredType().isEmpty());

        var invalid = new Sql.Request(sql.newOperationId(), "SELECT ?", List.of(), OPTIONS);
        assertEquals(Code.INVALID_INPUT,
            assertThrows(CoreException.class, () -> sql.execute(id, invalid)).code());
    }

    @Test
    void rejectsScriptsBeforeExecutionAndAllowsTriggerBodies() {
        SessionId id = open("script.sqlite");
        execute(id, "CREATE TABLE items (text TEXT)");
        execute(id, "CREATE TABLE audit (text TEXT)");

        CoreException script = assertThrows(CoreException.class, () -> execute(id,
            "INSERT INTO items VALUES ('first'); /* comment ; */ INSERT INTO items VALUES ('second');"));
        assertEquals(Code.INVALID_INPUT, script.code());
        assertEquals(0, numberAt(execute(id, "SELECT COUNT(*) FROM items"), 0));

        execute(id, """
            CREATE TRIGGER log_item AFTER INSERT ON items BEGIN
                INSERT INTO audit VALUES (NEW.text);
                INSERT INTO audit VALUES (CASE WHEN NEW.text = ';' THEN 'yes;' ELSE 'no' END);
            END;
            """);
        assertEquals(Code.INVALID_INPUT, assertThrows(CoreException.class, () -> execute(id, """
            CREATE TRIGGER multi AFTER INSERT ON items BEGIN SELECT 1; END; DROP TABLE items;
            """)).code());
        assertEquals(Code.UNSUPPORTED,
            assertThrows(CoreException.class, () -> execute(id, "BEGIN")).code());

        var inserted = execute(id, "INSERT INTO items VALUES (';'); -- ; ignored");
        assertEquals(1, inserted.statistics().affectedRows().orElseThrow());
        assertEquals(2, numberAt(execute(id, "SELECT COUNT(*) FROM audit"), 0));

        Schema.Snapshot snapshot = schema.readSchema(id);
        var trigger = (Schema.Trigger) object(snapshot, "log_item");
        assertEquals(object(snapshot, "items").id(), trigger.owner());
    }

    @Test
    void isolatesFilesAndReleasesCursorsOnDisconnect() {
        SessionId first = open("first.sqlite");
        SessionId second = open("second.sqlite");
        execute(first, "CREATE TABLE only_first (value)");
        assertTrue(schema.readSchema(second).objects().isEmpty());

        var result = execute(first, "SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3")
            .result().orElseThrow();
        assertEquals(Code.WRONG_SESSION,
            assertThrows(CoreException.class, () -> sql.fetchRows(second, result, 2)).code());
        sessions.disconnect(first);
        assertEquals(Code.UNKNOWN_SESSION,
            assertThrows(CoreException.class, () -> sql.fetchRows(first, result, 2)).code());
        assertEquals(1, numberAt(execute(second, "SELECT 1"), 0));
    }

    @Test
    void rejectsInvalidFilesAndDoesNotCreateMissingReadOnlyFiles() throws Exception {
        Path missing = folder.resolve("missing.db");
        assertEquals(Code.CONNECTION, assertThrows(CoreException.class,
            () -> sessions.connect(settings(missing, true))).code());
        assertFalse(Files.exists(missing));

        Path invalid = Files.writeString(folder.resolve("invalid.db"), "not an SQLite database");
        var failure = assertThrows(CoreException.class, () -> sessions.connect(settings(invalid, false)));
        assertEquals(Code.CONNECTION, failure.code());
        assertFalse(failure.getMessage().contains(invalid.toString()));
        assertTrue(sessions.list().isEmpty());
    }

    @Test
    void interruptsLongSqlAtTheRequestTimeoutAndKeepsTheConnectionUsable() {
        SessionId id = open("timeout.sqlite");
        var options = new Sql.Options(ExecutionMode.SINGLE_STATEMENT, TransactionPolicy.AUTOCOMMIT, 1,
            Duration.ofMillis(40));
        var request = new Sql.Request(sql.newOperationId(), """
            WITH RECURSIVE nums(n) AS (
                VALUES(1) UNION ALL SELECT n + 1 FROM nums WHERE n < 1000000000
            )
            SELECT SUM(n) FROM nums
            """, List.of(), options);

        assertEquals(Code.BUSY_TIMEOUT, assertThrows(CoreException.class,
            () -> sql.execute(id, request)).code());
        assertEquals(2, numberAt(execute(id, "SELECT 2"), 0));
    }

    @Test
    void acceptsCancellationBeforePortRegistrationAndDoesNotCancelTheNextCommand() {
        var provider = new SqliteAdapterProvider();
        var ports = provider.ports();
        SessionId id = ports.connection().connect(settings(folder.resolve("early.sqlite"), false)).id();
        var operation = sql.newOperationId();
        try {
            assertEquals(Sql.CancelResult.REQUESTED, ports.sql().cancel(id, operation));
            var request = new Sql.Request(operation, "CREATE TABLE cancelled (value)", List.of(), OPTIONS);
            assertEquals(Code.CANCELLED,
                assertThrows(CoreException.class, () -> ports.sql().execute(id, request)).code());
            assertEquals(Sql.CancelResult.NOT_ACTIVE, ports.sql().cancel(id, operation));
            assertTrue(ports.schema().readSchema(id).objects().isEmpty());
            ports.sql().execute(id, new Sql.Request(sql.newOperationId(), "CREATE TABLE ok (value)",
                List.of(), OPTIONS));
            assertEquals("ok", ports.schema().readSchema(id).objects().get(0).name().name());
        } finally {
            ports.connection().disconnect(id);
        }
    }

    @Test
    void cancelsAnActiveFetchWithoutWaitingForTheNativeStep() throws Exception {
        var ports = new SqliteAdapterProvider().ports();
        SessionId id = ports.connection().connect(settings(folder.resolve("cancel-fetch.sqlite"), false)).id();
        var executor = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var connection = ((SqliteSession) ports.connection()).connection(id);
            Function.create(connection, "pause_fetch", new Function() {
                @Override
                protected void xFunc() throws SQLException {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new SQLException("Timed out waiting for the test to release the function");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Test function was interrupted", e);
                    }
                    result(3);
                }
            });

            var options = new Sql.Options(ExecutionMode.SINGLE_STATEMENT, TransactionPolicy.AUTOCOMMIT,
                1, Duration.ZERO);
            var independent = ports.sql().execute(id, new Sql.Request(sql.newOperationId(),
                "SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12", List.of(), options));
            var execution = ports.sql().execute(id, new Sql.Request(sql.newOperationId(),
                "SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT pause_fetch()", List.of(), options));
            var cursor = execution.result().orElseThrow();
            var read = sql.newOperationId();
            var fetching = executor.submit(() -> ports.sql().fetchRows(id, cursor, read, 1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var cancelling = executor.submit(() -> ports.sql().cancel(id, read));
            assertEquals(Sql.CancelResult.REQUESTED, cancelling.get(4, TimeUnit.SECONDS));
            release.countDown();

            var failure = assertThrows(ExecutionException.class, () -> fetching.get(5, TimeUnit.SECONDS));
            assertEquals(Code.CANCELLED, assertInstanceOf(CoreException.class, failure.getCause()).code());
            assertEquals(Sql.CancelResult.NOT_ACTIVE, ports.sql().cancel(id, read));
            ports.sql().closeResult(id, cursor);
            var otherCursor = independent.result().orElseThrow();
            assertEquals(List.of(11L, 12L), numbers(ports.sql().fetchRows(id, otherCursor, 2)));
            assertEquals(4, numberAt(ports.sql().execute(id,
                new Sql.Request(sql.newOperationId(), "SELECT 4", List.of(), options)), 0));
        } finally {
            release.countDown();
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } finally {
                ports.connection().disconnect(id);
            }
        }
    }

    private SessionId open(String name) {
        return sessions.connect(settings(folder.resolve(name), false)).id();
    }

    private Sql.Execution execute(SessionId id, String statement) {
        return sql.execute(id, new Sql.Request(sql.newOperationId(), statement, List.of(), OPTIONS));
    }

    private static Settings settings(Path path, boolean readOnly) {
        return new Settings(SqliteAdapterProvider.ID, "SQLite для теста",
            Map.of("location", path.toString(), "readOnly", Boolean.toString(readOnly)), Map.of());
    }

    private static Sql.Parameter parameter(int position, Sql.Value value) {
        return new Sql.Parameter(position, Optional.empty(), value);
    }

    private static Schema.SchemaObject object(Schema.Snapshot snapshot, String name) {
        return snapshot.objects().stream().filter(value -> value.name().name().equals(name))
            .findFirst().orElseThrow();
    }

    private static List<Long> numbers(Sql.Batch batch) {
        return batch.rows().stream().map(row -> ((Sql.NumberValue) row.values().get(0)).value().longValueExact())
            .toList();
    }

    private static long numberAt(Sql.Execution result, int position) {
        return ((Sql.NumberValue) result.firstBatch().rows().get(0).values().get(position))
            .value().longValueExact();
    }
}
