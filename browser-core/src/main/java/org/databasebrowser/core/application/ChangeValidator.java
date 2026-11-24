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

/** Common structural checks only; identifier comparison comes from the provider. */
public final class ChangeValidator {
    private final IdentifierRules names;

    public ChangeValidator(IdentifierRules names) {
        this.names = Objects.requireNonNull(names);
    }

    public List<Problem> validate(Request request) {
        List<Problem> p = new ArrayList<>();
        if (request == null) {
            add(p, "required", "request", "Change is required");
            return List.copyOf(p);
        }
        if (request instanceof CreateTable r)
            table(r.table(), p);
        else if (request instanceof AddColumn r) {
            required(r.table(), "table", p);
            column(r.column(), "column", p);
        } else if (request instanceof AlterColumn r) {
            required(r.table(), "table", p);
            text(r.originalName(), "originalName", p);
            column(r.desired(), "desired", p);
        } else if (request instanceof DropColumn r) {
            required(r.table(), "table", p);
            text(r.column(), "column", p);
        } else if (request instanceof AddConstraint r) {
            required(r.table(), "table", p);
            constraint(r.constraint(), null, "constraint", p);
        } else if (request instanceof DropConstraint r) {
            required(r.table(), "table", p);
            text(r.constraintName(), "constraintName", p);
        } else if (request instanceof CreateIndex r)
            index(r.index(), p);
        else if (request instanceof CreateView r)
            view(r.view(), p);
        else if (request instanceof AlterView r) {
            required(r.original(), "original", p);
            view(r.desired(), p);
        } else if (request instanceof DropTable r)
            required(r.table(), "table", p);
        else if (request instanceof DropIndex r)
            required(r.index(), "index", p);
        else if (request instanceof DropView r)
            required(r.view(), "view", p);
        return List.copyOf(p);
    }

    private void table(Table t, List<Problem> p) {
        if (!required(t, "table", p))
            return;
        required(t.id(), "table.id", p);
        name(t.name(), "table.name", p);
        if (t.columns().isEmpty())
            add(p, "empty", "table.columns", "At least one column is required");
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < t.columns().size(); i++) {
            Column c = t.columns().get(i);
            column(c, "table.columns[" + i + "]", p);
            cols.add(c.name());
        }
        duplicates(cols, "table.columns", p);
        long pk = t.constraints().stream().filter(PrimaryKey.class ::isInstance).count();
        if (pk > 1)
            add(p, "multiple_primary_keys", "table.constraints", "Only one primary key is allowed");
        List<String> constraintNames = new ArrayList<>();
        for (int i = 0; i < t.constraints().size(); i++) {
            Constraint c = t.constraints().get(i);
            constraint(c, cols, "table.constraints[" + i + "]", p);
            if (c.name() != null)
                c.name().ifPresent(constraintNames::add);
        }
        duplicates(constraintNames, "table.constraints", p);
    }

    private void column(Column c, String path, List<Problem> p) {
        if (!required(c, path, p))
            return;
        text(c.name(), path + ".name", p);
        if (required(c.type(), path + ".type", p)) {
            text(c.type().name(), path + ".type.name", p);
            for (int i = 0; i < c.type().parameters().size(); i++)
                text(c.type().parameters().get(i), path + ".type.parameters[" + i + "]", p);
        }
        if (c.defaultExpression() == null)
            add(p, "required", path + ".defaultExpression",
                "Use Optional.empty for absent DEFAULT");
        else
            c.defaultExpression().ifPresent(s -> text(s, path + ".defaultExpression", p));
    }

    private void constraint(Constraint c, List<String> available, String path, List<Problem> p) {
        if (!required(c, path, p))
            return;
        if (c.name() == null)
            add(p, "required", path + ".name", "Use Optional.empty for unnamed constraint");
        else
            c.name().ifPresent(s -> text(s, path + ".name", p));
        List<String> cols = c instanceof PrimaryKey k ? k.columns()
            : c instanceof Unique u                   ? u.columns()
            : c instanceof ForeignKey f               ? f.columns()
                                                      : List.of();
        if (!(c instanceof Check)) {
            if (cols.isEmpty())
                add(p, "empty", path + ".columns", "Columns are required");
            duplicates(cols, path + ".columns", p);
            for (int i = 0; i < cols.size(); i++) {
                String col = cols.get(i);
                text(col, path + ".columns[" + i + "]", p);
                if (available != null && available.stream().noneMatch(a -> eq(a, col)))
                    add(p, "unknown_column", path + ".columns[" + i + "]",
                        "Unknown column: " + col);
            }
        }
        if (c instanceof ForeignKey f) {
            name(f.referencedTable(), path + ".referencedTable", p);
            if (cols.size() != f.referencedColumns().size())
                add(p, "foreign_key_arity", path + ".referencedColumns",
                    "Foreign key column counts differ");
            duplicates(f.referencedColumns(), path + ".referencedColumns", p);
            for (String s : f.referencedColumns()) text(s, path + ".referencedColumns", p);
            required(f.onDelete(), path + ".onDelete", p);
            required(f.onUpdate(), path + ".onUpdate", p);
        }
        if (c instanceof Check k)
            text(k.expression(), path + ".expression", p);
    }

    private void index(Index i, List<Problem> p) {
        if (!required(i, "index", p))
            return;
        required(i.id(), "index.id", p);
        required(i.table(), "index.table", p);
        name(i.name(), "index.name", p);
        if (i.terms().isEmpty())
            add(p, "empty", "index.terms", "Index terms are required");
        for (int j = 0; j < i.terms().size(); j++) {
            IndexTerm t = i.terms().get(j);
            String path = "index.terms[" + j + "]";
            if (t.column().isPresent() == t.expression().isPresent())
                add(p, "index_term", path, "Exactly one of column and expression is required");
            t.column().ifPresent(s -> text(s, path + ".column", p));
            t.expression().ifPresent(s -> text(s, path + ".expression", p));
            required(t.direction(), path + ".direction", p);
        }
    }

    private void view(View v, List<Problem> p) {
        if (!required(v, "view", p))
            return;
        required(v.id(), "view.id", p);
        name(v.name(), "view.name", p);
        text(v.query(), "view.query", p);
        duplicates(v.columnNames(), "view.columnNames", p);
        for (String s : v.columnNames()) text(s, "view.columnNames", p);
    }

    private void name(QualifiedName n, String path, List<Problem> p) {
        if (required(n, path, p)) {
            text(n.name(), path + ".name", p);
            n.namespace().ifPresent(s -> text(s, path + ".namespace", p));
            n.catalog().ifPresent(s -> text(s, path + ".catalog", p));
        }
    }

    private boolean eq(String a, String b) {
        return a != null && b != null && names.equivalent(a, b);
    }

    private void duplicates(List<String> s, String path, List<Problem> p) {
        for (int i = 0; i < s.size(); i++)
            for (int j = 0; j < i; j++)
                if (eq(s.get(i), s.get(j))) {
                    add(p, "duplicate", path + "[" + i + "]", "Duplicate identifier: " + s.get(i));
                    break;
                }
    }

    private static boolean required(Object o, String path, List<Problem> p) {
        if (o == null) {
            add(p, "required", path, "Required field");
            return false;
        }
        return true;
    }

    private static void text(String s, String path, List<Problem> p) {
        if (s == null || s.isBlank())
            add(p, "required", path, "Nonblank value is required");
    }

    private static void add(List<Problem> p, String code, String path, String message) {
        p.add(Problem.error(code, path, message));
    }
}
