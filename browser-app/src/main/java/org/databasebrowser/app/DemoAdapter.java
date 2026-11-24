package org.databasebrowser.app;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.ApplyStatus;
import org.databasebrowser.core.domain.Changes.CheckKind;
import org.databasebrowser.core.domain.Changes.DataCheck;
import org.databasebrowser.core.domain.Changes.ExecutionRecipe;
import org.databasebrowser.core.domain.Changes.Plan;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Changes.Rollback;
import org.databasebrowser.core.domain.Changes.Step;
import org.databasebrowser.core.domain.Changes.StepKind;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.ChangeKind;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Connections.Support;
import org.databasebrowser.core.domain.Connections.SupportedType;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
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
import org.databasebrowser.core.domain.Schema.QualifiedName;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Sql;
import org.databasebrowser.core.port.AdapterProvider;
import org.databasebrowser.core.port.ConnectionPort;
import org.databasebrowser.core.port.DatabaseInfoPort;
import org.databasebrowser.core.port.DdlExportPort;
import org.databasebrowser.core.port.QueryPlanPort;
import org.databasebrowser.core.port.SchemaChangePort;
import org.databasebrowser.core.port.SchemaReaderPort;
import org.databasebrowser.core.port.SqlExecutionPort;

final class DemoAdapter implements AdapterProvider, ConnectionPort, DatabaseInfoPort,
                                  SchemaReaderPort, SchemaChangePort, SqlExecutionPort,
                                  QueryPlanPort, DdlExportPort {
    static final AdapterId ID = new AdapterId("demo");
    private static final Definition DEFINITION =
        new Definition(Optional.of("original SQL"), Map.of(), true);
    private static final Dependencies DEPS =
        new Dependencies(List.of(), false, List.of("engine limitation"));
    private static final Column COLUMN =
        new Column("id", new DataType("integer", List.of(), Optional.empty(), true), false,
            Optional.empty(), Map.of());

    private final Set<SessionId> live = ConcurrentHashMap.newKeySet();
    private final Map<SessionId, String> tokens = new ConcurrentHashMap<>();

    @Override
    public AdapterId id() {
        return ID;
    }

    @Override
    public IdentifierRules identifierRules() {
        return String::equals;
    }

    @Override
    public AdapterPorts ports() {
        return new AdapterPorts(this, this, this, this, this, this, this);
    }

    @Override
    public Opened connect(Settings s) {
        SessionId id = new SessionId(UUID.randomUUID());
        live.add(id);
        tokens.put(id, "v1");
        return new Opened(id, getDatabaseInfo(id));
    }

    @Override
    public boolean checkConnection(SessionId id) {
        return live.contains(id);
    }

    @Override
    public void disconnect(SessionId id) {
        live.remove(id);
    }

    @Override
    public DatabaseInfo getDatabaseInfo(SessionId id) {
        return new DatabaseInfo(ID, "DemoDB", "1", Map.of());
    }

    @Override
    public Capabilities getCapabilities(SessionId id) {
        Map<ChangeKind, Support> m = new EnumMap<>(ChangeKind.class);
        for (ChangeKind c : ChangeKind.values())
            m.put(c, Support.MIGRATION);
        return new Capabilities(m, true, true, Set.of(ExecutionMode.SINGLE_STATEMENT),
            Set.of(TransactionPolicy.AUTOCOMMIT, TransactionPolicy.ATOMIC));
    }

    @Override
    public List<SupportedType> getSupportedTypes(SessionId id) {
        return List.of(new SupportedType("integer", List.of(), Optional.empty()));
    }

    @Override
    public Snapshot readSchema(SessionId id) {
        return new Snapshot(id, ID, tokens.get(id), List.of(), List.of(table(id, "existing")), DEPS);
    }

    @Override
    public Definition readObjectDefinition(SessionId id, ObjectId o) {
        return DEFINITION;
    }

    @Override
    public Dependencies findDependencies(SessionId id, ObjectId o) {
        return DEPS;
    }

    record Recipe(String token) implements ExecutionRecipe {}

    @Override
    public Prepared prepareChange(SessionId id, Request r) {
        Plan p = new Plan(new ChangePlanId(UUID.randomUUID()), id, r, tokens.get(id),
            List.of(new Step(StepKind.COPY_DATA, "recreate", List.of("preview"))),
            List.of(new DataCheck(CheckKind.NOT_NULL, "check", Optional.empty())), Set.of(), DEPS,
            List.of(), List.of("possible loss"), new Rollback(true, true, "engine contract"), true);
        return new Prepared(p, new Recipe(p.schemaToken()));
    }

    @Override
    public ApplyResult applyChange(SessionId id, Prepared p) {
        return new ApplyResult(ApplyStatus.APPLIED, List.of(), Optional.empty());
    }

    @Override
    public Sql.Execution execute(SessionId id, Sql.Request r) {
        return new Sql.Execution(Sql.Status.SUCCEEDED, Optional.of(new ResultId(UUID.randomUUID())),
            List.of(new Sql.ResultColumn("id", Optional.of("integer"), Optional.empty())),
            new Sql.Batch(List.of(), false),
            new Sql.Statistics(Duration.ofMillis(1), 0, OptionalLong.empty(), OptionalLong.empty()),
            List.of());
    }

    @Override
    public Sql.Batch fetchRows(SessionId id, ResultId rid, int size) {
        return new Sql.Batch(
            List.of(new Sql.Row(List.of(new Sql.NumberValue(BigDecimal.ONE)))), true);
    }

    @Override
    public Sql.CancelResult cancel(SessionId id, OperationId oid) {
        return Sql.CancelResult.REQUESTED;
    }

    @Override
    public void closeResult(SessionId id, ResultId rid) {}

    @Override
    public QueryPlan explain(SessionId id, Sql.Request r, Mode mode) {
        PlanNode node = new PlanNode("scan-1", NodeType.SCAN, "SCAN",
            Optional.of(new QualifiedName(Optional.empty(), Optional.empty(), "existing")),
            Optional.empty(), Optional.empty(), "full scan",
            new Metrics(Optional.of(BigDecimal.ONE), Optional.empty(), OptionalLong.empty(),
                OptionalDouble.empty()),
            Map.of(), List.of());
        return new QueryPlan(mode, List.of(node), "demo plan", "demo-format", Map.of());
    }

    @Override
    public Ddl generateDdl(Snapshot snapshot, DdlOptions options) {
        return new Ddl("-- demo DDL\nCREATE TABLE existing (id INTEGER);\n", List.of(), true);
    }

    private static Table table(SessionId id, String name) {
        return new Table(new ObjectId(id, name),
            new QualifiedName(Optional.empty(), Optional.empty(), name), List.of(COLUMN), List.of(),
            DEFINITION);
    }
}
