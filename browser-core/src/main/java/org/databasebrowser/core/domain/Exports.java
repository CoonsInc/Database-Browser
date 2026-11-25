package org.databasebrowser.core.domain;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import org.databasebrowser.core.domain.Errors.Problem;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Sql.Batch;
import org.databasebrowser.core.domain.Sql.ResultColumn;

public final class Exports {
    private Exports() {}

    public enum DependencyPolicy { INCLUDE, SELECTED_ONLY }
    public record
        DdlOptions(boolean wholeSchema, Set<ObjectId> selected, DependencyPolicy dependencies) {
        public DdlOptions {
            selected = Set.copyOf(selected);
        }
    }

    public record Ddl(String sql, List<Problem> warnings, boolean independentlyRestorable) {
        public Ddl {
            warnings = List.copyOf(warnings);
        }
    }
    /** Адрес назначения, который интерпретирует файловый адаптер. */

    public record Target(String value) {
        public Target {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("target");
            }
        }
    }

    public record TextOptions(Charset charset, boolean overwrite) {}

    public enum Escaping { MINIMAL, ALL }

    public enum RowScope { FULL_SOURCE, SELECTED_BATCH, REMAINING }

    public record CsvOptions(boolean headers, char delimiter, Charset charset, String nullText,
        Escaping escaping, int batchSize) {}

    public record CsvResult(long rowsWritten) {}
    @FunctionalInterface

    public interface Cancellation {
        boolean requested();
    }
    /**
     * CsvService закрывает источник даже при ошибке проверки. Размер порции ограничен batchSize.
     * FULL_SOURCE содержит полный результат, REMAINING — остаток прочитанного курсора.
     * Файловый адаптер проверяет отмену и сохраняет типы по правилам форматирования.
     */

    public interface RowSource extends AutoCloseable {
        List<ResultColumn> columns();

        RowScope scope();

        Batch fetch(int batchSize);

        @Override void close();
    }
}
