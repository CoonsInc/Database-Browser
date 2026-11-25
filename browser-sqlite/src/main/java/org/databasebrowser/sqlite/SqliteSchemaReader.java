package org.databasebrowser.sqlite;

import static org.databasebrowser.core.domain.Errors.error;
import static org.databasebrowser.sqlite.SqliteSql.quoteIdentifier;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Column;
import org.databasebrowser.core.domain.Schema.Constraint;
import org.databasebrowser.core.domain.Schema.DataType;
import org.databasebrowser.core.domain.Schema.Definition;
import org.databasebrowser.core.domain.Schema.Dependencies;
import org.databasebrowser.core.domain.Schema.Dependency;
import org.databasebrowser.core.domain.Schema.Direction;
import org.databasebrowser.core.domain.Schema.ForeignKey;
import org.databasebrowser.core.domain.Schema.ForeignKeyAction;
import org.databasebrowser.core.domain.Schema.Index;
import org.databasebrowser.core.domain.Schema.IndexTerm;
import org.databasebrowser.core.domain.Schema.PrimaryKey;
import org.databasebrowser.core.domain.Schema.QualifiedName;
import org.databasebrowser.core.domain.Schema.SchemaObject;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Schema.Trigger;
import org.databasebrowser.core.domain.Schema.Unique;
import org.databasebrowser.core.domain.Schema.View;
import org.databasebrowser.core.port.SchemaReaderPort;
import org.sqlite.SQLiteErrorCode;

/** Читает схему из sqlite_schema и PRAGMA в согласованном снимке. */
final class SqliteSchemaReader implements SchemaReaderPort {
    private static final Pattern TYPE_DECLARATION =
        Pattern.compile("([^()]+?)(?:\\(([^()]*)\\))?");
    private static final String SAVEPOINT = "dbbrowser_schema_read";
    private final SqliteSession session;

    SqliteSchemaReader(SqliteSession session) {
        this.session = session;
    }

    @Override
    public Snapshot readSchema(SessionId sessionId) {
        Connection connection = session.connection(sessionId);
        try (Statement transaction = connection.createStatement()) {
            transaction.execute("SAVEPOINT " + SAVEPOINT);
            try {
                Snapshot snapshot = readSnapshot(connection, sessionId);
                transaction.execute("RELEASE " + SAVEPOINT);
                return snapshot;
            } catch (SQLException | RuntimeException e) {
                try {
                    transaction.execute("ROLLBACK TO " + SAVEPOINT);
                    transaction.execute("RELEASE " + SAVEPOINT);
                } catch (SQLException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw SqliteErrors.failure(e, Code.SQL);
        }
    }

    @Override
    public Definition readObjectDefinition(SessionId sessionId, ObjectId objectId) {
        return readObject(sessionId, objectId, readSchema(sessionId)).definition();
    }

    @Override
    public Dependencies findDependencies(SessionId sessionId, ObjectId objectId) {
        Snapshot snapshot = readSchema(sessionId);
        readObject(sessionId, objectId, snapshot);
        Dependencies graph = snapshot.dependencies();
        List<Dependency> edges = graph.edges().stream()
            .filter(edge -> edge.prerequisite().equals(objectId) || edge.dependent().equals(objectId))
            .toList();
        return new Dependencies(edges, graph.complete(), graph.limitations());
    }

    private static Snapshot readSnapshot(Connection connection, SessionId sessionId) throws SQLException {
        List<Namespace> databases = readDatabases(connection);
        List<QualifiedName> namespaces = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        StringBuilder state = new StringBuilder();

        for (Namespace database : databases) {
            String namespace = database.name();
            namespaces.add(new QualifiedName(Optional.empty(), Optional.empty(), namespace));
            addToState(state, namespace);
            addToState(state, database.file());

            String schema = quoteIdentifier(namespace);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("PRAGMA " + schema + ".schema_version")) {
                if (rows.next()) {
                    addToState(state, rows.getString(1));
                }
            }
            entries.addAll(readEntries(connection, namespace));
        }

        Map<QualifiedName, SchemaObject> relations = new LinkedHashMap<>();
        Map<ObjectId, List<IndexMetadata>> indexes = new HashMap<>();
        List<SchemaObject> objects = new ArrayList<>();
        List<Dependency> edges = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        for (Entry entry : entries) {
            addToState(state, entry.namespace());
            addToState(state, entry.kind());
            addToState(state, entry.name());
            addToState(state, entry.sql());

            if (entry.kind().equals("table")) {
                List<IndexMetadata> tableIndexes = readIndexList(connection, entry);
                Table table = readTable(connection, sessionId, entry, tableIndexes);
                indexes.put(table.id(), tableIndexes);
                relations.put(table.name(), table);
                objects.add(table);
            } else if (entry.kind().equals("view")) {
                View view = readView(connection, sessionId, entry, limitations);
                relations.put(view.name(), view);
                objects.add(view);
            }
        }

        for (Entry entry : entries) {
            QualifiedName ownerName = name(entry.namespace(), entry.owner());
            SchemaObject owner = relations.get(ownerName);
            if (entry.kind().equals("index") && owner instanceof Table table) {
                List<IndexMetadata> tableIndexes = indexes.getOrDefault(table.id(), List.of());
                for (IndexMetadata metadata : tableIndexes) {
                    if (metadata.name().equals(entry.name())) {
                        Index index = readIndex(connection, sessionId, entry, table.id(), metadata);
                        objects.add(index);
                        edges.add(dependency(table.id(), index.id(), "INDEX"));
                        break;
                    }
                }
            } else if (entry.kind().equals("trigger")) {
                if (owner == null && entry.namespace().equals("temp")) {
                    owner = uniqueRelation(relations, entry.owner());
                }
                ObjectId ownerId = owner == null
                    ? objectId(sessionId, "table", entry.namespace(), entry.owner()) : owner.id();
                Trigger trigger = new Trigger(entry.id(sessionId), entry.qualifiedName(), ownerId,
                    entry.definition(Map.of()));
                objects.add(trigger);
                if (owner != null) {
                    edges.add(dependency(ownerId, trigger.id(), "TRIGGER"));
                }
            }
        }

        for (SchemaObject object : relations.values()) {
            if (object instanceof Table table) {
                addForeignKeyEdges(table, relations, edges);
            }
        }
        limitations.add("Dependencies in view and trigger SQL are not resolved");
        String token = UUID.nameUUIDFromBytes(state.toString().getBytes(StandardCharsets.UTF_8)).toString();
        return new Snapshot(sessionId, SqliteAdapterProvider.ID, token, namespaces, objects,
            new Dependencies(edges, false, limitations));
    }

    private static List<Namespace> readDatabases(Connection connection) throws SQLException {
        List<Namespace> databases = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA database_list")) {
            while (rows.next()) {
                databases.add(new Namespace(rows.getString("name"), rows.getString("file")));
            }
        }
        return databases;
    }

