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

/**
 * Типы подготовки и применения изменений схемы. Запрос {@code Request} проверяется до вызова
 * адаптера, {@code Plan} привязан к конкретному {@code schemaToken}, а {@code Prepared} хранит
 * адаптерный {@code ExecutionRecipe}. План одноразовый: после попытки {@code applyChange} нужно
 * подготовить новый {@code prepareChange}.
 */
public final class Changes {
    private Changes() {}
    /**
     * Черновики форм: строки могут быть пустыми; валидация возвращает все проблемы полей до вызова адаптеров.
     * Закрытый базовый тип запросов изменения схемы. Создайте одну из записей-реализаций и
     * передайте её в {@code prepareChange}; метод {@code kind()} используется для проверки
     * поддержки операции адаптером.
     */

    public sealed interface Request permits CreateTable, DropTable, AddColumn, AlterColumn,
        DropColumn, AddConstraint, DropConstraint, CreateIndex, DropIndex, CreateView, AlterView,
        DropView {
        ChangeKind kind();
    }

    /** Запрос создания таблицы {@code Table}. Идентификатор таблицы должен быть новым для сессии. */
    public record CreateTable(Table table) implements Request {
        public ChangeKind kind() {
            return ChangeKind.CREATE_TABLE;
        }
    }

    /** Запрос удаления таблицы по {@code ObjectId}. Адаптер решает, что делать с зависимыми объектами. */
    public record DropTable(ObjectId table) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_TABLE;
        }
    }

    /** Запрос добавления колонки {@code Column} в таблицу {@code table}. */
    public record AddColumn(ObjectId table, Column column) implements Request {
        public ChangeKind kind() {
            return ChangeKind.ADD_COLUMN;
        }
    }

    /**
     * Запрос изменения колонки: {@code originalName} задаёт существующую колонку,
     * {@code desired} — новое описание.
     */
    public record AlterColumn(ObjectId table, String originalName, Column desired)
        implements Request {
        public ChangeKind kind() {
            return ChangeKind.ALTER_COLUMN;
        }
    }

    /** Запрос удаления колонки {@code column} из таблицы {@code table}. */
    public record DropColumn(ObjectId table, String column) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_COLUMN;
        }
    }

    /** Запрос добавления ограничения {@code Constraint} в таблицу {@code table}. */
    public record AddConstraint(ObjectId table, Constraint constraint) implements Request {
        public ChangeKind kind() {
            return ChangeKind.ADD_CONSTRAINT;
        }
    }

    /** Запрос удаления ограничения по имени {@code constraintName}. */
    public record DropConstraint(ObjectId table, String constraintName) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_CONSTRAINT;
        }
    }

    /** Запрос создания индекса {@code Index}; таблица задаётся полем {@code Index.table}. */
    public record CreateIndex(Index index) implements Request {
        public ChangeKind kind() {
            return ChangeKind.CREATE_INDEX;
        }
    }

    /** Запрос удаления индекса по {@code ObjectId}. */
    public record DropIndex(ObjectId index) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_INDEX;
        }
    }

    /** Запрос создания представления {@code View}. */
    public record CreateView(View view) implements Request {
        public ChangeKind kind() {
            return ChangeKind.CREATE_VIEW;
        }
    }

    /** Запрос замены представления {@code original} описанием {@code desired}. */
    public record AlterView(ObjectId original, View desired) implements Request {
        public ChangeKind kind() {
            return ChangeKind.ALTER_VIEW;
        }
    }

    /** Запрос удаления представления по {@code ObjectId}. */
    public record DropView(ObjectId view) implements Request {
        public ChangeKind kind() {
            return ChangeKind.DROP_VIEW;
        }
    }

    /**
     * Категория шага плана: SQL, проверка данных, копирование, настройка соединения,
     * восстановление объектов или {@code OTHER}.
     */
    public enum StepKind {
        SQL,
        DATA_CHECK,
        COPY_DATA,
        CONNECTION_CONFIGURATION,
        RESTORE_OBJECTS,
        OTHER
    }

    /**
     * Шаг плана с пояснением {@code description} и точным SQL {@code previewSql}.
     * Список {@code previewSql} может быть пустым для не-SQL-шагов, например копирования данных.
     */
    public record Step(StepKind kind, String description, List<String> previewSql) {
        public Step {
            previewSql = List.copyOf(previewSql);
        }
    }

    /**
     * Вид проверки данных перед применением: существование объекта, конфликт имён,
     * {@code NOT NULL}, уникальность, преобразование, внешний ключ или поддержка движка.
     */
    public enum CheckKind {
        OBJECT_EXISTS,
        NAME_COLLISION,
        NOT_NULL,
        UNIQUE,
        CONVERSION,
        FOREIGN_KEY,
        ENGINE
    }

    /** Результат проверки {@code CheckKind}. {@code passed} пуст, если адаптер не смог выполнить проверку. */
    public record DataCheck(CheckKind kind, String description, Optional<Boolean> passed) {}
    /**
     * Возможности отката изменения. {@code fullRollbackPossible} показывает, можно ли вернуть
     * исходное состояние полностью; {@code limitations} объясняет ограничения.
     */
    public record
        Rollback(boolean transactional, boolean fullRollbackPossible, String limitations) {}

    /**
     * Просмотр плана изменения схемы, привязанный к сессии, запросу и версии схемы
     * {@code schemaToken}. Поля {@code steps}, {@code checks}, {@code problems}, {@code dataLoss},
     * {@code affected} и {@code dependencies} описывают последствия, а {@code applicable} —
     * применимость. План одноразовый: после первой попытки {@code applyChange} требуется новый
     * план, даже если попытка завершилась ошибкой.
     */
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
    /**
     * Непрозрачный неизменяемый рецепт выполнения, которым владеет адаптер; объектов драйвера в нём нет.
     * Приложение хранит его внутри {@code Prepared} и передаёт обратно только в {@code applyChange};
     * создавать или инспектировать рецепт приложение не должно.
     */

    public interface ExecutionRecipe {}

    /**
     * Пара готового плана {@code preview} и адаптерного {@code recipe}. Оба поля обязательны:
     * конструктор отклоняет {@code null}. Храните {@code Prepared} до вызова {@code applyChange};
     * повторное применение того же рецепта запрещено.
     */
    public record Prepared(Plan preview, ExecutionRecipe recipe) {
        public Prepared {
            Objects.requireNonNull(preview);
            Objects.requireNonNull(recipe);
        }
    }

    /**
     * Результат подготовки изменения: план {@code plan} или список {@code problems}. При ошибке
     * валидации {@code plan} пуст. Такой результат не создаёт план и не требует применения.
     */
    public record Preparation(Optional<Plan> plan, List<Problem> problems) {
        public Preparation {
            problems = List.copyOf(problems);
        }
    }

    /**
     * Итог применения плана. {@code CONFLICT} означает расхождение {@code schemaToken},
     * {@code OUTCOME_UNKNOWN} — потерю связи.
     */
    public enum ApplyStatus { APPLIED, CONFLICT, REJECTED, CANCELLED, OUTCOME_UNKNOWN }

    /**
     * Результат применения плана с диагностикой {@code problems} и новым
     * {@code resultingSchemaToken}. Токен присутствует только при {@code APPLIED}; при любом
     * другом исходе новое состояние схемы не подтверждено. Повторная попытка с тем же
     * {@code Changes.Plan} отклоняется.
     */
    public record ApplyResult(
        ApplyStatus status, List<Problem> problems, Optional<String> resultingSchemaToken) {
        public ApplyResult {
            problems = List.copyOf(problems);
        }
    }
}
