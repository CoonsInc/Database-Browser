package org.databasebrowser.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.databasebrowser.core.domain.Connections.ChangeKind;
import org.databasebrowser.core.domain.Errors.Problem;
import org.databasebrowser.core.domain.Ids.ChangePlanId;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Column;
import org.databasebrowser.core.domain.Schema.Constraint;
import org.databasebrowser.core.domain.Schema.Dependencies;
import org.databasebrowser.core.domain.Schema.Index;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Schema.View;

public final class Changes {
    private Changes() {}
    /** Черновик формы. Ошибки полей собираются до вызова адаптера. */

    public sealed interface Request permits CreateTable, DropTable, AddColumn, AlterColumn,
        DropColumn, AddConstraint, DropConstraint, CreateIndex, DropIndex, CreateView, AlterView,
        DropView {
        ChangeKind kind();
    }

    public record CreateTable(Table table) implements Request {
        public ChangeKind kind() {
            return ChangeKind.CREATE_TABLE;
        }
    }

    public record DropTable(ObjectId table) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_TABLE;
        }
    }

    public record AddColumn(ObjectId table, Column column) implements Request {
        public ChangeKind kind() {
            return ChangeKind.ADD_COLUMN;
        }
    }

    public record AlterColumn(ObjectId table, String originalName, Column desired)
        implements Request {
        public ChangeKind kind() {
            return ChangeKind.ALTER_COLUMN;
        }
    }

    public record DropColumn(ObjectId table, String column) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_COLUMN;
        }
    }

    public record AddConstraint(ObjectId table, Constraint constraint) implements Request {
        public ChangeKind kind() {
            return ChangeKind.ADD_CONSTRAINT;
        }
    }

    public record DropConstraint(ObjectId table, String constraintName) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_CONSTRAINT;
        }
    }

    public record CreateIndex(Index index) implements Request {
        public ChangeKind kind() {
            return ChangeKind.CREATE_INDEX;
        }
    }

    public record DropIndex(ObjectId index) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_INDEX;
        }
    }

    public record CreateView(View view) implements Request {
        public ChangeKind kind() {
            return ChangeKind.CREATE_VIEW;
        }
    }

    public record AlterView(ObjectId original, View desired) implements Request {
        public ChangeKind kind() {
            return ChangeKind.ALTER_VIEW;
        }
    }

    public record DropView(ObjectId view) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_VIEW;
        }
    }

    public enum StepKind {
        SQL,
        DATA_CHECK,
        COPY_DATA,
        CONNECTION_CONFIGURATION,
        RESTORE_OBJECTS,
        OTHER
    }

    public record Step(StepKind kind, String description, List<String> previewSql) {
        public Step {
            previewSql = List.copyOf(previewSql);
        }
    }

    public enum CheckKind {
        OBJECT_EXISTS,
        NAME_COLLISION,
        NOT_NULL,
        UNIQUE,
        CONVERSION,
        FOREIGN_KEY,
        ENGINE
    }

    public record DataCheck(CheckKind kind, String description, Optional<Boolean> passed) {}
    public record
        Rollback(boolean transactional, boolean fullRollbackPossible, String limitations) {}

    public record Plan(ChangePlanId id, SessionId session, Request request, String schemaToken,
        List<Step> steps, List<DataCheck> checks, Set<ObjectId> affected, Dependencies dependencies,
        List<Problem> problems, List<String> dataLoss, Rollback rollback, boolean applicable) {
        public Plan {
            steps = List.copyOf(steps);
            checks = List.copyOf(checks);
            affected = Set.copyOf(affected);
            problems = List.copyOf(problems);
            dataLoss = List.copyOf(dataLoss);
        }
    }
    /** Неизменяемый рецепт адаптера без ресурсов драйвера. Скрыт от интерфейса. */

    public interface ExecutionRecipe {}

    public record Prepared(Plan preview, ExecutionRecipe recipe) {
        public Prepared {
            Objects.requireNonNull(preview);
            Objects.requireNonNull(recipe);
        }
    }

    public record Preparation(Optional<Plan> plan, List<Problem> problems) {
        public Preparation {
            problems = List.copyOf(problems);
        }
    }

    public enum ApplyStatus { APPLIED, CONFLICT, REJECTED, CANCELLED, OUTCOME_UNKNOWN }

    public record ApplyResult(
        ApplyStatus status, List<Problem> problems, Optional<String> resultingSchemaToken) {
        public ApplyResult {
            problems = List.copyOf(problems);
        }
    }
}
