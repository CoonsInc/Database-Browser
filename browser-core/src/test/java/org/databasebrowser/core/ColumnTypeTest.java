package org.databasebrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.databasebrowser.core.application.schema.SchemaService;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.domain.Changes.AddColumn;
import org.databasebrowser.core.domain.Changes.CreateTable;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Column;
import org.databasebrowser.core.domain.Schema.DataType;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Schema.Table;
import org.junit.jupiter.api.Test;

class ColumnTypeTest {
    @Test
    void passesAnUndeclaredTypeToTheAdapter() {
        var adapter = new CoreUseCasesTest.Fake();
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var schema = new SchemaService(sessions, (target, text, options) -> {});
            SessionId session = connect(sessions);
            var request = new CreateTable(tableWithType(session, "draft", DataType.undeclared()));

            var preparation = schema.prepareChange(session, request);

            assertTrue(preparation.problems().isEmpty());
            assertEquals(request, adapter.prepared.preview().request());
            assertFalse(request.table().columns().get(0).type().declared());
        }
    }

    @Test
    void keepsValidatingAnExplicitTypeDeclaration() {
        var adapter = new CoreUseCasesTest.Fake();
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var schema = new SchemaService(sessions, (target, text, options) -> {});
            SessionId session = connect(sessions);
            var blank = new DataType("", List.of(), Optional.empty(), false);
            var request = new CreateTable(tableWithType(session, "draft", blank));

            var preparation = schema.prepareChange(session, request);

            assertTrue(preparation.plan().isEmpty());
            assertEquals("table.columns[0].type.name", preparation.problems().get(0).path());
            assertEquals(0, adapter.prepares);
        }
    }

    @Test
    void canEditAnExistingTableWithAnUndeclaredColumnType() {
        var adapter = new CoreUseCasesTest.Fake() {
            @Override
            public Snapshot readSchema(SessionId session) {
                return new Snapshot(session, id(), tokens.get(session), List.of(),
                    List.of(tableWithType(session, "existing", DataType.undeclared())),
                    CoreUseCasesTest.DEPS);
            }
        };
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var schema = new SchemaService(sessions, (target, text, options) -> {});
            SessionId session = connect(sessions);
            var typed = new DataType("TEXT", List.of(), Optional.empty(), true);
            var addition = new Column("note", typed, true, Optional.empty(), Map.of());

            var preparation = schema.prepareChange(session,
                new AddColumn(new ObjectId(session, "existing"), addition));

            assertTrue(preparation.plan().isPresent());
            assertEquals(1, adapter.prepares);
        }
    }

    private static SessionId connect(SessionRegistry sessions) {
        return sessions.connect(new Settings(CoreUseCasesTest.ADAPTER, "test", Map.of(), Map.of()))
            .id();
    }

    private static Table tableWithType(SessionId session, String name, DataType type) {
        Table table = CoreUseCasesTest.table(session, name);
        var column = new Column("id", type, false, Optional.empty(), Map.of());
        return new Table(table.id(), table.name(), List.of(column), table.constraints(),
            table.definition());
    }
}
