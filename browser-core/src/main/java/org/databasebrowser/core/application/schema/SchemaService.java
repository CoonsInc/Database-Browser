package org.databasebrowser.core.application.schema;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.List;
import java.util.Objects;
import org.databasebrowser.core.application.session.SessionRegistry.SessionState;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.Preparation;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Connections.SupportedType;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Exports.TextOptions;
import org.databasebrowser.core.domain.Ids.ChangePlanId;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Definition;
import org.databasebrowser.core.domain.Schema.Dependencies;
import org.databasebrowser.core.domain.Schema.SchemaObject;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.port.TextFilePort;

/** Читает схему и предоставляет сценарии изменений и экспорта. */
public final class SchemaService {
    private final SessionRegistry sessions;
    private final SchemaChanges changes;
    private final DdlExport ddl;

    /** Связывает операции со схемой с общим реестром подключений. */
    public SchemaService(SessionRegistry sessions, TextFilePort textFiles) {
        this.sessions = Objects.requireNonNull(sessions);
        changes = new SchemaChanges(sessions);
        ddl = new DdlExport(sessions, Objects.requireNonNull(textFiles));
        this.sessions.onSessionClosed(changes::releaseSession);
    }

    /** Получает типы для редактора колонок выбранной сессии. */
    public List<SupportedType> supportedTypes(SessionId id) {
        return sessions.work(id, state -> List.copyOf(state.ports().info().getSupportedTypes(id)));
    }

    /** Читает согласованный снимок схемы. */
    public Snapshot readSchema(SessionId id) {
        return sessions.work(id, SchemaService::snapshot);
    }

    /** Возвращает определение объекта своей сессии. */
    public Definition readObjectDefinition(SessionId id, ObjectId object) {
        checkOwner(id, object);
        return sessions.work(id, state -> state.ports().schema().readObjectDefinition(id, object));
    }

    /** Находит связи объекта с остальной схемой. */
    public Dependencies findDependencies(SessionId id, ObjectId object) {
        checkOwner(id, object);
        return sessions.work(id, state -> state.ports().schema().findDependencies(id, object));
    }

    /** Готовит план изменения для предпросмотра. */
    public Preparation prepareChange(SessionId id, Request request) {
        return changes.prepareChange(id, request);
    }

    /** Применяет ранее подготовленный план. */
    public ApplyResult applyChange(SessionId id, ChangePlanId planId) {
        return changes.applyChange(id, planId);
    }

    /** Готовит предпросмотр DDL. */
    public Ddl previewDdl(SessionId id, DdlOptions options) {
        return ddl.previewDdl(id, options);
    }

    /** Готовит актуальный DDL и записывает его. */
    public Ddl saveDdl(SessionId id, DdlOptions options, Target target, TextOptions textOptions) {
        return ddl.saveDdl(id, options, target, textOptions);
    }

    /** Сохраняет текст показанного предпросмотра. */
    public void saveDdlPreview(Ddl preview, Target target, TextOptions options) {
        ddl.saveDdlPreview(preview, target, options);
    }

    /** Проверяет принадлежность объекта сессии. */
    static void checkOwner(SessionId session, ObjectId object) {
        if (object == null) {
            throw error(Code.INVALID_INPUT, "Object is required");
        }
        if (!object.session().equals(session)) {
            throw error(Code.WRONG_SESSION, "Object belongs to another session");
        }
    }

    /** Проверяет сессию и диалект полученного снимка. */
    static Snapshot snapshot(SessionState state) {
        Snapshot snapshot = state.ports().schema().readSchema(state.session().id());
        if (!snapshot.session().equals(state.session().id())
            || !snapshot.dialect().equals(state.session().info().dialect())) {
            throw error(Code.INTERNAL, "Invalid snapshot provenance");
        }

        for (SchemaObject object : snapshot.objects()) {
            checkOwner(state.session().id(), object.id());
        }
        return snapshot;
    }
}
