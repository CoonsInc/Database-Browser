package org.databasebrowser.core.application.usecase;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.databasebrowser.core.application.SessionScope;
import org.databasebrowser.core.application.SessionScope.StoredPlan;
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

/**
 * Готовит и применяет изменения схемы по правилам, заданным адаптером.
 * Юзкейс проверяет запрос, готовит план изменений и применяет его только после явного предпросмотра.
 * План одноразовый: после любой попытки {@link #applyChange(ChangePlanId)} нужен новый {@link #prepareChange(Request)}.
 * Используется как {@code Usecases.change()}; проверки и вызовы адаптера привязаны к {@link SessionScope}.
 */
public final class ChangeSchema {
    private final SessionScope scope;

    /** Создаёт юзкейс для заданной сессии; {@code null} запрещён. */
    public ChangeSchema(SessionScope scope) {
        this.scope = Objects.requireNonNull(scope);
    }

    /**
     * Проверяет {@code request}, возможности адаптера и контекст схемы, затем сохраняет план в сессии.
     * Возвращает {@link Preparation}: план доступен только при отсутствии ошибок, найденные {@link Problem} передаются вместе с ним.
     * План привязан к сессии, запросу и токену снимка; несовпадение даёт {@link Code#CONFLICT}.
     */
    public Preparation prepareChange(Request request) {
        return scope.work(() -> {
            List<Problem> issues = scope.validator().validate(request);
            if (!issues.isEmpty())
                return new Preparation(Optional.empty(), issues);
            for (ObjectId object : requestIds(request)) scope.requireObject(object);
            if (scope.ports()
                    .info()
                    .getCapabilities(scope.session().id())
                    .support(request.kind())
                == Support.UNSUPPORTED)
                throw error(Code.UNSUPPORTED, "Schema operation is unsupported");
            Snapshot schema = scope.snapshot();
            List<Problem> contextual = contextualValidation(request, schema);
            if (!contextual.isEmpty())
                return new Preparation(Optional.empty(), contextual);
            Prepared prepared =
                scope.ports().changes().prepareChange(scope.session().id(), request);
            Plan plan = prepared.preview();
            if (!plan.session().equals(scope.session().id()) || !plan.request().equals(request)
                || !plan.schemaToken().equals(schema.stateToken()))
                throw error(
                    Code.CONFLICT, "Prepared plan differs from requested context; refresh preview");
            if (scope.putPlanIfAbsent(plan.id(), new StoredPlan(prepared)) != null)
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

    private List<Problem> contextualValidation(Request r, Snapshot snap) {
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
        boolean tableRequest = r instanceof AddColumn || r instanceof AlterColumn
            || r instanceof DropColumn || r instanceof AddConstraint || r instanceof DropConstraint
            || r instanceof DropTable || r instanceof CreateIndex;
        if (tableRequest && !(obj instanceof Table)
            || r instanceof DropIndex && !(obj instanceof Index)
            || (r instanceof DropView || r instanceof AlterView) && !(obj instanceof View))
            throw error(Code.INVALID_INPUT, "Wrong schema object kind");
        if (!(obj instanceof Table t))
            return List.of();
        IdentifierRules rules = scope.rules();
        List<Column> cols = new ArrayList<>(t.columns());
        List<Constraint> constraints = new ArrayList<>(t.constraints());
        if (r instanceof AddColumn x)
            cols.add(x.column());
        if (r instanceof AlterColumn x) {
            int pos = -1;
            for (int i = 0; i < cols.size(); i++)
                if (rules.equivalent(cols.get(i).name(), x.originalName()))
                    pos = i;
            if (pos < 0)
                throw error(Code.NOT_FOUND, "Column not found");
            cols.set(pos, x.desired());
            for (int j = 0; j < constraints.size(); j++)
                constraints.set(
                    j, renameLocal(constraints.get(j), x.originalName(), x.desired().name(), rules));
        }
        if (r instanceof DropColumn x) {
            boolean removed = cols.removeIf(c -> rules.equivalent(c.name(), x.column()));
            if (!removed)
                throw error(Code.NOT_FOUND, "Column not found");
        }
        if (r instanceof AddConstraint x)
            constraints.add(x.constraint());
        if (r instanceof DropConstraint x) {
            boolean removed = constraints.removeIf(c
                -> c.name().filter(n -> rules.equivalent(n, x.constraintName())).isPresent());
            if (!removed)
                throw error(Code.NOT_FOUND, "Constraint not found");
        }
        return scope.validator().validate(
            new CreateTable(new Table(t.id(), t.name(), cols, constraints, t.definition())));
    }

    private Constraint renameLocal(
        Constraint c, String oldName, String newName, IdentifierRules rules) {
        java.util.function.Function<List<String>, List<String>> rename =
            cols -> cols.stream().map(n -> rules.equivalent(n, oldName) ? newName : n).toList();
        if (c instanceof PrimaryKey k)
            return new PrimaryKey(k.name(), rename.apply(k.columns()));
        if (c instanceof Unique k)
            return new Unique(k.name(), rename.apply(k.columns()));
        if (c instanceof ForeignKey k)
            return new ForeignKey(k.name(), rename.apply(k.columns()), k.referencedTable(),
                k.referencedColumns(), k.onDelete(), k.onUpdate());
        return c; // CHECK expressions remain opaque; adapter validates them.
    }

    /**
     * Применяет ранее подготовленный план {@code planId} и возвращает {@link ApplyResult}.
     * План должен принадлежать текущей сессии и не содержать ошибок уровня {@link Severity#ERROR}.
     * План одноразовый: попытка помечается до вызова адаптера, поэтому даже сетевой сбой не разрешает повтор.
     * Нужен новый вызов {@link #prepareChange(Request)}.
     */
    public ApplyResult applyChange(ChangePlanId planId) {
        return scope.work(() -> {
            StoredPlan stored = scope.plan(planId);
            Plan p = stored.prepared().preview();
            if (!p.session().equals(scope.session().id()))
                throw error(Code.WRONG_SESSION, "Plan belongs to another session");
            if (!p.applicable()
                || p.problems().stream().anyMatch(x -> x.severity() == Severity.ERROR))
                throw error(Code.CONSTRAINT, "Plan cannot be applied");
            if (!stored.tryAttempt())
                throw error(
                    Code.ALREADY_APPLIED, "Plan has already been attempted; prepare a new preview");
            // Consume before adapter call: even a transport exception cannot authorize retry.
            return scope.ports().changes().applyChange(scope.session().id(), stored.prepared());
        });
    }
}
