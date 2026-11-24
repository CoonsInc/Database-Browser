package org.databasebrowser.core.domain;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import org.databasebrowser.core.domain.Errors.Problem;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Sql.Batch;
import org.databasebrowser.core.domain.Sql.ResultColumn;

/**
 * Типы экспорта DDL и CSV. {@code RowSource} передаёт владение курсором в {@code ExportCsv},
 * даже если запрос отклонён; {@code Cancellation} позволяет прервать адаптер во время записи.
 */
public final class Exports {
    private Exports() {}

    /**
     * Политика включения зависимых объектов в DDL. {@code SELECTED_ONLY} экспортирует только
     * явно выбранные объекты и может дать несамостоятельный результат.
     */
    public enum DependencyPolicy { INCLUDE, SELECTED_ONLY }
    /**
     * Настройки экспорта DDL: вся схема или множество {@code selected} и политика зависимостей.
     * Множество копируется в конструкторе. Пустое {@code selected} имеет смысл только при
     * {@code wholeSchema == true}.
     */
    public record
        DdlOptions(boolean wholeSchema, Set<ObjectId> selected, DependencyPolicy dependencies) {
        public DdlOptions {
            selected = Set.copyOf(selected);
        }
    }

    /**
     * Сгенерированный DDL-скрипт. {@code independentlyRestorable} показывает, можно ли
     * восстановить объекты без внешних зависимостей. {@code warnings} не блокируют экспорт.
     */
    public record Ddl(String sql, List<Problem> warnings, boolean independentlyRestorable) {
        public Ddl {
            warnings = List.copyOf(warnings);
        }
    }
    /**
     * Непрозрачный адрес назначения: путь, идентификатор хранилища или другое значение,
     * понятное IO-адаптеру. Приложение не интерпретирует {@code value} самостоятельно.
     */

    public record Target(String value) {
        public Target {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("target");
        }
    }

    /** Настройки текстового экспорта: кодировка {@code charset} и признак перезаписи {@code overwrite}. */
    public record TextOptions(Charset charset, boolean overwrite) {}

    /** Стиль экранирования CSV: {@code MINIMAL} только при необходимости, {@code ALL} для всех значений. */
    public enum Escaping { MINIMAL, ALL }

    /**
     * Область строк, которую отдаёт {@code RowSource}. {@code FULL_SOURCE} требует свежий полный
     * курсор; {@code SELECTED_BATCH} — уже прочитанную порцию; {@code REMAINING} — остаток курсора
     * после частичного чтения. Запрашиваемая область должна совпадать со {@code RowSource.scope()}.
     */
    public enum RowScope { FULL_SOURCE, SELECTED_BATCH, REMAINING }

    /**
     * Настройки CSV: заголовки, разделитель, кодировка, текст для {@code NULL}, экранирование и
     * максимальный размер порции. {@code batchSize} должен быть положительным; адаптер не должен
     * запрашивать у источника больше строк, чем это значение.
     */
    public record CsvOptions(boolean headers, char delimiter, Charset charset, String nullText,
        Escaping escaping, int batchSize) {}

    /** Итог экспорта CSV — число записанных строк {@code rowsWritten}. */
    public record CsvResult(long rowsWritten) {}
    /**
     * Токен отмены, который адаптер обязан опрашивать во время экспорта. Метод
     * {@code requested()} должен быть потокобезопасным; возврат {@code true} прерывает запись
     * с {@code CANCELLED}.
     */
    @FunctionalInterface

    public interface Cancellation {
        boolean requested();
    }
    /**
     * Источник строк для CSV: {@code columns()} задаёт порядок полей, {@code fetch()} читает
     * порции не более {@code batchSize} строк, {@code close()} освобождает курсор. Владение сразу
     * переходит к {@code ExportCsv}, в том числе при отклонении параметров.
     * {@link RowScope#FULL_SOURCE} требует свежий полный источник; частично прочитанный курсор
     * должен сообщать {@link RowScope#REMAINING}. Адаптер IO обязан опрашивать {@link Cancellation},
     * сохранять типы через описанное форматирование и не должен закрывать источник сам.
     */

    public interface RowSource extends AutoCloseable {
        List<ResultColumn> columns();

        RowScope scope();

        Batch fetch(int batchSize);

        @Override void close();
    }
}
