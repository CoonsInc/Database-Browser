package org.databasebrowser.core.application;
import static org.databasebrowser.core.domain.Errors.error;

import java.util.*;
import org.databasebrowser.core.domain.Errors.*;
import org.databasebrowser.core.domain.Exports.*;
import org.databasebrowser.core.domain.Sql.*;
import org.databasebrowser.core.port.CsvExportPort;
/** Takes ownership immediately, including on invalid requests. */
public final class CsvService {
  private final CsvExportPort port;
  public CsvService(CsvExportPort port) {
    this.port = Objects.requireNonNull(port);
  }
  public CsvResult export(
      RowSource source, RowScope requested, Target target, CsvOptions options, Cancellation cancellation) {
    Objects.requireNonNull(source);
    try (source) {
      if (requested == null || source.scope() != requested)
        throw error(Code.UNSUPPORTED,
            "Source scope differs from requested export; full results require explicit re-execution");
      if (target == null || options == null || options.charset() == null || options.escaping() == null
          || options.nullText() == null || options.batchSize() < 1 || options.delimiter() == '\r'
          || options.delimiter() == '\n' || options.delimiter() == '"' || options.delimiter() == '\0')
        throw error(Code.INVALID_INPUT, "Invalid CSV options");
      if (cancellation == null)
        throw error(Code.INVALID_INPUT, "Cancellation token is required");
      if (cancellation.requested())
        throw error(Code.CANCELLED, "CSV export cancelled");
      RowSource bounded = new RowSource() {
        public List<ResultColumn> columns() {
          return List.copyOf(source.columns());
        }
        public RowScope scope() {
          return source.scope();
        }
        public Batch fetch(int size) {
          if (cancellation.requested())
            throw error(Code.CANCELLED, "CSV export cancelled");
          if (size < 1)
            throw error(Code.INVALID_INPUT, "Invalid fetch size");
          int limit = Math.min(size, options.batchSize());
          Batch b = source.fetch(limit);
          if (b.rows().size() > limit)
            throw error(Code.INTERNAL, "Source exceeded requested batch size");
          return b;
        }
        public void close() {} // owner is closed by the service
      };
      CsvResult result = port.exportCsv(bounded, target, options, cancellation);
      if (cancellation.requested())
        throw error(Code.CANCELLED, "CSV export cancelled");
      return result;
    } catch (CoreException e) {
      throw e;
    } catch (RuntimeException e) {
      throw error(Code.INTERNAL, "CSV adapter or source contract failure; diagnostics withheld");
    }
  }
  public static RowSource selectedBatch(List<ResultColumn> columns, Batch batch) {
    List<ResultColumn> metadata = List.copyOf(columns);
    List<Row> rows = List.copyOf(batch.rows());
    return new RowSource() {
      int offset;
      boolean closed;
      public List<ResultColumn> columns() {
        return metadata;
      }
      public RowScope scope() {
        return RowScope.SELECTED_BATCH;
      }
      public Batch fetch(int size) {
        if (closed)
          throw error(Code.NOT_FOUND, "Source closed");
        if (size < 1)
          throw error(Code.INVALID_INPUT, "Invalid fetch size");
        int end = (int) Math.min(rows.size(), (long) offset + size);
        Batch b = new Batch(rows.subList(offset, end), end == rows.size());
        offset = end;
        return b;
      }
      public void close() {
        closed = true;
      }
    };
  }
}
