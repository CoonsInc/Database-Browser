package org.databasebrowser.core.port;

import java.util.Objects;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Ids.AdapterId;

/**
 * One provider may return a per-connection bundle; state must be isolated by session ID.
 * identifierRules is immutable and available without opening a database.
 */
public interface AdapterProvider {
    AdapterId id();

    IdentifierRules identifierRules();

    AdapterPorts ports();

    record AdapterPorts(ConnectionPort connection, DatabaseInfoPort info, SchemaReaderPort schema,
        SchemaChangePort changes, SqlExecutionPort sql, QueryPlanPort plans, DdlExportPort ddl) {
        public AdapterPorts {
            Objects.requireNonNull(connection);
            Objects.requireNonNull(info);
            Objects.requireNonNull(schema);
            Objects.requireNonNull(changes);
            Objects.requireNonNull(sql);
            Objects.requireNonNull(plans);
            Objects.requireNonNull(ddl);
        }
    }
}
