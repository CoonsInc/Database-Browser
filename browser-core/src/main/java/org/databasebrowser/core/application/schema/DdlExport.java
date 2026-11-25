package org.databasebrowser.core.application.schema;

import static org.databasebrowser.core.application.AdapterCalls.safelyRun;
import static org.databasebrowser.core.application.schema.SchemaService.checkOwner;
import static org.databasebrowser.core.application.schema.SchemaService.snapshot;
import static org.databasebrowser.core.domain.Errors.error;

import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Exports.TextOptions;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.port.TextFilePort;

/** Формирует DDL из снимка и сохраняет показанный SQL. */
final class DdlExport {
    private final SessionRegistry sessions;
    private final TextFilePort textFiles;

    /** Использует открытые сессии и порт записи файлов. */
    DdlExport(SessionRegistry sessions, TextFilePort textFiles) {
        this.sessions = sessions;
        this.textFiles = textFiles;
    }

    /** Готовит SQL для выбранных объектов или всей схемы. */
    Ddl previewDdl(SessionId id, DdlOptions options) {
        return sessions.work(id, state -> {
            if (options == null || options.dependencies() == null
                || options.wholeSchema() != options.selected().isEmpty()) {
                throw error(Code.INVALID_INPUT, "Select whole schema or a nonempty object set");
            }

            Snapshot snapshot = snapshot(state);
            for (ObjectId object : options.selected()) {
                checkOwner(state.session().id(), object);
                if (snapshot.objects().stream().noneMatch(candidate -> candidate.id().equals(object))) {
                    throw error(Code.NOT_FOUND, "Selected export object not found");
                }
            }

            return state.ports().ddl().generateDdl(snapshot, options);
        });
    }

    /** Готовит актуальный DDL и сохраняет его. */
    Ddl saveDdl(SessionId id, DdlOptions options, Target target, TextOptions textOptions) {
        Ddl preview = previewDdl(id, options);
        saveDdlPreview(preview, target, textOptions);
        return preview;
    }

    /** Сохраняет текст уже показанного предпросмотра. */
    void saveDdlPreview(Ddl preview, Target target, TextOptions options) {
        if (preview == null || target == null || options == null || options.charset() == null) {
            throw error(Code.INVALID_INPUT, "DDL, target and text options are required");
        }
        safelyRun(() -> textFiles.writeText(target, preview.sql(), options));
    }
}
