package org.databasebrowser.core.application.schema;

import static org.databasebrowser.core.application.schema.SchemaService.checkOwner;
import static org.databasebrowser.core.application.schema.SchemaService.snapshot;
import static org.databasebrowser.core.domain.Errors.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.databasebrowser.core.application.ChangeValidator;
import org.databasebrowser.core.application.session.SessionRegistry.SessionState;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.domain.Changes.AddColumn;
import org.databasebrowser.core.domain.Changes.AddConstraint;
import org.databasebrowser.core.domain.Changes.AlterColumn;
import org.databasebrowser.core.domain.Changes.AlterView;
import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.CreateIndex;
import org.databasebrowser.core.domain.Changes.CreateTable;
import org.databasebrowser.core.domain.Changes.CreateView;
import org.databasebrowser.core.domain.Changes.DropColumn;
import org.databasebrowser.core.domain.Changes.DropConstraint;
import org.databasebrowser.core.domain.Changes.DropIndex;
import org.databasebrowser.core.domain.Changes.DropTable;
import org.databasebrowser.core.domain.Changes.DropView;
import org.databasebrowser.core.domain.Changes.Plan;
import org.databasebrowser.core.domain.Changes.Preparation;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Connections.Support;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.Problem;
import org.databasebrowser.core.domain.Errors.Severity;
import org.databasebrowser.core.domain.Ids.ChangePlanId;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Column;
import org.databasebrowser.core.domain.Schema.Constraint;
import org.databasebrowser.core.domain.Schema.ForeignKey;
import org.databasebrowser.core.domain.Schema.Index;
import org.databasebrowser.core.domain.Schema.PrimaryKey;
import org.databasebrowser.core.domain.Schema.SchemaObject;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Schema.Unique;
import org.databasebrowser.core.domain.Schema.View;

/** Готовит и применяет изменения схемы по подтверждённому плану. */
final class SchemaChanges {
    private final SessionRegistry sessions;
    private final Map<ChangePlanId, StoredPlan> plans = new ConcurrentHashMap<>();

