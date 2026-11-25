package org.databasebrowser.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.databasebrowser.core.application.ChangeValidator;
import org.databasebrowser.core.application.schema.SchemaService;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.domain.Changes.ApplyStatus;
import org.databasebrowser.core.domain.Changes.CreateIndex;
import org.databasebrowser.core.domain.Changes.CreateTable;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.Problem;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Definition;
import org.databasebrowser.core.domain.Schema.Direction;
import org.databasebrowser.core.domain.Schema.Index;
import org.databasebrowser.core.domain.Schema.IndexTerm;
import org.databasebrowser.core.domain.Schema.QualifiedName;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Schema.Unique;
import org.junit.jupiter.api.Test;

class SchemaRegressionTest {
    @Test
    void schemaServiceUsesRegistryAndConsumesPlanOnce() {
        var adapter = new CoreUseCasesTest.Fake();
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var schema = new SchemaService(sessions, (target, text, options) -> {});
            SessionId first = sessions.connect(
                new Settings(adapter.id(), "first", Map.of(), Map.of())).id();
            SessionId second = sessions.connect(
                new Settings(adapter.id(), "second", Map.of(), Map.of())).id();
            assertEquals(1, schema.supportedTypes(first).size());

            var preview = schema.prepareChange(first,
                new CreateTable(CoreUseCasesTest.table(first, "draft")));
            var planId = preview.plan().orElseThrow().id();
            CoreUseCasesTest.code(Code.WRONG_SESSION, () -> schema.applyChange(second, planId));
            assertEquals(ApplyStatus.APPLIED, schema.applyChange(first, planId).status());
            CoreUseCasesTest.code(Code.ALREADY_APPLIED, () -> schema.applyChange(first, planId));

            sessions.disconnect(first);
            CoreUseCasesTest.code(Code.UNKNOWN_SESSION, () -> schema.readSchema(first));
            assertEquals(second, schema.readSchema(second).session());
        }
    }

    @Test
    void duplicateConstraintPathUsesOriginalRowIndex() {
        Table draft = CoreUseCasesTest.table(new SessionId(java.util.UUID.randomUUID()), "draft");
        Table table = new Table(draft.id(), draft.name(), draft.columns(),
            List.of(new Unique(Optional.empty(), List.of("id")),
                new Unique(Optional.of("same"), List.of("id")),
                new Unique(Optional.of("SAME"), List.of("id"))),
            draft.definition());

        List<Problem> problems =
            new ChangeValidator(String::equalsIgnoreCase).validate(new CreateTable(table));

        assertEquals(1, problems.size());
        assertEquals("duplicate", problems.get(0).code());
        assertEquals("table.constraints[2]", problems.get(0).path());
    }

    @Test
    void delegatesIndexCreationOnTableWithReadOnlyDefinition() {
        CoreUseCasesTest.Fake adapter = new CoreUseCasesTest.Fake() {
            @Override
            public Snapshot readSchema(SessionId session) {
                Table table = CoreUseCasesTest.table(session, "existing");
                Definition definition = new Definition(Optional.of("source"), Map.of(), false);
                Table readOnly = new Table(table.id(), table.name(), table.columns(),
                    table.constraints(), definition);
                return new Snapshot(session, id(), tokens.get(session), List.of(), List.of(readOnly),
                    CoreUseCasesTest.DEPS);
            }
        };
        try (var sessions = new SessionRegistry(List.of(adapter))) {
            var schema = new SchemaService(sessions, (target, text, options) -> {});
            SessionId session = sessions.connect(
                new Settings(adapter.id(), "db", Map.of(), Map.of())).id();
            var index = new Index(new ObjectId(session, "new-index"),
                new QualifiedName(Optional.empty(), Optional.empty(), "new_index"),
                new ObjectId(session, "existing"), false,
                List.of(new IndexTerm(Optional.of("id"), Optional.empty(), Direction.ASC,
                    Optional.empty())), Optional.empty(), CoreUseCasesTest.DEFINITION);
            var prepared = schema.prepareChange(session, new CreateIndex(index));
            assertTrue(prepared.plan().isPresent());
            assertEquals(1, adapter.prepares);
        }
    }
}