    private static List<Entry> readEntries(Connection connection, String namespace) throws SQLException {
        List<Entry> entries = new ArrayList<>();
        String sql = "SELECT type, name, tbl_name, sql FROM " + quoteIdentifier(namespace)
            + ".sqlite_schema WHERE substr(name, 1, 7) <> 'sqlite_'"
            + " OR (type = 'index' AND sql IS NULL) ORDER BY type, name";
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                entries.add(new Entry(namespace, rows.getString("type"), rows.getString("name"),
                    rows.getString("tbl_name"), rows.getString("sql")));
            }
        }
        return entries;
    }

    private static Table readTable(Connection connection, SessionId sessionId, Entry entry,
        List<IndexMetadata> indexes) throws SQLException {
        List<ColumnMetadata> columns = readColumns(connection, entry);
        List<Constraint> constraints = new ArrayList<>();
        Map<Integer, String> primaryKey = new TreeMap<>();
        for (ColumnMetadata column : columns) {
            if (column.keyPosition() > 0) {
                primaryKey.put(column.keyPosition(), column.name());
            }
        }
        if (!primaryKey.isEmpty()) {
            constraints.add(new PrimaryKey(Optional.empty(), List.copyOf(primaryKey.values())));
        }

        for (IndexMetadata index : indexes) {
            if (index.origin().equals("u")) {
                List<String> unique = readIndexTerms(connection, entry.namespace(), index.name()).stream()
                    .flatMap(term -> term.column().stream())
                    .toList();
                constraints.add(new Unique(Optional.empty(), unique));
            }
        }
        constraints.addAll(readForeignKeys(connection, entry));

        boolean indexedPrimaryKey = indexes.stream().anyMatch(index -> index.origin().equals("pk"));
        List<Column> tableColumns = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            boolean rowId = !indexedPrimaryKey && primaryKey.size() == 1 && column.keyPosition() == 1
                && SqliteSql.sameIdentifier(column.declaration(), "INTEGER");
            Map<String, String> attributes = column.hidden() == 0 ? Map.of()
                : Map.of("sqlite.hidden", Integer.toString(column.hidden()));
            tableColumns.add(new Column(column.name(), dataType(column.declaration()),
                !column.notNull() && !rowId, Optional.ofNullable(column.defaultSql()), attributes));
        }

        return new Table(entry.id(sessionId), entry.qualifiedName(), tableColumns, constraints,
            entry.definition(Map.of()));
    }

    private static List<ColumnMetadata> readColumns(Connection connection, Entry entry)
        throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
            SELECT name, type, "notnull", dflt_value, pk, hidden
            FROM pragma_table_xinfo(?, ?) ORDER BY cid
            """)) {
            statement.setString(1, entry.name());
            statement.setString(2, entry.namespace());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(new ColumnMetadata(rows.getString("name"), rows.getString("type"),
                        rows.getInt("notnull") != 0, rows.getString("dflt_value"),
                        rows.getInt("pk"), rows.getInt("hidden")));
                }
            }
        }
        return columns;
    }

    private static List<IndexMetadata> readIndexList(Connection connection, Entry entry)
        throws SQLException {
        List<IndexMetadata> indexes = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
            SELECT name, "unique", origin, partial FROM pragma_index_list(?, ?)
            """)) {
            statement.setString(1, entry.name());
            statement.setString(2, entry.namespace());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    indexes.add(new IndexMetadata(rows.getString("name"), rows.getInt("unique") != 0,
                        rows.getString("origin"), rows.getInt("partial") != 0));
                }
            }
        }
        return indexes;
    }

    private static Index readIndex(Connection connection, SessionId sessionId, Entry entry,
        ObjectId table, IndexMetadata metadata) throws SQLException {
        List<IndexTerm> terms = readIndexTerms(connection, entry.namespace(), entry.name());
        Optional<String> predicate = metadata.partial() && entry.sql() != null
            ? SqliteSql.afterKeyword(entry.sql(), "WHERE") : Optional.empty();
        Definition definition = entry.definition(Map.of("sqlite.origin", metadata.origin()));
        return new Index(entry.id(sessionId), entry.qualifiedName(), table, metadata.unique(),
            terms, predicate, definition);
    }

    private static List<IndexTerm> readIndexTerms(Connection connection, String namespace, String index)
        throws SQLException {
        List<IndexTerm> terms = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
            SELECT name, "desc", coll FROM pragma_index_xinfo(?, ?)
            WHERE "key" = 1 ORDER BY seqno
            """)) {
            statement.setString(1, index);
            statement.setString(2, namespace);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    // Текст выражения SQLite хранит в исходном определении индекса.
                    terms.add(new IndexTerm(Optional.ofNullable(rows.getString("name")), Optional.empty(),
                        rows.getInt("desc") != 0 ? Direction.DESC : Direction.ASC,
                        Optional.ofNullable(rows.getString("coll"))));
                }
            }
        }
        return terms;
    }

    private static List<ForeignKey> readForeignKeys(Connection connection, Entry entry)
        throws SQLException {
        Map<Integer, ForeignKeyMetadata> keys = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
            SELECT id, "table", "from", "to", on_delete, on_update
            FROM pragma_foreign_key_list(?, ?) ORDER BY id, seq
            """)) {
            statement.setString(1, entry.name());
            statement.setString(2, entry.namespace());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    int id = rows.getInt("id");
                    ForeignKeyMetadata key = keys.get(id);
                    if (key == null) {
                        key = new ForeignKeyMetadata(name(entry.namespace(), rows.getString("table")),
                            action(rows.getString("on_delete")), action(rows.getString("on_update")),
                            new ArrayList<>(), new ArrayList<>());
                        keys.put(id, key);
                    }
                    key.from().add(rows.getString("from"));
                    String referenced = rows.getString("to");
                    if (referenced != null) {
                        key.to().add(referenced);
                    }
                }
            }
        }
        return keys.values().stream()
            .map(key -> new ForeignKey(Optional.empty(), key.from(), key.table(), key.to(),
                key.onDelete(), key.onUpdate()))
            .toList();
    }

    private static View readView(Connection connection, SessionId sessionId, Entry entry,
        List<String> limitations) throws SQLException {
        List<String> columns;
        try {
            columns = readColumns(connection, entry).stream().map(ColumnMetadata::name).toList();
        } catch (SQLException e) {
            if ((e.getErrorCode() & 0xff) != SQLiteErrorCode.SQLITE_ERROR.code) {
                throw e;
            }
            // Представление может ссылаться на таблицу, которой пока нет.
            columns = List.of();
            if (!limitations.contains("Some view columns could not be resolved")) {
                limitations.add("Some view columns could not be resolved");
            }
        }
        String query = entry.sql() == null ? "" : SqliteSql.afterKeyword(entry.sql(), "AS").orElse("");
        return new View(entry.id(sessionId), entry.qualifiedName(), query, columns, entry.definition(Map.of()));
    }

    private static DataType dataType(String declaration) {
        if (declaration == null || declaration.isBlank()) {
            return DataType.undeclared();
        }

        var match = TYPE_DECLARATION.matcher(declaration.strip());
        if (!match.matches()) {
            return new DataType(declaration, List.of(), Optional.of(declaration), false);
        }
        String name = match.group(1).strip();
        String arguments = match.group(2);
        List<String> parameters = arguments == null ? List.of()
            : java.util.Arrays.stream(arguments.split(",")).map(String::strip).toList();
        return new DataType(name, parameters, Optional.of(declaration), SqliteSession.knownType(name));
    }

    private static void addForeignKeyEdges(Table table, Map<QualifiedName, SchemaObject> relations,
        List<Dependency> edges) {
        for (Constraint constraint : table.constraints()) {
            if (constraint instanceof ForeignKey fk) {
                SchemaObject parent = relations.get(fk.referencedTable());
                if (parent == null) {
                    parent = relations.entrySet().stream()
                        .filter(entry -> entry.getKey().namespace().equals(fk.referencedTable().namespace())
                            && SqliteSql.sameIdentifier(entry.getKey().name(), fk.referencedTable().name()))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse(null);
                }
                if (parent instanceof Table) {
                    edges.add(dependency(parent.id(), table.id(), "FOREIGN_KEY"));
                }
            }
        }
    }

    private static SchemaObject uniqueRelation(Map<QualifiedName, SchemaObject> relations, String name) {
        SchemaObject match = null;
        for (SchemaObject object : relations.values()) {
            if (SqliteSql.sameIdentifier(object.name().name(), name)) {
                if (match != null) {
                    return null;
                }
                match = object;
            }
        }
        return match;
    }

    private static SchemaObject readObject(SessionId id, ObjectId objectId, Snapshot snapshot) {
        if (objectId == null) {
            throw error(Code.INVALID_INPUT, "SQLite object is required");
        }
        if (!objectId.session().equals(id)) {
            throw error(Code.WRONG_SESSION, "SQLite object belongs to another session");
        }
        return snapshot.objects().stream().filter(object -> object.id().equals(objectId)).findFirst()
            .orElseThrow(() -> error(Code.NOT_FOUND, "SQLite schema object was not found"));
    }

    private static QualifiedName name(String namespace, String name) {
        return new QualifiedName(Optional.empty(), Optional.of(namespace), name);
    }

    private static ObjectId objectId(SessionId id, String kind, String namespace, String name) {
        return new ObjectId(id, kind + ":" + namespace.length() + ":" + namespace + name);
    }

    private static Dependency dependency(ObjectId prerequisite, ObjectId dependent, String kind) {
        return new Dependency(prerequisite, dependent, Optional.of(kind));
    }

    private static ForeignKeyAction action(String name) {
        return ForeignKeyAction.valueOf(name.replace(' ', '_').toUpperCase(Locale.ROOT));
    }

    private static void addToState(StringBuilder state, String value) {
        if (value == null) {
            state.append("-1:");
        } else {
            state.append(value.length()).append(':').append(value);
        }
    }

    private record Namespace(String name, String file) {}

    private record Entry(String namespace, String kind, String name, String owner, String sql) {
        QualifiedName qualifiedName() {
            return SqliteSchemaReader.name(namespace, name);
        }

        ObjectId id(SessionId session) {
            return objectId(session, kind, namespace, name);
        }

        Definition definition(Map<String, String> attributes) {
            return new Definition(Optional.ofNullable(sql), attributes, false);
        }
    }

    private record ColumnMetadata(String name, String declaration, boolean notNull, String defaultSql,
        int keyPosition, int hidden) {}

    private record IndexMetadata(String name, boolean unique, String origin, boolean partial) {}

    private record ForeignKeyMetadata(QualifiedName table, ForeignKeyAction onDelete,
        ForeignKeyAction onUpdate, List<String> from, List<String> to) {}
}
