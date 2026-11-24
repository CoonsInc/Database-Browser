package org.databasebrowser.core.application;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.Supplier;
import org.databasebrowser.core.domain.*;
import org.databasebrowser.core.domain.Changes.*;
import org.databasebrowser.core.domain.Connections.*;
import org.databasebrowser.core.domain.Errors.*;
import org.databasebrowser.core.domain.Exports.*;
import org.databasebrowser.core.domain.Ids.*;
import org.databasebrowser.core.domain.QueryPlans.*;
import org.databasebrowser.core.domain.Schema.*;
import org.databasebrowser.core.port.*;
import org.databasebrowser.core.port.AdapterProvider.AdapterPorts;

/** Blocking API: call on UI worker threads. No application-wide lock spans adapter calls. */
public final class BrowserService implements AutoCloseable {
  private final Map<AdapterId, AdapterProvider> providers;
  private final TextFilePort textFiles;
  private final ConcurrentMap<SessionId, State> sessions = new ConcurrentHashMap<>();
  private final Set<SessionId> usedSessions = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<ChangePlanId, StoredPlan> plans = new ConcurrentHashMap<>();
  private final ConcurrentMap<OperationId, Operation> operations = new ConcurrentHashMap<>();
  private final ConcurrentMap<ResultId, ResultState> results = new ConcurrentHashMap<>();
  private final Object lifecycle = new Object();
  private boolean shuttingDown;
  private static final class State {
    final Session session;
    final AdapterPorts ports;
    final ChangeValidator validator;
    final ReentrantLock work = new ReentrantLock(true);
    volatile boolean closing;
    boolean closed;
    State(Session s, AdapterPorts p, IdentifierRules rules) {
      session = s;
      ports = p;
      validator = new ChangeValidator(rules);
    }
  }
  private static final class StoredPlan {
    final Prepared prepared;
    final AtomicBoolean attempted = new AtomicBoolean();
    StoredPlan(Prepared p) {
      prepared = p;
    }
  }
  private static final class Operation {
    final SessionId owner;
    volatile boolean active = true;
    Operation(SessionId owner) {
      this.owner = owner;
    }
  }
  private static final class ResultState {
    final SessionId owner;
    final AtomicBoolean closed = new AtomicBoolean();
    volatile boolean complete;
    boolean claimed;
    final List<Sql.ResultColumn> columns;
    ResultState(SessionId owner, boolean complete, List<Sql.ResultColumn> columns) {
      this.owner = owner;
      this.complete = complete;
      this.columns = List.copyOf(columns);
    }
  }
  public BrowserService(Collection<AdapterProvider> adapters, TextFilePort textFiles) {
    this.textFiles = Objects.requireNonNull(textFiles);
    Map<AdapterId, AdapterProvider> m = new HashMap<>();
    for (AdapterProvider a : adapters)
      if (m.putIfAbsent(a.id(), a) != null)
        throw error(Code.INVALID_INPUT, "Duplicate adapter ID");
    providers = Map.copyOf(m);
  }
  public Session connect(Settings settings) {
    if (settings == null || settings.displayName() == null || settings.displayName().isBlank())
      throw error(Code.INVALID_INPUT, "Connection display name is required");
    AdapterProvider provider = providers.get(settings.adapter());
    if (provider == null)
      throw error(Code.UNKNOWN_ADAPTER, "Unknown adapter: " + settings.adapter());
    synchronized (lifecycle) {
      if (shuttingDown)
        throw error(Code.CONFLICT, "Core is shutting down");
    }
    AdapterPorts p = Objects.requireNonNull(provider.ports());
    Opened opened = safe(() -> p.connection().connect(settings));
    if (opened == null || opened.id() == null || opened.info() == null)
      throw error(Code.INTERNAL, "Adapter returned invalid connection metadata");
    Session session = new Session(opened.id(), provider.id(), settings.displayName(), opened.info());
    synchronized (lifecycle) {
      // Do not disconnect a duplicate ID: that could close the original session in a broken provider.
      if (!usedSessions.add(session.id()))
        throw error(Code.INTERNAL, "Adapter reused a session ID");
      if (!shuttingDown) {
        sessions.put(session.id(), new State(session, p, provider.identifierRules()));
        return session;
      }
    }
    p.connection().disconnect(session.id());
    throw error(Code.CONFLICT, "Core is shutting down");
  }
  public List<Session> sessions() {
    return sessions.values()
        .stream()
        .filter(s -> !s.closing)
        .map(s -> s.session)
        .sorted(Comparator.comparing(s -> s.id().value()))
        .toList();
  }
  private State state(SessionId id) {
    State s = sessions.get(id);
    if (s == null)
      throw error(Code.UNKNOWN_SESSION, "Unknown session");
    return s;
  }
  private <T> T work(SessionId id, java.util.function.Function<State, T> action) {
    State s = state(id);
    s.work.lock();
    try {
      if (s.closing)
        throw error(Code.UNKNOWN_SESSION, "Session is closing or closed");
      return safe(() -> action.apply(s));
    } finally {
      s.work.unlock();
    }
  }
  public boolean checkConnection(SessionId id) {
    return work(id, s -> s.ports.connection().checkConnection(id));
  }
  public DatabaseInfo databaseInfo(SessionId id) {
    return work(id, s -> s.ports.info().getDatabaseInfo(id));
  }
  public Capabilities capabilities(SessionId id) {
    return work(id, s -> s.ports.info().getCapabilities(id));
  }
  public List<SupportedType> supportedTypes(SessionId id) {
    return work(id, s -> List.copyOf(s.ports.info().getSupportedTypes(id)));
  }
  private void objectOwner(SessionId session, ObjectId object) {
    if (object == null)
      throw error(Code.INVALID_INPUT, "Object is required");
    if (!object.session().equals(session))
      throw error(Code.WRONG_SESSION, "Object belongs to another session");
  }
  private Snapshot snapshot(State s) {
    Snapshot snap = s.ports.schema().readSchema(s.session.id());
    if (!snap.session().equals(s.session.id()) || !snap.dialect().equals(s.session.info().dialect()))
      throw error(Code.INTERNAL, "Invalid snapshot provenance");
    for (SchemaObject o : snap.objects()) objectOwner(s.session.id(), o.id());
    return snap;
  }
  public Snapshot readSchema(SessionId id) {
    return work(id, this::snapshot);
  }
  public Definition readObjectDefinition(SessionId id, ObjectId object) {
    objectOwner(id, object);
    return work(id, s -> s.ports.schema().readObjectDefinition(id, object));
  }
  public Dependencies findDependencies(SessionId id, ObjectId object) {
    objectOwner(id, object);
    return work(id, s -> s.ports.schema().findDependencies(id, object));
  }
  public Preparation prepareChange(SessionId id, Request request) {
    return work(id, s -> {
      List<Problem> issues = s.validator.validate(request);
      if (!issues.isEmpty())
        return new Preparation(Optional.empty(), issues);
      for (ObjectId object : requestIds(request)) objectOwner(id, object);
      if (s.ports.info().getCapabilities(id).support(request.kind()) == Support.UNSUPPORTED)
        throw error(Code.UNSUPPORTED, "Schema operation is unsupported");
      Snapshot schema = snapshot(s);
      List<Problem> contextual = contextualValidation(s, request, schema);
      if (!contextual.isEmpty())
        return new Preparation(Optional.empty(), contextual);
      Prepared prepared = s.ports.changes().prepareChange(id, request);
      Plan plan = prepared.preview();
      if (!plan.session().equals(id) || !plan.request().equals(request)
          || !plan.schemaToken().equals(schema.stateToken()))
        throw error(Code.CONFLICT, "Prepared plan differs from requested context; refresh preview");
      if (plans.putIfAbsent(plan.id(), new StoredPlan(prepared)) != null)
        throw error(Code.INTERNAL, "Adapter reused plan ID");
      return new Preparation(Optional.of(plan), plan.problems());
    });
  }
  private List<ObjectId> requestIds(Request r) {
    if (r instanceof CreateTable x)
      return List.of(x.table().id());
    if (r instanceof DropTable x)
      return List.of(x.table());
    if (r instanceof AddColumn x)
      return List.of(x.table());
    if (r instanceof AlterColumn x)
      return List.of(x.table());
    if (r instanceof DropColumn x)
      return List.of(x.table());
    if (r instanceof AddConstraint x)
      return List.of(x.table());
    if (r instanceof DropConstraint x)
      return List.of(x.table());
    if (r instanceof CreateIndex x)
      return List.of(x.index().id(), x.index().table());
    if (r instanceof DropIndex x)
      return List.of(x.index());
    if (r instanceof CreateView x)
      return List.of(x.view().id());
    if (r instanceof AlterView x)
      return List.of(x.original(), x.desired().id());
    if (r instanceof DropView x)
      return List.of(x.view());
    throw error(Code.INTERNAL, "Unknown request");
  }
  private List<Problem> contextualValidation(State s, Request r, Snapshot snap) {
    ObjectId target = null;
    if (r instanceof AddColumn x)
      target = x.table();
    if (r instanceof AlterColumn x)
      target = x.table();
    if (r instanceof DropColumn x)
      target = x.table();
    if (r instanceof AddConstraint x)
      target = x.table();
    if (r instanceof DropConstraint x)
      target = x.table();
    if (r instanceof DropTable x)
      target = x.table();
    if (r instanceof DropIndex x)
      target = x.index();
    if (r instanceof DropView x)
      target = x.view();
    if (r instanceof AlterView x)
      target = x.original();
    if (r instanceof CreateIndex x)
      target = x.index().table();
    if (target == null)
      return List.of();
    ObjectId sought = target;
    SchemaObject obj = snap.objects()
                           .stream()
                           .filter(o -> o.id().equals(sought))
                           .findFirst()
                           .orElseThrow(() -> error(Code.NOT_FOUND, "Schema object not found"));
    if (!obj.definition().editable())
      throw error(Code.UNSUPPORTED, "Object contains unsupported constructs");
    boolean tableRequest = r instanceof AddColumn || r instanceof AlterColumn || r instanceof DropColumn
        || r instanceof AddConstraint || r instanceof DropConstraint || r instanceof DropTable
        || r instanceof CreateIndex;
    if (tableRequest && !(obj instanceof Table) || r instanceof DropIndex && !(obj instanceof Index)
        || (r instanceof DropView || r instanceof AlterView) && !(obj instanceof View))
      throw error(Code.INVALID_INPUT, "Wrong schema object kind");
    if (!(obj instanceof Table t))
      return List.of();
    List<Column> cols = new ArrayList<>(t.columns());
    List<Constraint> constraints = new ArrayList<>(t.constraints());
    if (r instanceof AddColumn x)
      cols.add(x.column());
    if (r instanceof AlterColumn x) {
      int pos = -1;
      for (int i = 0; i < cols.size(); i++)
        if (validatorRules(s).equivalent(cols.get(i).name(), x.originalName()))
          pos = i;
      if (pos < 0)
        throw error(Code.NOT_FOUND, "Column not found");
      cols.set(pos, x.desired());
      for (int j = 0; j < constraints.size(); j++)
        constraints.set(
            j, renameLocal(constraints.get(j), x.originalName(), x.desired().name(), validatorRules(s)));
    }
    if (r instanceof DropColumn x) {
      boolean removed = cols.removeIf(c -> validatorRules(s).equivalent(c.name(), x.column()));
      if (!removed)
        throw error(Code.NOT_FOUND, "Column not found");
    }
    if (r instanceof AddConstraint x)
      constraints.add(x.constraint());
    if (r instanceof DropConstraint x) {
      boolean removed = constraints.removeIf(
          c -> c.name().filter(n -> validatorRules(s).equivalent(n, x.constraintName())).isPresent());
      if (!removed)
        throw error(Code.NOT_FOUND, "Constraint not found");
    }
    return s.validator.validate(
        new CreateTable(new Table(t.id(), t.name(), cols, constraints, t.definition())));
  }
  private Constraint renameLocal(Constraint c, String oldName, String newName, IdentifierRules rules) {
    java.util.function.Function<List<String>, List<String>> rename =
        cols -> cols.stream().map(n -> rules.equivalent(n, oldName) ? newName : n).toList();
    if (c instanceof PrimaryKey k)
      return new PrimaryKey(k.name(), rename.apply(k.columns()));
    if (c instanceof Unique k)
      return new Unique(k.name(), rename.apply(k.columns()));
    if (c instanceof ForeignKey k)
      return new ForeignKey(k.name(), rename.apply(k.columns()), k.referencedTable(), k.referencedColumns(),
          k.onDelete(), k.onUpdate());
    return c; // CHECK expressions remain opaque; adapter validates them.
  }
  private IdentifierRules validatorRules(State s) {
    return providers.get(s.session.adapter()).identifierRules();
  }
  public ApplyResult applyChange(SessionId id, ChangePlanId planId) {
    return work(id, s -> {
      StoredPlan stored = plans.get(planId);
      if (stored == null)
        throw error(Code.NOT_FOUND, "Unknown prepared plan");
      Plan p = stored.prepared.preview();
      if (!p.session().equals(id))
        throw error(Code.WRONG_SESSION, "Plan belongs to another session");
      if (!p.applicable() || p.problems().stream().anyMatch(x -> x.severity() == Severity.ERROR))
        throw error(Code.CONSTRAINT, "Plan cannot be applied");
      if (!stored.attempted.compareAndSet(false, true))
        throw error(Code.ALREADY_APPLIED, "Plan has already been attempted; prepare a new preview");
      // Consume before adapter call: even a transport exception cannot authorize retry.
      return s.ports.changes().applyChange(id, stored.prepared);
    });
  }
  public OperationId newOperationId() {
    return new OperationId(UUID.randomUUID());
  }
  private <T> T operation(State s, Sql.Request request, Supplier<T> action) {
    validateSql(s, request);
    Operation op = new Operation(s.session.id());
    Operation prior;
    synchronized (s) {
      if (s.closing)
        throw error(Code.UNKNOWN_SESSION, "Session is closing");
      prior = operations.putIfAbsent(request.operationId(), op);
    }
    if (prior != null)
      throw error(prior.owner.equals(s.session.id()) ? Code.CONFLICT : Code.WRONG_SESSION,
          "Operation ID already used");
    try {
      return action.get();
    } finally {
      op.active = false;
    }
  }
  private void validateSql(State s, Sql.Request r) {
    if (r == null || r.operationId() == null || r.sql() == null || r.sql().isBlank() || r.options() == null)
      throw error(Code.INVALID_INPUT, "SQL, operation ID and options are required");
    Sql.Options o = r.options();
    if (o.mode() == null || o.transaction() == null || o.initialBatchSize() < 1 || o.timeout() == null
        || o.timeout().isNegative())
      throw error(Code.INVALID_INPUT, "Invalid SQL options");
    Set<Integer> positions = new HashSet<>();
    for (Sql.Parameter p : r.parameters())
      if (p.position() < 1 || !positions.add(p.position()))
        throw error(Code.INVALID_INPUT, "Parameter positions must be positive and unique");
    Capabilities c = s.ports.info().getCapabilities(s.session.id());
    if (!c.executionModes().contains(o.mode()) || !c.transactions().contains(o.transaction()))
      throw error(Code.UNSUPPORTED, "Unsupported execution or transaction mode");
  }
  public Sql.Execution execute(SessionId id, Sql.Request request) {
    return work(id, s -> operation(s, request, () -> {
      Sql.Execution out = s.ports.sql().execute(id, request);
      out.result().ifPresent(r -> {
        if (results.putIfAbsent(r, new ResultState(id, out.firstBatch().complete(), out.columns())) != null)
          throw error(Code.INTERNAL, "Adapter reused result ID");
      });
      return out;
    }));
  }
  private ResultState result(SessionId id, ResultId rid) {
    ResultState r = results.get(rid);
    if (r == null)
      throw error(Code.NOT_FOUND, "Unknown result");
    if (!r.owner.equals(id))
      throw error(Code.WRONG_SESSION, "Result belongs to another session");
    return r;
  }
  public Sql.Batch fetchRows(SessionId id, ResultId rid, int size) {
    return fetch(id, rid, size, false);
  }
  private Sql.Batch fetch(SessionId id, ResultId rid, int size, boolean owned) {
    if (size < 1)
      throw error(Code.INVALID_INPUT, "Batch size must be positive");
    return work(id, s -> {
      ResultState r = result(id, rid);
      if (r.closed.get())
        throw error(Code.NOT_FOUND, "Result is closed");
      if (r.claimed && !owned)
        throw error(Code.CONFLICT, "Result is owned by CSV source");
      if (r.complete)
        return new Sql.Batch(List.of(), true);
      Sql.Batch batch = s.ports.sql().fetchRows(id, rid, size);
      r.complete = batch.complete();
      return batch;
    });
  }
  public void closeResult(SessionId id, ResultId rid) {
    closeResult(id, rid, false);
  }
  private void closeResult(SessionId id, ResultId rid, boolean owned) {
    work(id, s -> {
      ResultState r = result(id, rid);
      if (r.claimed && !owned)
        throw error(Code.CONFLICT, "Result is owned by CSV source");
      if (!r.closed.get()) {
        s.ports.sql().closeResult(id, rid);
        r.closed.set(true);
      }
      return null;
    });
  }
  /**
   * Transfers the remaining cursor to the caller. Initial/UI-read batches are deliberately excluded.
   * Caller must close the source or pass it to CsvService, which assumes ownership.
   */
  public RowSource remainingRows(SessionId id, ResultId rid) {
    return work(id, s -> {
      ResultState r = result(id, rid);
      if (r.closed.get() || r.claimed)
        throw error(Code.CONFLICT, "Result unavailable for transfer");
      r.claimed = true;
      return new RowSource() {
        boolean closed;
        public List<Sql.ResultColumn> columns() {
          return r.columns;
        }
        public RowScope scope() {
          return RowScope.REMAINING;
        }
        public synchronized Sql.Batch fetch(int size) {
          if (closed)
            throw error(Code.NOT_FOUND, "Source closed");
          return BrowserService.this.fetch(id, rid, size, true);
        }
        public synchronized void close() {
          if (closed)
            return;
          closed = true;
          State owner = state(id);
          owner.work.lock();
          try {
            if (!owner.closing)
              BrowserService.this.closeResult(id, rid, true);
          } finally {
            owner.work.unlock();
          }
        }
      };
    });
  }
  private static <T> T safe(Supplier<T> call) {
    try {
      return call.get();
    } catch (CoreException e) {
      throw e;
    } catch (RuntimeException e) {
      throw error(Code.INTERNAL, "Adapter or contract failure; diagnostics withheld");
    }
  }
  /** Cancellation bypasses the session work lock; adapter must support concurrent cancellation. */
  public Sql.CancelResult cancel(SessionId id, OperationId oid) {
    State s = state(id);
    Operation op = operations.get(oid);
    if (op == null)
      return Sql.CancelResult.NOT_ACTIVE;
    if (!op.owner.equals(id))
      throw error(Code.WRONG_SESSION, "Operation belongs to another session");
    if (!op.active)
      return Sql.CancelResult.NOT_ACTIVE;
    return safe(() -> s.ports.sql().cancel(id, oid));
  }
  public QueryPlan explain(SessionId id, Sql.Request request, Mode mode) {
    return work(id, s -> {
      Capabilities c = s.ports.info().getCapabilities(id);
      if (mode == null)
        throw error(Code.INVALID_INPUT, "Explain mode is required");
      if (!c.queryPlan() || mode == Mode.ANALYZE && !c.analyze())
        throw error(Code.UNSUPPORTED, "Query plan mode unsupported");
      return operation(s, request, () -> s.ports.plans().explain(id, request, mode));
    });
  }
  public Ddl previewDdl(SessionId id, DdlOptions options) {
    return work(id, s -> {
      if (options == null || options.dependencies() == null
          || options.wholeSchema() && !options.selected().isEmpty()
          || !options.wholeSchema() && options.selected().isEmpty())
        throw error(Code.INVALID_INPUT, "Select whole schema or a nonempty object set");
      Snapshot snap = snapshot(s);
      for (ObjectId oid : options.selected()) {
        objectOwner(id, oid);
        if (snap.objects().stream().noneMatch(o -> o.id().equals(oid)))
          throw error(Code.NOT_FOUND, "Selected export object not found");
      }
      return s.ports.ddl().generateDdl(snap, options);
    });
  }
  public Ddl saveDdl(SessionId id, DdlOptions options, Target target, TextOptions textOptions) {
    Ddl ddl = previewDdl(id, options);
    saveDdlPreview(ddl, target, textOptions);
    return ddl;
  }
  /** Writes exactly the previously reviewed export, without reading the database again. */
  public void saveDdlPreview(Ddl ddl, Target target, TextOptions options) {
    if (ddl == null || target == null || options == null || options.charset() == null)
      throw error(Code.INVALID_INPUT, "DDL, target and text options are required");
    safe(() -> {
      textFiles.writeText(target, ddl.sql(), options);
      return true;
    });
  }
  public void disconnect(SessionId id) {
    State s = state(id);
    synchronized (s) {
      s.closing = true;
    }
    List<RuntimeException> failures = new ArrayList<>();
    operations.forEach((oid, op) -> {
      if (op.owner.equals(id) && op.active)
        try {
          safe(() -> s.ports.sql().cancel(id, oid));
        } catch (RuntimeException e) {
          synchronized (failures) {
            failures.add(e);
          }
        }
    });
    s.work.lock();
    try {
      if (s.closed)
        return;
      safe(() -> {
        s.ports.connection().disconnect(id);
        return true;
      });
      s.closed = true;
      results.values().stream().filter(r -> r.owner.equals(id)).forEach(r -> r.closed.set(true));
    } finally {
      s.work.unlock();
    }
    if (!failures.isEmpty()) {
      RuntimeException e = failures.get(0);
      failures.stream().skip(1).forEach(e::addSuppressed);
      throw e;
    }
  }
  @Override
  public void close() {
    synchronized (lifecycle) {
      shuttingDown = true;
    }
    RuntimeException first = null;
    for (SessionId id : sessions.keySet()) try {
        disconnect(id);
      } catch (RuntimeException e) {
        if (first == null)
          first = e;
        else
          first.addSuppressed(e);
      }
    if (first != null)
      throw first;
  }
}
