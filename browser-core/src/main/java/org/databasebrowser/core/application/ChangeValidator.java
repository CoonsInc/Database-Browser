package org.databasebrowser.core.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.databasebrowser.core.domain.Changes.AddColumn;
import org.databasebrowser.core.domain.Changes.AddConstraint;
import org.databasebrowser.core.domain.Changes.AlterColumn;
import org.databasebrowser.core.domain.Changes.AlterView;
import org.databasebrowser.core.domain.Changes.CreateIndex;
import org.databasebrowser.core.domain.Changes.CreateTable;
import org.databasebrowser.core.domain.Changes.CreateView;
import org.databasebrowser.core.domain.Changes.DropColumn;
import org.databasebrowser.core.domain.Changes.DropConstraint;
import org.databasebrowser.core.domain.Changes.DropIndex;
import org.databasebrowser.core.domain.Changes.DropTable;
import org.databasebrowser.core.domain.Changes.DropView;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Errors.Problem;
import org.databasebrowser.core.domain.Schema.Check;
import org.databasebrowser.core.domain.Schema.Column;
import org.databasebrowser.core.domain.Schema.Constraint;
import org.databasebrowser.core.domain.Schema.ForeignKey;
import org.databasebrowser.core.domain.Schema.Index;
import org.databasebrowser.core.domain.Schema.IndexTerm;
import org.databasebrowser.core.domain.Schema.PrimaryKey;
import org.databasebrowser.core.domain.Schema.QualifiedName;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Schema.Unique;
import org.databasebrowser.core.domain.Schema.View;

/** Проверяет черновики изменений схемы. */
public final class ChangeValidator {
    private final IdentifierRules names;

    /** Принимает правила сравнения имён выбранного адаптера. */
    public ChangeValidator(IdentifierRules names) {
        this.names = Objects.requireNonNull(names);
    }

    /** Возвращает ошибки черновика с путями до полей. */
    public List<Problem> validate(Request request) {
        List<Problem> problems = new ArrayList<>();
        if (request == null) {
            problems.add(Problem.error("required", "request", "Change is required"));
        } else if (request instanceof CreateTable change) {
            table(change.table(), problems);
        } else if (request instanceof AddColumn change) {
            required(change.table(), "table", problems);
            column(change.column(), "column", problems);
        } else if (request instanceof AlterColumn change) {
            required(change.table(), "table", problems);
            text(change.originalName(), "originalName", problems);
            column(change.desired(), "desired", problems);
        } else if (request instanceof DropColumn change) {
            required(change.table(), "table", problems);
            text(change.column(), "column", problems);
        } else if (request instanceof AddConstraint change) {
            required(change.table(), "table", problems);
            constraint(change.constraint(), null, "constraint", problems);
        } else if (request instanceof DropConstraint change) {
            required(change.table(), "table", problems);
            text(change.constraintName(), "constraintName", problems);
        } else if (request instanceof CreateIndex change) {
            index(change.index(), problems);
        } else if (request instanceof CreateView change) {
            view(change.view(), problems);
        } else if (request instanceof AlterView change) {
            required(change.original(), "original", problems);
            view(change.desired(), problems);
        } else if (request instanceof DropTable change) {
            required(change.table(), "table", problems);
        } else if (request instanceof DropIndex change) {
            required(change.index(), "index", problems);
        } else if (request instanceof DropView change) {
            required(change.view(), "view", problems);
        }

        return List.copyOf(problems);
    }

    // Проверки объектов схемы.

    /** Проверяет колонки и ограничения черновика таблицы. */
    private void table(Table table, List<Problem> problems) {
        if (!required(table, "table", problems)) {
            return;
        }

        required(table.id(), "table.id", problems);
        name(table.name(), "table.name", problems);
        if (table.columns().isEmpty()) {
            problems.add(Problem.error("empty", "table.columns", "At least one column is required"));
        }

        List<String> columnNames = new ArrayList<>();
        for (int i = 0; i < table.columns().size(); i++) {
            Column column = table.columns().get(i);
            column(column, "table.columns[" + i + "]", problems);
            columnNames.add(column.name());
        }
        duplicates(columnNames, "table.columns", problems);

        long primaryKeys = table.constraints().stream().filter(PrimaryKey.class::isInstance).count();
        if (primaryKeys > 1) {
            problems.add(Problem.error("multiple_primary_keys", "table.constraints",
                "Only one primary key is allowed"));
        }

        List<String> constraintNames = new ArrayList<>();
        for (int i = 0; i < table.constraints().size(); i++) {
            Constraint constraint = table.constraints().get(i);
            constraint(constraint, columnNames, "table.constraints[" + i + "]", problems);
            // Сохраняем исходный номер строки для ошибок формы.
            constraintNames.add(constraint.name() == null ? null : constraint.name().orElse(null));
        }
        duplicates(constraintNames, "table.constraints", problems);
    }

    /** Проверяет имя, объявленный тип и значение по умолчанию. */
    private void column(Column column, String path, List<Problem> problems) {
        if (!required(column, path, problems)) {
            return;
        }

        text(column.name(), path + ".name", problems);
        if (required(column.type(), path + ".type", problems) && column.type().declared()) {
            text(column.type().name(), path + ".type.name", problems);
            textList(column.type().parameters(), path + ".type.parameters", problems);
        }

        if (column.defaultExpression() == null) {
            problems.add(Problem.error("required", path + ".defaultExpression",
                "Use Optional.empty for absent DEFAULT"));
        } else {
            column.defaultExpression().ifPresent(s -> text(s, path + ".defaultExpression", problems));
        }
    }

