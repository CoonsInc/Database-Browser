package org.databasebrowser.core.domain;
import java.math.*;
import java.time.*;
import java.util.*;
import org.databasebrowser.core.domain.Connections.*;
import org.databasebrowser.core.domain.Errors.*;
import org.databasebrowser.core.domain.Ids.*;
public final class Sql {
  private Sql() {}
  public sealed interface Value permits NullValue, TextValue, NumberValue, BinaryValue, BooleanValue,
      DateValue, TimeValue, TimestampValue, OffsetTimestampValue {}
  public record NullValue() implements Value {}
  public record TextValue(String value) implements Value {
    public TextValue {
      Objects.requireNonNull(value);
    }
  }
  public record NumberValue(BigDecimal value) implements Value {
    public NumberValue {
      Objects.requireNonNull(value);
    }
  }
  public record BooleanValue(boolean value) implements Value {}
  public record BinaryValue(byte[] value) implements Value {
    public BinaryValue {
      value = value.clone();
    }
    @Override
    public byte[] value() {
      return value.clone();
    }
  }
  public record DateValue(LocalDate value) implements Value {
    public DateValue {
      Objects.requireNonNull(value);
    }
  }
  public record TimeValue(LocalTime value) implements Value {
    public TimeValue {
      Objects.requireNonNull(value);
    }
  }
  public record TimestampValue(LocalDateTime value) implements Value {
    public TimestampValue {
      Objects.requireNonNull(value);
    }
  }
  public record OffsetTimestampValue(OffsetDateTime value) implements Value {
    public OffsetTimestampValue {
      Objects.requireNonNull(value);
    }
  }
  public record Parameter(int position, Optional<String> declaredType, Value value) {
    public Parameter {
      Objects.requireNonNull(value);
    }
  }
  public record
      Options(ExecutionMode mode, TransactionPolicy transaction, int initialBatchSize, Duration timeout) {}
  /** operationId is allocated by BrowserService.newOperationId before execute/explain. */
  public record Request(OperationId operationId, String sql, List<Parameter> parameters, Options options) {
    public Request {
      parameters = List.copyOf(parameters);
    }
  }
  public record ResultColumn(String label, Optional<String> declaredType, Optional<Boolean> nullable) {}
  public record Row(List<Value> values) {
    public Row {
      values = List.copyOf(values);
    }
  }
  public record Batch(List<Row> rows, boolean complete) {
    public Batch {
      rows = List.copyOf(rows);
    }
  }
  public record
      Statistics(Duration elapsed, long receivedRows, OptionalLong affectedRows, OptionalLong totalRows) {}
  public enum Status { SUCCEEDED, CANCELLED, FAILED, OUTCOME_UNKNOWN }
  public record Execution(Status status, Optional<ResultId> result, List<ResultColumn> columns,
      Batch firstBatch, Statistics statistics, List<Problem> warnings) {
    public Execution {
      columns = List.copyOf(columns);
      warnings = List.copyOf(warnings);
    }
  }
  public enum CancelResult { REQUESTED, NOT_ACTIVE, UNSUPPORTED }
}