    /** Выполняет изменения под блокировкой общей сессии. */
    SchemaChanges(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    /** Создаёт предпросмотр изменения без записи в БД. */
    Preparation prepareChange(SessionId id, Request request) {
        return sessions.work(id, state -> prepareChange(state, request));
    }

    /** Проверяет черновик и сохраняет выданный адаптером рецепт. */
    private Preparation prepareChange(SessionState state, Request request) {
        var validator = new ChangeValidator(state.identifierRules());
        List<Problem> issues = validator.validate(request);
        if (!issues.isEmpty()) {
            return new Preparation(Optional.empty(), issues);
        }

        SessionId session = state.session().id();
        ChangeTarget target = changeTarget(request);
        target.checkOwners(session);
        if (state.ports().info().getCapabilities(session).support(request.kind()) == Support.UNSUPPORTED) {
            throw error(Code.UNSUPPORTED, "Schema operation is unsupported");
        }

        Snapshot schema = snapshot(state);
        List<Problem> contextual = validateChangeContext(request, target, schema,
            validator, state.identifierRules());
        if (!contextual.isEmpty()) {
            return new Preparation(Optional.empty(), contextual);
        }

        Prepared prepared = state.ports().changes().prepareChange(session, request);
        Plan plan = prepared.preview();
        if (!plan.session().equals(session) || !plan.request().equals(request)
            || !plan.schemaToken().equals(schema.stateToken())) {
            throw error(Code.CONFLICT, "Prepared plan differs from requested context; refresh preview");
        }

        if (plans.putIfAbsent(plan.id(), new StoredPlan(session, prepared)) != null) {
            throw error(Code.INTERNAL, "Adapter reused plan ID");
        }
        return new Preparation(Optional.of(plan), plan.problems());
    }

    /** Применяет подтверждённый план указанной сессии. */
    ApplyResult applyChange(SessionId id, ChangePlanId planId) {
        return sessions.work(id, state -> applyChange(state, planId));
    }

    /** Расходует план и передаёт адаптеру сохранённый рецепт. */
    private ApplyResult applyChange(SessionState state, ChangePlanId id) {
        StoredPlan stored = id == null ? null : plans.get(id);
        if (stored == null) {
            throw error(Code.NOT_FOUND, "Unknown prepared plan");
        }

        SessionId session = state.session().id();
        if (!stored.owner.equals(session)) {
            throw error(Code.WRONG_SESSION, "Plan belongs to another session");
        }

        Prepared prepared = stored.prepared;
        if (prepared == null) {
            throw error(Code.ALREADY_APPLIED, "Plan has already been attempted; prepare a new preview");
        }
        Plan plan = prepared.preview();
        if (!plan.applicable() || plan.problems().stream().anyMatch(p -> p.severity() == Severity.ERROR)) {
            throw error(Code.CONSTRAINT, "Plan cannot be applied");
        }

        // Расходуем план до вызова адаптера, чтобы исключить повтор при сбое связи.
        stored.prepared = null;
        return state.ports().changes().applyChange(session, prepared);
    }

    /** Определяет объект схемы и идентификатор черновика. */
    private static ChangeTarget changeTarget(Request request) {
        if (request instanceof CreateTable x) {
            return new ChangeTarget(null, x.table().id());
        }
        if (request instanceof DropTable x) {
            return new ChangeTarget(x.table(), null);
        }
        if (request instanceof AddColumn x) {
            return new ChangeTarget(x.table(), null);
        }
        if (request instanceof AlterColumn x) {
            return new ChangeTarget(x.table(), null);
        }
        if (request instanceof DropColumn x) {
            return new ChangeTarget(x.table(), null);
        }

        if (request instanceof AddConstraint x) {
            return new ChangeTarget(x.table(), null);
        }
        if (request instanceof DropConstraint x) {
            return new ChangeTarget(x.table(), null);
        }

        if (request instanceof CreateIndex x) {
            return new ChangeTarget(x.index().table(), x.index().id());
        }
        if (request instanceof DropIndex x) {
            return new ChangeTarget(x.index(), null);
        }

        if (request instanceof CreateView x) {
            return new ChangeTarget(null, x.view().id());
        }
        if (request instanceof AlterView x) {
            return new ChangeTarget(x.original(), x.desired().id());
        }
        if (request instanceof DropView x) {
            return new ChangeTarget(x.view(), null);
        }

        throw error(Code.INTERNAL, "Unknown request");
    }

    /** Проверяет изменение на снимке существующей схемы. */
    private static List<Problem> validateChangeContext(Request request, ChangeTarget target, Snapshot snapshot,
        ChangeValidator validator, IdentifierRules rules) {
        if (target.existing() == null) {
            return List.of();
        }

        SchemaObject object = snapshot.objects().stream()
            .filter(candidate -> candidate.id().equals(target.existing()))
            .findFirst()
            .orElseThrow(() -> error(Code.NOT_FOUND, "Schema object not found"));

        if (!(request instanceof CreateIndex) && !object.definition().editable()) {
            throw error(Code.UNSUPPORTED, "Object contains unsupported constructs");
        }

        boolean tableRequest = request instanceof AddColumn || request instanceof AlterColumn
            || request instanceof DropColumn || request instanceof AddConstraint
            || request instanceof DropConstraint
            || request instanceof DropTable || request instanceof CreateIndex;
        if (tableRequest && !(object instanceof Table)
            || request instanceof DropIndex && !(object instanceof Index)
            || (request instanceof DropView || request instanceof AlterView) && !(object instanceof View)) {
            throw error(Code.INVALID_INPUT, "Wrong schema object kind");
        }
        if (!(object instanceof Table t) || request instanceof CreateIndex) {
            return List.of();
        }

        List<Column> cols = new ArrayList<>(t.columns());
        List<Constraint> constraints = new ArrayList<>(t.constraints());

        if (request instanceof AddColumn x) {
            cols.add(x.column());
        }
        if (request instanceof AlterColumn x) {
            int pos = -1;
            for (int i = 0; i < cols.size(); i++) {
                if (rules.equivalent(cols.get(i).name(), x.originalName())) {
                    pos = i;
                }
            }
            if (pos < 0) {
                throw error(Code.NOT_FOUND, "Column not found");
            }
            cols.set(pos, x.desired());
            for (int j = 0; j < constraints.size(); j++) {
                constraints.set(j,
                    renameLocal(constraints.get(j), x.originalName(), x.desired().name(),
                        rules));
            }
        }
        if (request instanceof DropColumn x) {
            boolean removed =
                cols.removeIf(c -> rules.equivalent(c.name(), x.column()));
            if (!removed) {
                throw error(Code.NOT_FOUND, "Column not found");
            }
        }
        if (request instanceof AddConstraint x) {
            constraints.add(x.constraint());
        }
        if (request instanceof DropConstraint x) {
            boolean removed = constraints.removeIf(c
                -> c.name()
                    .filter(n -> rules.equivalent(n, x.constraintName()))
                    .isPresent());
            if (!removed) {
                throw error(Code.NOT_FOUND, "Constraint not found");
            }
        }

        return validator.validate(
            new CreateTable(new Table(t.id(), t.name(), cols, constraints, t.definition())));
    }

    /** Обновляет ссылки ограничения на переименованную колонку. */
    private static Constraint renameLocal(
        Constraint c, String oldName, String newName, IdentifierRules rules) {
        Function<List<String>, List<String>> rename =
            cols -> cols.stream().map(n -> rules.equivalent(n, oldName) ? newName : n).toList();
        if (c instanceof PrimaryKey k) {
            return new PrimaryKey(k.name(), rename.apply(k.columns()));
        }
        if (c instanceof Unique k) {
            return new Unique(k.name(), rename.apply(k.columns()));
        }
        if (c instanceof ForeignKey k) {
            return new ForeignKey(k.name(), rename.apply(k.columns()), k.referencedTable(),
                k.referencedColumns(), k.onDelete(), k.onUpdate());
        }

        return c; // Выражения CHECK проверяет адаптер.
    }

    /** Освобождает рецепты планов закрытой сессии. */
    void releaseSession(SessionId session) {
        for (StoredPlan stored : plans.values()) {
            if (stored.owner.equals(session)) {
                stored.prepared = null;
            }
        }
    }

    /** Связывает исходный объект схемы с черновиком изменения. */
    private record ChangeTarget(ObjectId existing, ObjectId draft) {
        /** Проверяет сессию объекта и черновика. */
        void checkOwners(SessionId session) {
            if (draft != null) {
                checkOwner(session, draft);
            }
            if (existing != null) {
                checkOwner(session, existing);
            }
        }
    }

    /** Хранит рецепт плана до попытки применения. */
    private static final class StoredPlan {
        final SessionId owner;
        Prepared prepared;

        StoredPlan(SessionId owner, Prepared prepared) {
            this.owner = owner;
            this.prepared = prepared;
        }
    }
}
