package org.databasebrowser.core.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Errors.Problem;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;

/**
 * Типы для выполнения SQL: параметры, строки результатов, пакеты и состояние курсора.
 * {@code Request} создаётся с заранее выделенным {@code OperationId}. Результат {@code Execution}
 * может содержать {@code ResultId} открытого курсора, владение которым закрепляется за приложением.
 */
public final class Sql {
    private Sql() {}

    /**
     * Закрытый базовый тип значения параметра или ячейки результата. Выбирайте конкретную запись
     * для сохранения SQL-типа: приведение к {@code Object} теряет различие между датой, строкой
     * и числом.
     */
    public sealed interface Value permits NullValue, TextValue, NumberValue, BinaryValue,
        BooleanValue, DateValue, TimeValue, TimestampValue, OffsetTimestampValue {}

    /**
     * Пустое значение {@code SQL NULL}. Используйте вместо {@code null} в {@code Row}
     * и {@code Parameter}.
     */
    public record NullValue() implements Value {}

    /**
     * Строковое значение. Конструктор отвергает {@code null}; используйте {@code NullValue}
     * для {@code SQL NULL}.
     */
    public record TextValue(String value) implements Value {
        public TextValue {
            Objects.requireNonNull(value);
        }
    }

    /**
     * Число с произвольной точностью. Используйте {@code BigDecimal}, чтобы не терять
     * разряды при чтении {@code DECIMAL}/{@code NUMERIC}.
     */
    public record NumberValue(BigDecimal value) implements Value {
        public NumberValue {
            Objects.requireNonNull(value);
        }
    }

    /** Логическое значение {@code true}/{@code false}. Для {@code SQL NULL} используйте {@code NullValue}. */
    public record BooleanValue(boolean value) implements Value {}

    /**
     * Массив байтов. Конструктор и {@code value()} копируют данные, поэтому запись можно
     * передавать между потоками без дополнительной защиты исходного массива от изменения.
     */
    public record BinaryValue(byte[] value) implements Value {
        public BinaryValue {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    /** Значение типа {@code DATE} без времени. Используйте {@code TimestampValue}, если время сохранено. */
    public record DateValue(LocalDate value) implements Value {
        public DateValue {
            Objects.requireNonNull(value);
        }
    }

    /** Значение типа {@code TIME} без даты. Хранится в {@code LocalTime}. */
    public record TimeValue(LocalTime value) implements Value {
        public TimeValue {
            Objects.requireNonNull(value);
        }
    }

    /**
     * Значение {@code TIMESTAMP} без смещения; используйте {@code OffsetTimestampValue},
     * если смещение известно.
     */
    public record TimestampValue(LocalDateTime value) implements Value {
        public TimestampValue {
            Objects.requireNonNull(value);
        }
    }

    /** Значение {@code TIMESTAMP WITH TIME ZONE} с сохранённым смещением {@code OffsetDateTime}. */
    public record OffsetTimestampValue(OffsetDateTime value) implements Value {
        public OffsetTimestampValue {
            Objects.requireNonNull(value);
        }
    }

    /**
     * Параметр запроса с позицией начиная с 1 и значением {@code Value}. {@code declaredType}
     * может быть пустым, если адаптер не смог вывести тип. Позиции должны быть уникальными
     * в пределах {@code Request}.
     */
    public record Parameter(int position, Optional<String> declaredType, Value value) {
        public Parameter {
            Objects.requireNonNull(value);
        }
    }

    /**
     * Настройки выполнения SQL: режим, политика транзакции, размер первой порции и таймаут.
     * Адаптер должен проверить поддержку {@code mode} и {@code transaction} через capabilities.
     */
    public record Options(ExecutionMode mode, TransactionPolicy transaction, int initialBatchSize,
        Duration timeout) {}
    /**
     * Запрос на выполнение SQL с параметрами и настройками. Идентификатор {@code operationId}
     * выделяется {@code ExecuteSql.newOperationId()} до {@code execute}/{@code explain} и уникален
     * в сессии; повторное использование приведёт к {@code CONFLICT}.
     */
    public record
        Request(OperationId operationId, String sql, List<Parameter> parameters, Options options) {
        public Request {
            parameters = List.copyOf(parameters);
        }
    }
    /**
     * Метаданные колонки результата. {@code nullable} может быть пустым, если адаптер
     * не знает допустимость {@code NULL}.
     */
    public record
        ResultColumn(String label, Optional<String> declaredType, Optional<Boolean> nullable) {}

    /**
     * Строка результата со значениями в порядке {@code ResultColumn}. Запись копирует
     * список и не допускает его изменения.
     */
    public record Row(List<Value> values) {
        public Row {
            values = List.copyOf(values);
        }
    }

    /**
     * Порция строк результата. {@code complete} означает, что курсор исчерпан и следующий
     * {@code fetchRows} вернёт пустую порцию. В {@code Execution.firstBatch} это уже прочитанные
     * строки; владелец курсора — приложение, которое вызывает {@code fetchRows} и {@code closeResult}.
     */
    public record Batch(List<Row> rows, boolean complete) {
        public Batch {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Метрики выполнения: затраченное время, число полученных строк и дополнительные счётчики.
     * {@code affectedRows} и {@code totalRows} пусты, если адаптер их не предоставляет.
     */
    public record Statistics(
        Duration elapsed, long receivedRows, OptionalLong affectedRows, OptionalLong totalRows) {}

    /**
     * Итог выполнения запроса. {@code OUTCOME_UNKNOWN} означает потерю связи: повторный
     * запуск с тем же {@code OperationId} запрещён.
     */
    public enum Status { SUCCEEDED, CANCELLED, FAILED, OUTCOME_UNKNOWN }

    /**
     * Результат выполнения SQL. При наличии {@code result} он содержит {@code ResultId} открытого
     * курсора; владелец курсора — приложение, которое должно вызвать {@code fetchRows} и затем
     * {@code closeResult} либо передать владение {@code Exports.RowSource}. {@code firstBatch} уже
     * прочитан и не должен запрашиваться повторно; {@code warnings} не меняют {@code status}.
     */
    public record Execution(Status status, Optional<ResultId> result, List<ResultColumn> columns,
        Batch firstBatch, Statistics statistics, List<Problem> warnings) {
        public Execution {
            columns = List.copyOf(columns);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Результат запроса отмены операции. {@code NOT_ACTIVE} означает, что операция
     * уже завершилась или неизвестна.
     */
    public enum CancelResult { REQUESTED, NOT_ACTIVE, UNSUPPORTED }
}
