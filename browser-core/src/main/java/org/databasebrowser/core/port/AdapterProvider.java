package org.databasebrowser.core.port;

import java.util.Objects;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Ids.AdapterId;

/**
 * Состояние портов изолировано по сессиям. Для каждой сессии возможен свой набор портов.
 * Неизменяемые правила сравнения имён доступны до подключения.
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
