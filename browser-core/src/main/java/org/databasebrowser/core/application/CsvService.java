package org.databasebrowser.core.application;

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

/** Выполняет сценарий экспорта строк в CSV. */
public final class CsvService {
    private final CsvExportPort port;

    /** Подключает файловый порт для записи CSV. */
    public CsvService(CsvExportPort port) {
        this.port = Objects.requireNonNull(port);
    }

    /** Проверяет область экспорта, передаёт строки на запись и закрывает источник. */
    public CsvResult export(RowSource source, RowScope requested, Target target, CsvOptions options,
        Cancellation cancellation) {
        Objects.requireNonNull(source);
        try (source) {
            if (requested == null || source.scope() != requested) {
                throw error(Code.UNSUPPORTED, "Source scope differs from requested export");
            }

            validateOptions(target, options, cancellation);
            checkCancellation(cancellation);

            CsvResult result = port.exportCsv(
                bounded(source, options.batchSize(), cancellation), target, options,
                cancellation);

            checkCancellation(cancellation);
            return result;
        } catch (CoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw error(Code.INTERNAL, "CSV adapter or source contract failure; diagnostics withheld");
        }
    }

    /** Создаёт источник из уже загруженной порции строк. */
    public static RowSource selectedBatch(List<ResultColumn> columns, Batch batch) {
        List<ResultColumn> metadata = List.copyOf(columns);
        List<Row> rows = batch.rows();
        return new RowSource() {
            private int offset;
            private boolean closed;

            @Override
            public List<ResultColumn> columns() {
                return metadata;
            }

            @Override
            public RowScope scope() {
                return RowScope.SELECTED_BATCH;
            }

            @Override
            public Batch fetch(int size) {
                if (closed) {
                    throw error(Code.NOT_FOUND, "Source closed");
                }

                checkSize(size);
                int end = offset + Math.min(size, rows.size() - offset);
                Batch next = new Batch(rows.subList(offset, end), end == rows.size());
                offset = end;
                return next;
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    /** Ограничивает порции для файлового адаптера и проверяет отмену. */
    private static RowSource bounded(RowSource source, int limit, Cancellation cancellation) {
        return new RowSource() {
            @Override
            public List<ResultColumn> columns() {
                return List.copyOf(source.columns());
            }

            @Override
            public RowScope scope() {
                return source.scope();
            }

            @Override
            public Batch fetch(int size) {
                checkCancellation(cancellation);
                checkSize(size);
                int requested = Math.min(size, limit);
                Batch batch = source.fetch(requested);
                if (batch.rows().size() > requested) {
                    throw error(Code.INTERNAL, "Source exceeded requested batch size");
                }

                return batch;
            }

            @Override
            public void close() {
                // Источник закрывает CsvService.
            }
        };
    }

    /** Проверяет настройки CSV до начала записи. */
    private static void validateOptions(Target target, CsvOptions options, Cancellation cancellation) {
        if (target == null || options == null || options.charset() == null
            || options.escaping() == null || options.nullText() == null || options.batchSize() < 1
            || options.delimiter() == '\r' || options.delimiter() == '\n'
            || options.delimiter() == '"' || options.delimiter() == '\0') {
            throw error(Code.INVALID_INPUT, "Invalid CSV options");
        }
        if (cancellation == null) {
            throw error(Code.INVALID_INPUT, "Cancellation token is required");
        }
    }

    /** Останавливает экспорт, если запрошена отмена. */
    private static void checkCancellation(Cancellation cancellation) {
        if (cancellation.requested()) {
            throw error(Code.CANCELLED, "CSV export cancelled");
        }
    }

    /** Требует положительный размер порции. */
    private static void checkSize(int size) {
        if (size < 1) {
            throw error(Code.INVALID_INPUT, "Invalid fetch size");
        }
    }
}
