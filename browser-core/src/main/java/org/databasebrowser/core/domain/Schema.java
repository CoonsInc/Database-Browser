package org.databasebrowser.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;

public final class Schema {
    private Schema() {}

    public record QualifiedName(Optional<String> catalog, Optional<String> namespace, String name) {
    }

    public record DataType(
        String name, List<String> parameters, Optional<String> originalDeclaration, boolean known) {
        public DataType {
            parameters = List.copyOf(parameters);
        }
    }
    /**
     * Optional.empty() = no DEFAULT; Optional.of("NULL") = explicit DEFAULT NULL. SQL is opaque.
     */

    public record Column(String name, DataType type, boolean nullable,
        Optional<String> defaultExpression, Map<String, String> attributes) {
        public Column {
            attributes = Map.copyOf(attributes);
        }
    }

    public enum ForeignKeyAction { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT }

    public sealed interface Constraint permits PrimaryKey, ForeignKey, Unique, Check {
        Optional<String> name();
    }

    public record PrimaryKey(Optional<String> name, List<String> columns) implements Constraint {
        public PrimaryKey {
            columns = List.copyOf(columns);
        }
    }

    public record Unique(Optional<String> name, List<String> columns) implements Constraint {
        public Unique {
            columns = List.copyOf(columns);
        }
    }

    public record ForeignKey(Optional<String> name, List<String> columns,
        QualifiedName referencedTable, List<String> referencedColumns, ForeignKeyAction onDelete,
        ForeignKeyAction onUpdate) implements Constraint {
        public ForeignKey {
            columns = List.copyOf(columns);
            referencedColumns = List.copyOf(referencedColumns);
        }
    }

    public record Check(Optional<String> name, String expression) implements Constraint {}
    public record
        Definition(Optional<String> originalSql, Map<String, String> attributes, boolean editable) {
        public Definition {
            attributes = Map.copyOf(attributes);
        }
    }

    public sealed interface SchemaObject permits Table, Index, View, Trigger {
        ObjectId id();

        QualifiedName name();

        Definition definition();
    }

    public record Table(ObjectId id, QualifiedName name, List<Column> columns,
        List<Constraint> constraints, Definition definition) implements SchemaObject {
        public Table {
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
        }
    }

    public enum Direction { ASC, DESC, UNSPECIFIED }
    /** Expression preserves indexes that are not simple column lists. */

    public record IndexTerm(Optional<String> column, Optional<String> expression,
        Direction direction, Optional<String> collation) {}

    public record Index(ObjectId id, QualifiedName name, ObjectId table, boolean unique,
        List<IndexTerm> terms, Optional<String> predicate, Definition definition)
        implements SchemaObject {
        public Index {
            terms = List.copyOf(terms);
        }
    }

    public record View(ObjectId id, QualifiedName name, String query, List<String> columnNames,
        Definition definition) implements SchemaObject {
        public View {
            columnNames = List.copyOf(columnNames);
        }
    }

    public record Trigger(ObjectId id, QualifiedName name, ObjectId owner, Definition definition)
        implements SchemaObject {}

    public record Dependency(ObjectId prerequisite, ObjectId dependent, Optional<String> kind) {}

    public record Dependencies(List<Dependency> edges, boolean complete, List<String> limitations) {
        public Dependencies {
            edges = List.copyOf(edges);
            limitations = List.copyOf(limitations);
        }
    }
    /** Adapter must return a consistent snapshot and an opaque version token. */

    public record Snapshot(SessionId session, AdapterId dialect, String stateToken,
        List<QualifiedName> namespaces, List<SchemaObject> objects, Dependencies dependencies) {
        public Snapshot {
            namespaces = List.copyOf(namespaces);
            objects = List.copyOf(objects);
        }
    }
}