    /** Проверяет структуру ограничения и доступные колонки. */
    private void constraint(Constraint constraint, List<String> available, String path,
        List<Problem> problems) {
        if (!required(constraint, path, problems)) {
            return;
        }

        if (constraint.name() == null) {
            problems.add(Problem.error("required", path + ".name",
                "Use Optional.empty for unnamed constraint"));
        } else {
            constraint.name().ifPresent(s -> text(s, path + ".name", problems));
        }

        if (constraint instanceof Check check) {
            text(check.expression(), path + ".expression", problems);
        } else if (constraint instanceof PrimaryKey key) {
            columns(key.columns(), available, path, problems);
        } else if (constraint instanceof Unique key) {
            columns(key.columns(), available, path, problems);
        } else if (constraint instanceof ForeignKey key) {
            columns(key.columns(), available, path, problems);
            name(key.referencedTable(), path + ".referencedTable", problems);
            String referenced = path + ".referencedColumns";
            if (key.columns().size() != key.referencedColumns().size()) {
                problems.add(Problem.error("foreign_key_arity", referenced,
                    "Foreign key column counts differ"));
            }
            duplicates(key.referencedColumns(), referenced, problems);
            textList(key.referencedColumns(), referenced, problems);
            required(key.onDelete(), path + ".onDelete", problems);
            required(key.onUpdate(), path + ".onUpdate", problems);
        }
    }

    /** Проверяет список колонок ограничения и ссылки на таблицу. */
    private void columns(List<String> columns, List<String> available, String path,
        List<Problem> problems) {
        String columnsPath = path + ".columns";
        if (columns.isEmpty()) {
            problems.add(Problem.error("empty", columnsPath, "Columns are required"));
        }
        duplicates(columns, columnsPath, problems);
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            String field = columnsPath + "[" + i + "]";
            text(column, field, problems);
            if (available != null && available.stream().noneMatch(name -> equivalent(name, column))) {
                problems.add(Problem.error("unknown_column", field, "Unknown column: " + column));
            }
        }
    }

    /** Проверяет поля индекса и состав его элементов. */
    private void index(Index index, List<Problem> problems) {
        if (!required(index, "index", problems)) {
            return;
        }

        required(index.id(), "index.id", problems);
        required(index.table(), "index.table", problems);
        name(index.name(), "index.name", problems);
        if (index.terms().isEmpty()) {
            problems.add(Problem.error("empty", "index.terms", "Index terms are required"));
        }

        for (int i = 0; i < index.terms().size(); i++) {
            IndexTerm term = index.terms().get(i);
            String path = "index.terms[" + i + "]";
            if (term.column().isPresent() == term.expression().isPresent()) {
                problems.add(Problem.error("index_term", path,
                    "Exactly one of column and expression is required"));
            }
            term.column().ifPresent(s -> text(s, path + ".column", problems));
            term.expression().ifPresent(s -> text(s, path + ".expression", problems));
            required(term.direction(), path + ".direction", problems);
        }
    }

    /** Проверяет имя, запрос и список колонок представления. */
    private void view(View view, List<Problem> problems) {
        if (!required(view, "view", problems)) {
            return;
        }

        required(view.id(), "view.id", problems);
        name(view.name(), "view.name", problems);
        text(view.query(), "view.query", problems);
        duplicates(view.columnNames(), "view.columnNames", problems);
        textList(view.columnNames(), "view.columnNames", problems);
    }

    // Проверки полей и имён.

    /** Проверяет имя объекта, каталог и схему при их наличии. */
    private void name(QualifiedName name, String path, List<Problem> problems) {
        if (required(name, path, problems)) {
            text(name.name(), path + ".name", problems);
            name.namespace().ifPresent(s -> text(s, path + ".namespace", problems));
            name.catalog().ifPresent(s -> text(s, path + ".catalog", problems));
        }
    }

    /** Сравнивает заданные имена по правилам адаптера. */
    private boolean equivalent(String left, String right) {
        return left != null && right != null && names.equivalent(left, right);
    }

    /** Находит дубликаты, сохраняя исходный индекс поля. */
    private void duplicates(List<String> values, String path, List<Problem> problems) {
        for (int i = 0; i < values.size(); i++) {
            String current = values.get(i);
            for (int j = 0; j < i; j++) {
                if (equivalent(current, values.get(j))) {
                    problems.add(Problem.error("duplicate", path + "[" + i + "]",
                        "Duplicate identifier: " + current));
                    break;
                }
            }
        }
    }

    /** Добавляет ошибку, если обязательное поле отсутствует. */
    private boolean required(Object value, String path, List<Problem> problems) {
        if (value != null) {
            return true;
        }
        problems.add(Problem.error("required", path, "Required field"));
        return false;
    }

    /** Проверяет, что строка содержит непробельные символы. */
    private void text(String value, String path, List<Problem> problems) {
        if (value == null || value.isBlank()) {
            problems.add(Problem.error("required", path, "Nonblank value is required"));
        }
    }

    /** Проверяет строки списка с отдельным путём для каждой. */
    private void textList(List<String> values, String path, List<Problem> problems) {
        for (int i = 0; i < values.size(); i++) {
            text(values.get(i), path + "[" + i + "]", problems);
        }
    }
}
