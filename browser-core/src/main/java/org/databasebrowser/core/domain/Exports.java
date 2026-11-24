package org.databasebrowser.core.domain;
import java.nio.charset.Charset;
import java.util.*;
import org.databasebrowser.core.domain.Errors.*;
import org.databasebrowser.core.domain.Ids.*;
import org.databasebrowser.core.domain.Sql.*;
public final class Exports {
  private Exports() {}
  public enum DependencyPolicy { INCLUDE, SELECTED_ONLY }
  public record DdlOptions(boolean wholeSchema, Set<ObjectId> selected, DependencyPolicy dependencies) {
    public DdlOptions {
      selected = Set.copyOf(selected);
    }
  }
  public record Ddl(String sql, List<Problem> warnings, boolean independentlyRestorable) {
    public Ddl {
      warnings = List.copyOf(warnings);
    }
  }
  /** Opaque destination interpreted by IO adapter, e.g. a path or storage handle. */
  public record Target(String value) {
    public Target {
      if (value == null || value.isBlank())
        throw new IllegalArgumentException("target");
    }
  }
  public record TextOptions(Charset charset, boolean overwrite) {}
  public enum Escaping { MINIMAL, ALL }
  public enum RowScope { FULL_SOURCE, SELECTED_BATCH, REMAINING }
  public record CsvOptions(
      boolean headers, char delimiter, Charset charset, String nullText, Escaping escaping, int batchSize) {}
  public record CsvResult(long rowsWritten) {}
  @FunctionalInterface
  public interface Cancellation {
    boolean requested();
  }
  /**
   * Ownership transfers to CsvService even on validation failure. fetch must be bounded by batchSize.
   * FULL_SOURCE asserts a fresh complete source; a partially read cursor must report REMAINING.
   * IO must poll cancellation, preserve types via documented formatting, and never close this source itself.
   */
  public interface RowSource extends AutoCloseable {
    List<ResultColumn> columns();
    RowScope scope();
    Batch fetch(int batchSize);
    @Override void close();
  }
}
