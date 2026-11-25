package org.databasebrowser.sqlite;

import static org.databasebrowser.core.domain.Errors.error;

import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Connections.IdentifierRules;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.port.AdapterProvider;
import org.databasebrowser.core.port.SchemaChangePort;

/** Создаёт независимый набор SQLite-портов для каждого подключения. */
public final class SqliteAdapterProvider implements AdapterProvider {
    public static final AdapterId ID = new AdapterId("sqlite");

    private static final SchemaChangePort SCHEMA_CHANGES = new SchemaChangePort() {
        @Override
        public Prepared prepareChange(SessionId sessionId, Request request) {
            throw error(Code.UNSUPPORTED, "SQLite schema change plans are not implemented");
        }

        @Override
        public ApplyResult applyChange(SessionId sessionId, Prepared plan) {
            throw error(Code.UNSUPPORTED, "SQLite schema change plans are not implemented");
        }
    };

    @Override
    public AdapterId id() {
        return ID;
    }

    @Override
    public IdentifierRules identifierRules() {
        return SqliteSql::sameIdentifier;
    }

    @Override
    public AdapterPorts ports() {
        var session = new SqliteSession();

        return new AdapterPorts(session, session, new SqliteSchemaReader(session), SCHEMA_CHANGES,
            session.queries(),
            (id, request, mode) -> { throw error(Code.UNSUPPORTED, "SQLite query plans are not implemented"); },
            (snapshot, options) -> { throw error(Code.UNSUPPORTED, "SQLite DDL export is not implemented"); });
    }
}
