package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
import org.databasebrowser.core.domain.Schema.Snapshot;

/** Preserve source dialect, explicitly warn about missing definitions/dependencies. */
public interface DdlExportPort {
  Ddl generateDdl(Snapshot schemaSnapshot, DdlOptions options);
}
