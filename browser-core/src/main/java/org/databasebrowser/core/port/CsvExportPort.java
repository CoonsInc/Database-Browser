package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Cancellation;
import org.databasebrowser.core.domain.Exports.CsvOptions;
import org.databasebrowser.core.domain.Exports.CsvResult;
import org.databasebrowser.core.domain.Exports.RowSource;
import org.databasebrowser.core.domain.Exports.Target;

/**
 * Это не БД-порт: он входит в {@code ExportPorts} и вызывается сценарием {@code ExportCsv}.
 * Источник {@link RowSource} уже проверен и ограничен сервисом: адаптер читает его только
 * батчами, не буферизует целиком и не закрывает самостоятельно. Адаптер обязан опрашивать
 * {@link Cancellation} в процессе записи и сообщать об ошибках ввода-вывода и отмене
 * через категории ошибок ядра.
 */
public interface CsvExportPort {
    /**
     * Потоково выгружает строки из {@link RowSource} в {@link Target}.
     *
     * @param rowSource источник батчей, закрытие которого остаётся за вызывающей стороной
     * @param target непрозрачное назначение, интерпретируемое IO-адаптером
     * @param options разделитель, кодировка, заголовки и размер батча
     * @param cancellation токен отмены, который нужно опрашивать при записи
     * @return число записанных строк
     */
    CsvResult exportCsv(
        RowSource rowSource, Target target, CsvOptions options, Cancellation cancellation);
}
