package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Cancellation;
import org.databasebrowser.core.domain.Exports.CsvOptions;
import org.databasebrowser.core.domain.Exports.CsvResult;
import org.databasebrowser.core.domain.Exports.RowSource;
import org.databasebrowser.core.domain.Exports.Target;

/**
 * Пишет порциями без буферизации всех строк и проверяет отмену.
 * Ошибки имеют код IO или CANCELLED. Источник закрывает вызывающая сторона.
 */
public interface CsvExportPort {
    CsvResult exportCsv(
        RowSource rowSource, Target target, CsvOptions options, Cancellation cancellation);
}
