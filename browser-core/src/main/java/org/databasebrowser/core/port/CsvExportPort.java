package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Cancellation;
import org.databasebrowser.core.domain.Exports.CsvOptions;
import org.databasebrowser.core.domain.Exports.CsvResult;
import org.databasebrowser.core.domain.Exports.RowSource;
import org.databasebrowser.core.domain.Exports.Target;

/**
 * Pull batches from source; poll cancellation throughout writing. Never buffer entire source.
 * Throw categorized IO/CANCELLED errors. Caller owns closing source.
 */
public interface CsvExportPort {
    CsvResult exportCsv(
        RowSource rowSource, Target target, CsvOptions options, Cancellation cancellation);
}
