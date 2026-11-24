package org.databasebrowser.core.application.usecase;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.List;
import java.util.Objects;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.CoreException;
import org.databasebrowser.core.domain.Exports.Cancellation;
import org.databasebrowser.core.domain.Exports.CsvOptions;
import org.databasebrowser.core.domain.Exports.CsvResult;
import org.databasebrowser.core.domain.Exports.RowScope;
import org.databasebrowser.core.domain.Exports.RowSource;
import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Sql.Batch;
import org.databasebrowser.core.domain.Sql.ResultColumn;
import org.databasebrowser.core.domain.Sql.Row;
import org.databasebrowser.core.port.CsvExportPort;

/**
 * Юзкейс экспортирует результат запроса в CSV через порт {@link CsvExportPort}.
 * Источник строк передаётся во владение сразу: {@code export} закроет его даже при некорректном запросе.
 * Используется как {@code Usecases.csv()}; готовый пакет строк превращают в источник через {@link #selectedBatch(List, Batch)}.
 */
public final class ExportCsv {
    private final CsvExportPort port;

    /** Создаёт юзкейс с портом экспорта CSV; {@code null} запрещён. */
    public ExportCsv(CsvExportPort port) {
        this.port = Objects.requireNonNull(port);
    }

    /**
     * Экспортирует {@code source} в {@code target} и возвращает {@link CsvResult}.
     * Принимает владение {@code source} сразу и закрывает его при любом исходе, включая ошибку валидации.
     * Требует совпадения {@code requested} с областью источника, корректных {@code options} и токена {@code cancellation}.
     * Ошибки адаптера или нарушения контракта источника скрываются как {@link Code#INTERNAL}.
     */
    public CsvResult export(RowSource source, RowScope requested, Target target, CsvOptions options,
        Cancellation cancellation) {
        Objects.requireNonNull(source);
        try (source) {
            if (requested == null || source.scope() != requested)
                throw error(Code.UNSUPPORTED,
                    "Source scope differs from requested export; full results require explicit "
                    + "re-execution");
            if (target == null || options == null || options.charset() == null
                || options.escaping() == null || options.nullText() == null
                || options.batchSize() < 1 || options.delimiter() == '\r'
                || options.delimiter() == '\n' || options.delimiter() == '"'
                || options.delimiter() == '\0')
                throw error(Code.INVALID_INPUT, "Invalid CSV options");
            if (cancellation == null)
                throw error(Code.INVALID_INPUT, "Cancellation token is required");
            if (cancellation.requested())
                throw error(Code.CANCELLED, "CSV export cancelled");
            RowSource bounded = new RowSource() {
                /** Возвращает копию колонок исходного результата. */
                public List<ResultColumn> columns() {
                    return List.copyOf(source.columns());
                }

                /** Возвращает область исходного {@code source}. */
                public RowScope scope() {
                    return source.scope();
                }

                /**
                 * Проверяет отмену и положительный размер, затем читает не более {@code size} строк.
                 * Адаптер не может вернуть больше строк, чем запрошено.
                 */
                public Batch fetch(int size) {
                    if (cancellation.requested())
                        throw error(Code.CANCELLED, "CSV export cancelled");
                    if (size < 1)
                        throw error(Code.INVALID_INPUT, "Fetch size must be positive");
                    int limit = Math.min(size, options.batchSize());
                    Batch b = source.fetch(limit);
                    if (b.rows().size() > limit)
                        throw error(Code.INTERNAL, "Source exceeded requested batch size");
                    return b;
                }

                /** Пусто: владелец {@code source} закрывается сервисом {@code export}. */
                public void close() {} // owner is closed by the service
            };
            CsvResult result = port.exportCsv(bounded, target, options, cancellation);
            if (cancellation.requested())
                throw error(Code.CANCELLED, "CSV export cancelled");
            return result;
        } catch (CoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw error(
                Code.INTERNAL, "CSV adapter or source contract failure; diagnostics withheld");
        }
    }

    /**
     * Создаёт {@link RowSource} поверх уже загруженного {@code batch} с областью {@link RowScope#SELECTED_BATCH}.
     * Возвращает копии колонок и строк и выдаёт их постранично без обращения к БД.
     * После {@link RowSource#close()} выбор недоступен: повторный {@code fetch} даёт {@link Code#NOT_FOUND}.
     */
    public static RowSource selectedBatch(List<ResultColumn> columns, Batch batch) {
        List<ResultColumn> metadata = List.copyOf(columns);
        List<Row> rows = List.copyOf(batch.rows());
        return new RowSource() {
            int offset;
            boolean closed;

            /** Возвращает неизменяемые колонки выборки. */
            public List<ResultColumn> columns() {
                return metadata;
            }

            /** Всегда сообщает {@link RowScope#SELECTED_BATCH}. */
            public RowScope scope() {
                return RowScope.SELECTED_BATCH;
            }

            /** Выдаёт следующий поддиапазон строк и флаг завершения; после закрытия недоступен. */
            public Batch fetch(int size) {
                if (closed)
                    throw error(Code.NOT_FOUND, "Source closed");
                if (size < 1)
                    throw error(Code.INVALID_INPUT, "Fetch size must be positive");
                int end = (int) Math.min(rows.size(), (long) offset + size);
                Batch b = new Batch(rows.subList(offset, end), end == rows.size());
                offset = end;
                return b;
            }

            /** Помечает источник закрытым; повторный вызов ничего не меняет. */
            public void close() {
                closed = true;
            }
        };
    }
}
