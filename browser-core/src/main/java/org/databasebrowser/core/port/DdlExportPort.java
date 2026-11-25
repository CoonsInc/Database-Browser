package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
import org.databasebrowser.core.domain.Schema.Snapshot;

/** Сохраняет исходный диалект и предупреждает о неполных определениях и зависимостях. */
public interface DdlExportPort {
    Ddl generateDdl(Snapshot schemaSnapshot, DdlOptions options);
}
