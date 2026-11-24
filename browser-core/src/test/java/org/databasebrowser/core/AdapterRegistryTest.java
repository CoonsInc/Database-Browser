package org.databasebrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.databasebrowser.core.application.AdapterFactory;
import org.databasebrowser.core.application.AdapterInfo;
import org.databasebrowser.core.application.AdapterRegistry;
import org.databasebrowser.core.application.ChangeValidator;
import org.databasebrowser.core.application.ConnectionValuesReader;
import org.databasebrowser.core.application.ExportPorts;
import org.databasebrowser.core.application.SessionScope;
import org.databasebrowser.core.application.Usecases;
import org.databasebrowser.core.domain.Changes.AddConstraint;
import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.ApplyStatus;
import org.databasebrowser.core.domain.Changes.CheckKind;
import org.databasebrowser.core.domain.Changes.CreateTable;
import org.databasebrowser.core.domain.Changes.DataCheck;
import org.databasebrowser.core.domain.Changes.ExecutionRecipe;
import org.databasebrowser.core.domain.Changes.Plan;
import org.databasebrowser.core.domain.Changes.Preparation;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Changes.Rollback;
import org.databasebrowser.core.domain.Changes.Step;
import org.databasebrowser.core.domain.Changes.StepKind;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.ChangeKind;
import org.databasebrowser.core.domain.Connections.ConnectionSchema;
import org.databasebrowser.core.domain.Connections.ConnectionValues;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Connections.Session;
import org.databasebrowser.core.domain.Connections.SettingField;
import org.databasebrowser.core.domain.Connections.SettingKind;
import org.databasebrowser.core.domain.Connections.Support;
import org.databasebrowser.core.domain.Connections.SupportedType;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.CoreException;
import org.databasebrowser.core.domain.Errors.Diagnostic;
import org.databasebrowser.core.domain.Exports.CsvResult;
import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
import org.databasebrowser.core.domain.Exports.DependencyPolicy;
import org.databasebrowser.core.domain.Exports.RowScope;
import org.databasebrowser.core.domain.Exports.RowSource;
import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Exports.TextOptions;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.ChangePlanId;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.QueryPlans.Metrics;
import org.databasebrowser.core.domain.QueryPlans.Mode;
import org.databasebrowser.core.domain.QueryPlans.NodeType;
import org.databasebrowser.core.domain.QueryPlans.PlanNode;
import org.databasebrowser.core.domain.QueryPlans.QueryPlan;
import org.databasebrowser.core.domain.Schema.Column;
import org.databasebrowser.core.domain.Schema.DataType;
import org.databasebrowser.core.domain.Schema.Definition;
import org.databasebrowser.core.domain.Schema.Dependencies;
import org.databasebrowser.core.domain.Schema.PrimaryKey;
import org.databasebrowser.core.domain.Schema.QualifiedName;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Schema.Unique;
import org.databasebrowser.core.domain.Sql;
import org.databasebrowser.core.port.ConnectionPort;
import org.databasebrowser.core.port.DatabaseInfoPort;
import org.databasebrowser.core.port.DatabasePorts;
import org.databasebrowser.core.port.DdlExportPort;
import org.databasebrowser.core.port.QueryPlanPort;
import org.databasebrowser.core.port.SchemaChangePort;
import org.databasebrowser.core.port.SchemaReaderPort;
import org.databasebrowser.core.port.SqlExecutionPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdapterRegistryTest {
    static final AdapterId ADAPTER = new AdapterId("test");
    static final Definition DEFINITION =
        new Definition(Optional.of("original SQL"), Map.of(), true);
    static final Dependencies DEPS =
        new Dependencies(List.of(), false, List.of("engine limitation"));
    static final Column COLUMN =
        new Column("id", new DataType("integer", List.of(), Optional.empty(), true), false,
            Optional.empty(), Map.of());
    static final ConnectionSchema SCHEMA = new ConnectionSchema(List.of(
        new SettingField("path", "Database path", SettingKind.PATH, true, false),
        new SettingField("password", "Password", SettingKind.STRING, false, true),
        new SettingField("wal", "Use WAL", SettingKind.BOOLEAN, false, false),
        new SettingField("cache", "Cache size", SettingKind.NUMBER, false, false)));

    static class Fake implements ConnectionPort, DatabaseInfoPort, SchemaReaderPort,
                                  SchemaChangePort, SqlExecutionPort, QueryPlanPort, DdlExportPort {
        final Set<SessionId> live = ConcurrentHashMap.newKeySet();
        final Map<ResultId, SessionId> cursors = new ConcurrentHashMap<>();
        final Map<SessionId, String> tokens = new ConcurrentHashMap<>();
        int prepares, applies, fetches, disconnects;
        Prepared prepared, applied;
        CoreException connectError, executeError;
        boolean command, conflict, ambiguous;
        CountDownLatch started, release;
        volatile SessionId cancelled;
        QueryPlan queryPlan;

        public IdentifierRules identifierRules() {
            return String::equals;
        }

        public DatabasePorts ports() {
            return new DatabasePorts(this, this, this, this, this, this, this);
        }

        public Opened connect(ConnectionValues values) {
            if (connectError != null)
                throw connectError;
            SessionId id = new SessionId(UUID.randomUUID());
            live.add(id);
            tokens.put(id, "v1");
            return new Opened(id, getDatabaseInfo(id));
        }

        public boolean checkConnection(SessionId id) {
            return live.contains(id);
        }

        public void disconnect(SessionId id) {
            disconnects++;
            live.remove(id);
            cursors.entrySet().removeIf(e -> e.getValue().equals(id));
        }

        public DatabaseInfo getDatabaseInfo(SessionId id) {
            return new DatabaseInfo(ADAPTER, "TestDB", "1", Map.of());
        }

        public Capabilities getCapabilities(SessionId id) {
            Map<ChangeKind, Support> m = new EnumMap<>(ChangeKind.class);
            for (ChangeKind c : ChangeKind.values()) m.put(c, Support.MIGRATION);
            return new Capabilities(m, true, true, Set.of(ExecutionMode.SINGLE_STATEMENT),
                Set.of(TransactionPolicy.AUTOCOMMIT, TransactionPolicy.ATOMIC));
        }

        public List<SupportedType> getSupportedTypes(SessionId id) {
            return List.of(new SupportedType("integer", List.of(), Optional.empty()));
        }

        public Snapshot readSchema(SessionId id) {
            return new Snapshot(
                id, ADAPTER, tokens.get(id), List.of(), List.of(table(id, "existing")), DEPS);
        }

        public Definition readObjectDefinition(SessionId id, ObjectId o) {
            return DEFINITION;
        }

        public Dependencies findDependencies(SessionId id, ObjectId o) {
            return DEPS;
        }

        record Recipe(String token) implements ExecutionRecipe {}

        public Prepared prepareChange(SessionId id, Request r) {
            prepares++;
            Plan p = new Plan(new ChangePlanId(UUID.randomUUID()), id, r, tokens.get(id),
                List.of(new Step(StepKind.COPY_DATA, "recreate", List.of("preview"))),
                List.of(new DataCheck(CheckKind.NOT_NULL, "check", Optional.empty())), Set.of(),
                DEPS, List.of(), List.of("possible loss"),
                new Rollback(true, true, "engine contract"), true);
            prepared = new Prepared(p, new Recipe(p.schemaToken()));
            return prepared;
        }

        public ApplyResult applyChange(SessionId id, Prepared p) {
            applies++;
            applied = p;
            if (ambiguous)
                throw new CoreException(Code.OUTCOME_UNKNOWN, "transport lost");
            return new ApplyResult(conflict || !tokens.get(id).equals(p.preview().schemaToken())
                    ? ApplyStatus.CONFLICT
                    : ApplyStatus.APPLIED,
                List.of(), Optional.empty());
        }

        public Sql.Execution execute(SessionId id, Sql.Request r) {
            if (started != null) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("cancel timeout");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
            if (executeError != null)
                throw executeError;
            Optional<ResultId> rid =
                command ? Optional.empty() : Optional.of(new ResultId(UUID.randomUUID()));
            rid.ifPresent(x -> cursors.put(x, id));
            return new Sql.Execution(Sql.Status.SUCCEEDED, rid,
                List.of(new Sql.ResultColumn("id", Optional.of("integer"), Optional.empty())),
                new Sql.Batch(List.of(), command),
                new Sql.Statistics(Duration.ofMillis(1), 0,
                    command ? OptionalLong.of(3) : OptionalLong.empty(), OptionalLong.empty()),
                List.of());
        }

        public Sql.Batch fetchRows(SessionId id, ResultId rid, int size) {
            fetches++;
            return new Sql.Batch(
                List.of(new Sql.Row(List.of(new Sql.NumberValue(java.math.BigDecimal.ONE)))), true);
        }

        public Sql.CancelResult cancel(SessionId id, OperationId oid) {
            cancelled = id;
            if (release != null)
                release.countDown();
            return Sql.CancelResult.REQUESTED;
        }

        public void closeResult(SessionId id, ResultId rid) {
            cursors.remove(rid);
        }

        public QueryPlan explain(SessionId id, Sql.Request r, Mode mode) {
            return queryPlan;
        }

        public Ddl generateDdl(Snapshot snapshot, DdlOptions options) {
            return new Ddl("-- original\nDDL;\n", List.of(), false);
        }
    }

    static class Factory implements AdapterFactory {
        final Fake fake;
        final AdapterId id;
        final ConnectionSchema schema;
        final List<ConnectionValues> opened = new ArrayList<>();
        final List<String> names = new ArrayList<>();

        Factory(Fake fake, AdapterId id, ConnectionSchema schema) {
            this.fake = fake;
            this.id = id;
            this.schema = schema;
        }

        public AdapterId id() {
            return id;
        }

        public String displayName() {
            return "Test adapter";
        }

        public ConnectionSchema schema() {
            return schema;
        }

        public Usecases open(String displayName, ConnectionValues values, ExportPorts exports) {
            names.add(displayName);
            opened.add(values);
            ConnectionValuesReader.path(values, "path");
            ConnectionValuesReader.flag(values, "wal", false);
            ConnectionValuesReader.number(values, "cache", 100);
            values.secrets().get("password");
            Opened connection = fake.connect(values);
            SessionScope scope = new SessionScope(
                new Session(connection.id(), id, displayName, connection.info()),
                fake.ports(), fake.identifierRules());
            return Usecases.bind(scope, exports);
        }
    }

    static ConnectionValues values() {
        return new ConnectionValues(
            Map.of("path", "/tmp/test.db", "wal", "true", "cache", "10"),
            Map.of("password", "SECRET"));
    }

    static ExportPorts exports(List<String> writes) {
        return new ExportPorts((source, target, options, cancellation) -> new CsvResult(0),
            (target, text, options) -> writes.add(text));
    }

    List<String> writes = new ArrayList<>();
    Fake fake;
    Factory factory;
    AdapterRegistry registry;
    Usecases first, second;
    SessionId a, b;

    @BeforeEach
    void setup() {
        fake = new Fake();
        factory = new Factory(fake, ADAPTER, SCHEMA);
        registry = new AdapterRegistry(List.of(factory), exports(writes));
        first = open("first", values());
        second = open("second", values());
        a = first.scope().session().id();
        b = second.scope().session().id();
    }

    @AfterEach
    void shutdown() {
        first.close();
        second.close();
    }

    Usecases open(String displayName, ConnectionValues values) {
        return registry.open(ADAPTER, displayName, values);
    }

    static Table table(SessionId id, String name) {
        return new Table(new ObjectId(id, name),
            new QualifiedName(Optional.empty(), Optional.empty(), name), List.of(COLUMN), List.of(),
            DEFINITION);
    }

    static Sql.Request sql(OperationId operation) {
        return new Sql.Request(operation, "select 1", List.of(),
            new Sql.Options(
                ExecutionMode.SINGLE_STATEMENT, TransactionPolicy.AUTOCOMMIT, 2, Duration.ZERO));
    }

    Sql.Request sql() {
        return sql(first.sql().newOperationId());
    }

    Plan plan() {
        return first.change().prepareChange(new CreateTable(table(a, "new"))).plan().orElseThrow();
    }

    static void code(Code expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(CoreException.class, call).code());
    }

    @Test
    void connectionsAndMetadata() {
        assertEquals(List.of(new AdapterInfo(ADAPTER, "Test adapter")), registry.available());
        assertEquals(SCHEMA, registry.schema(ADAPTER));
        code(Code.UNKNOWN_ADAPTER, () -> registry.schema(new AdapterId("missing")));
        assertEquals(SettingKind.PATH, factory.schema().fields().get(0).kind());
        assertTrue(factory.schema().fields().get(1).secret());
        assertEquals(List.of("first", "second"), factory.names);
        assertTrue(first.inspect().checkConnection());
        assertEquals("TestDB", first.inspect().databaseInfo().product());
        assertEquals(1, first.inspect().supportedTypes().size());
        assertEquals(1, first.read().readSchema().objects().size());
        assertSame(DEFINITION, first.read().readObjectDefinition(new ObjectId(a, "existing")));
        assertFalse(first.read().findDependencies(new ObjectId(a, "existing")).complete());
        assertFalse(values().toString().contains("SECRET"));
    }

    @Test
    void valuesBridgeConvertsGenericStrings() {
        ConnectionValues v = values();
        assertEquals(Path.of("/tmp/test.db"), ConnectionValuesReader.path(v, "path"));
        assertTrue(ConnectionValuesReader.flag(v, "wal", false));
        assertEquals(10, ConnectionValuesReader.number(v, "cache", 0));
        assertEquals("SECRET", ConnectionValuesReader.secret(v, "password"));
        code(Code.INVALID_INPUT, () -> ConnectionValuesReader.path(
            new ConnectionValues(Map.of("path", "\0"), Map.of()), "path"));
    }

    @Test
    void unknownAndConnectionFailure() {
        code(Code.UNKNOWN_ADAPTER,
            () -> registry.open(new AdapterId("missing"), "db", values()));
        code(Code.INVALID_INPUT, () -> registry.open(ADAPTER, " ", values()));
        code(Code.INVALID_INPUT, () -> registry.open(ADAPTER, "db", null));
        fake.connectError = new CoreException(Code.CONNECTION, "safe");
        assertSame(fake.connectError,
            assertThrows(CoreException.class, () -> open("db", values())));
        Factory broken = new Factory(fake, ADAPTER, SCHEMA) {
            @Override
            public Usecases open(String displayName, ConnectionValues values, ExportPorts exports) {
                throw new IllegalStateException("SECRET");
            }
        };
        AdapterRegistry brokenRegistry =
            new AdapterRegistry(List.of(broken), exports(writes));
        CoreException normalized = assertThrows(CoreException.class,
            () -> brokenRegistry.open(ADAPTER, "db", values()));
        assertEquals(Code.INTERNAL, normalized.code());
        assertFalse(normalized.getMessage().contains("SECRET"));
        assertEquals(2, fake.live.size());
    }

    @Test
    void validationAggregatesAndStopsAdapter() {
        Table t = new Table(new ObjectId(a, "bad"),
            new QualifiedName(Optional.empty(), Optional.empty(), ""), List.of(COLUMN, COLUMN),
            List.of(new PrimaryKey(Optional.empty(), List.of("missing")),
                new PrimaryKey(Optional.empty(), List.of("id"))),
            DEFINITION);
        Preparation out = first.change().prepareChange(new CreateTable(t));
        assertTrue(out.plan().isEmpty());
        assertTrue(out.problems().size() >= 4);
        assertEquals(0, fake.prepares);
        assertEquals(0, fake.applies);
    }

    @Test
    void exactPreparedPlanAndRepeat() {
        Plan p = plan();
        assertEquals(0, fake.applies);
        assertEquals(ApplyStatus.APPLIED, first.change().applyChange(p.id()).status());
        assertSame(fake.prepared, fake.applied);
        code(Code.ALREADY_APPLIED, () -> first.change().applyChange(p.id()));
    }

    @Test
    void foreignUnknownAndStalePlans() {
        Plan p = plan();
        code(Code.WRONG_SESSION, () -> second.change().applyChange(p.id()));
        code(Code.NOT_FOUND, () -> first.change().applyChange(new ChangePlanId(UUID.randomUUID())));
        fake.tokens.put(a, "v2");
        assertEquals(ApplyStatus.CONFLICT, first.change().applyChange(p.id()).status());
        assertEquals(1, fake.prepares);
        code(Code.ALREADY_APPLIED, () -> first.change().applyChange(p.id()));
    }

    @Test
    void ambiguousNeverRetried() {
        Plan p = plan();
        fake.ambiguous = true;
        code(Code.OUTCOME_UNKNOWN, () -> first.change().applyChange(p.id()));
        code(Code.ALREADY_APPLIED, () -> first.change().applyChange(p.id()));
        assertEquals(1, fake.applies);
    }

    @Test
    void sqlRowsCommandAndOwnership() {
        Sql.Execution out = first.sql().execute(sql());
        ResultId rid = out.result().orElseThrow();
        assertTrue(out.statistics().totalRows().isEmpty());
        code(Code.WRONG_SESSION, () -> second.sql().fetchRows(rid, 2));
        assertTrue(first.sql().fetchRows(rid, 2).complete());
        assertTrue(first.sql().fetchRows(rid, 2).rows().isEmpty());
        assertEquals(1, fake.fetches);
        first.sql().closeResult(rid);
        first.sql().closeResult(rid);
        code(Code.NOT_FOUND, () -> first.sql().fetchRows(rid, 1));
        fake.command = true;
        Sql.Execution command = second.sql().execute(sql(second.sql().newOperationId()));
        assertTrue(command.result().isEmpty());
        assertEquals(3, command.statistics().affectedRows().orElseThrow());
    }

    @Test
    void objectIsolation() {
        code(Code.WRONG_SESSION,
            () -> second.read().readObjectDefinition(new ObjectId(a, "existing")));
        code(
            Code.WRONG_SESSION, () -> second.change().prepareChange(new CreateTable(table(a, "new"))));
    }

    @Test
    void closeIsolatedIdempotent() {
        first.sql().execute(sql());
        second.sql().execute(sql(second.sql().newOperationId()));
        first.close();
        first.close();
        assertEquals(1, fake.disconnects);
        assertEquals(Set.of(b), fake.live);
        assertEquals(Set.of(b), new HashSet<>(fake.cursors.values()));
        assertTrue(second.inspect().checkConnection());
        code(Code.UNKNOWN_SESSION, () -> first.sql().execute(sql(first.sql().newOperationId())));
    }

    @Test
    void cancellationWhileRunning() throws Exception {
        fake.started = new CountDownLatch(1);
        fake.release = new CountDownLatch(1);
        Sql.Request request = sql();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Sql.Execution> running = pool.submit(() -> first.sql().execute(request));
            assertTrue(fake.started.await(5, TimeUnit.SECONDS));
            code(Code.WRONG_SESSION, () -> second.sql().cancel(request.operationId()));
            assertEquals(Sql.CancelResult.REQUESTED, first.sql().cancel(request.operationId()));
            running.get(5, TimeUnit.SECONDS);
            assertEquals(a, fake.cancelled);
            assertEquals(Sql.CancelResult.NOT_ACTIVE, first.sql().cancel(request.operationId()));
        } finally {
            fake.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void closeCancelsRunningWithoutBlockingOtherSession() throws Exception {
        fake.started = new CountDownLatch(1);
        fake.release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = pool.submit(() -> first.sql().execute(sql()));
            assertTrue(fake.started.await(5, TimeUnit.SECONDS));
            assertTrue(second.inspect().checkConnection());
            first.close();
            running.get(5, TimeUnit.SECONDS);
            assertEquals(a, fake.cancelled);
            assertTrue(fake.cursors.isEmpty());
        } finally {
            fake.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousApplyOnce() throws Exception {
        Plan p = plan();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Object> attempt = () -> {
            start.await();
            try {
                return first.change().applyChange(p.id());
            } catch (CoreException e) {
                return e.code();
            }
        };
        try {
            Future<Object> one = pool.submit(attempt), two = pool.submit(attempt);
            start.countDown();
            List<Object> outcomes =
                List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(ApplyResult.class ::isInstance).count());
            assertTrue(outcomes.contains(Code.ALREADY_APPLIED));
            assertEquals(1, fake.applies);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void queryPlanPreservesUnknown() {
        Metrics metrics = new Metrics(
            Optional.empty(), Optional.empty(), OptionalLong.empty(), OptionalDouble.empty());
        PlanNode node =
            new PlanNode("x", NodeType.UNKNOWN, "VENDOR_OP", Optional.empty(), Optional.empty(),
                Optional.empty(), "raw node", metrics, Map.of("extra", "data"), List.of());
        fake.queryPlan =
            new QueryPlan(Mode.ESTIMATED, List.of(node), "raw explain", "vendor-format", Map.of());
        QueryPlan out = first.explain().explain(sql(), Mode.ESTIMATED);
        assertSame(fake.queryPlan, out);
        assertEquals("VENDOR_OP", out.roots().get(0).originalType());
        assertTrue(out.roots().get(0).metrics().cost().isEmpty());
        assertEquals("raw explain", out.rawOutput());
    }

    @Test
    void ddlUnchanged() {
        Ddl ddl = first.ddl().saveDdl(new DdlOptions(true, Set.of(), DependencyPolicy.INCLUDE),
            new Target("output"), new TextOptions(StandardCharsets.UTF_8, false));
        assertEquals(List.of(ddl.sql()), writes);
        assertEquals("-- original\nDDL;\n", writes.get(0));
        assertFalse(ddl.independentlyRestorable());
    }

    @Test
    void diagnosticsPreserved() {
        Diagnostic d = new Diagnostic(Optional.of("vendor"), Optional.of("state"), "safe detail");
        fake.executeError = new CoreException(Code.SQL, "syntax", List.of(), Optional.of(d));
        CoreException out = assertThrows(CoreException.class, () -> first.sql().execute(sql()));
        assertSame(fake.executeError, out);
        assertEquals(d, out.diagnostic().orElseThrow());
    }

    @Test
    void immutableCollectionsAndBinary() {
        byte[] bytes = {1};
        Sql.BinaryValue value = new Sql.BinaryValue(bytes);
        bytes[0] = 2;
        value.value()[0] = 3;
        assertEquals(1, value.value()[0]);
        Plan p = plan();
        assertThrows(UnsupportedOperationException.class, () -> p.steps().clear());
    }

    @Test
    void transferredCursorHasExclusiveOwnership() {
        ResultId rid = first.sql().execute(sql()).result().orElseThrow();
        try (RowSource source = first.sql().remainingRows(rid)) {
            assertEquals(RowScope.REMAINING, source.scope());
            code(Code.CONFLICT, () -> first.sql().fetchRows(rid, 1));
            code(Code.CONFLICT, () -> first.sql().closeResult(rid));
            assertTrue(source.fetch(1).complete());
        }
        assertFalse(fake.cursors.containsKey(rid));
        code(Code.NOT_FOUND, () -> first.sql().fetchRows(rid, 1));
    }

    @Test
    void contextualConstraintValidation() {
        Preparation out = first.change().prepareChange(
            new AddConstraint(
                new ObjectId(a, "existing"), new Unique(Optional.empty(), List.of("missing"))));
        assertTrue(out.plan().isEmpty());
        assertTrue(out.problems().stream().anyMatch(p -> p.code().equals("unknown_column")));
        assertEquals(0, fake.prepares);
    }

    @Test
    void unsupportedCapabilitiesStopExecution() {
        Fake unsupported = new Fake() {
            @Override
            public Capabilities getCapabilities(SessionId id) {
                return new Capabilities(Map.of(), false, false, Set.of(), Set.of());
            }
        };
        Factory otherFactory = new Factory(unsupported, ADAPTER, SCHEMA);
        AdapterRegistry otherRegistry =
            new AdapterRegistry(List.of(otherFactory), exports(new ArrayList<>()));
        try (Usecases other = otherRegistry.open(ADAPTER, "db", values())) {
            SessionId id = other.scope().session().id();
            code(
                Code.UNSUPPORTED, () -> other.change().prepareChange(new CreateTable(table(id, "new"))));
            code(
                Code.UNSUPPORTED,
                () -> other.sql().execute(sql(other.sql().newOperationId())));
            code(Code.UNSUPPORTED,
                () -> other.explain().explain(sql(other.sql().newOperationId()), Mode.ESTIMATED));
            assertEquals(0, unsupported.prepares);
        }
    }

    @Test
    void operationCannotBeReused() {
        Sql.Request r = sql();
        first.sql().execute(r);
        code(Code.CONFLICT, () -> first.sql().execute(r));
        code(Code.WRONG_SESSION, () -> second.sql().execute(r));
    }

    @Test
    void identifierRulesAreAdapterDefined() {
        ChangeValidator sensitive = new ChangeValidator(String::equals),
                         insensitive = new ChangeValidator(String::equalsIgnoreCase);
        Column upper = new Column("ID", COLUMN.type(), true, Optional.of("NULL"), Map.of());
        CreateTable request = new CreateTable(new Table(new ObjectId(a, "t"), table(a, "t").name(),
            List.of(COLUMN, upper), List.of(), DEFINITION));
        assertTrue(sensitive.validate(request).isEmpty());
        assertTrue(
            insensitive.validate(request).stream().anyMatch(p -> p.code().equals("duplicate")));
    }
}
