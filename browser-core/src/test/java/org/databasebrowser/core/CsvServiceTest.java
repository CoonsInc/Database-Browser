package org.databasebrowser.core;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.databasebrowser.core.application.*;
import org.databasebrowser.core.domain.Errors.*;
import org.databasebrowser.core.domain.Exports.*;
import org.databasebrowser.core.domain.Sql.*;
import org.junit.jupiter.api.*;
class CsvServiceTest {
  static class Source implements RowSource {
    int reads, closed, remaining = 5;
    public List<ResultColumn> columns() {
      return List.of();
    }
    public RowScope scope() {
      return RowScope.FULL_SOURCE;
    }
    public Batch fetch(int size) {
      assertTrue(size <= 2);
      reads++;
      int n = Math.min(size, remaining);
      remaining -= n;
      return new Batch(Collections.nCopies(n, new Row(List.of())), remaining == 0);
    }
    public void close() {
      closed++;
    }
  }
  CsvOptions options = new CsvOptions(true, ',', StandardCharsets.UTF_8, "NULL", Escaping.MINIMAL, 2);
  @Test
  void streamsAndCloses() {
    Source source = new Source();
    CsvService service = new CsvService((s, t, o, c) -> {
      long count = 0;
      Batch b;
      do {
        b = s.fetch(100);
        count += b.rows().size();
      } while (!b.complete());
      return new CsvResult(count);
    });
    assertEquals(5,
        service.export(source, RowScope.FULL_SOURCE, new Target("out"), options, () -> false).rowsWritten());
    assertEquals(3, source.reads);
    assertEquals(1, source.closed);
  }
  @Test
  void errorClosesAndPreserves() {
    Source source = new Source();
    CoreException failure = new CoreException(Code.IO, "disk full");
    CsvService service = new CsvService((s, t, o, c) -> {
      s.fetch(2);
      throw failure;
    });
    assertSame(failure,
        assertThrows(CoreException.class,
            () -> service.export(source, RowScope.FULL_SOURCE, new Target("out"), options, () -> false)));
    assertEquals(1, source.closed);
  }
  @Test
  void cancelCloses() {
    Source source = new Source();
    AtomicBoolean cancelled = new AtomicBoolean();
    CsvService service = new CsvService((s, t, o, c) -> {
      s.fetch(2);
      cancelled.set(true);
      s.fetch(2);
      return new CsvResult(0);
    });
    assertEquals(Code.CANCELLED,
        assertThrows(CoreException.class,
            () -> service.export(source, RowScope.FULL_SOURCE, new Target("out"), options, cancelled::get))
            .code());
    assertEquals(1, source.closed);
  }
  @Test
  void wrongScopeRejectedAndClosed() {
    Source source = new Source();
    CsvService service = new CsvService((s, t, o, c) -> {
      fail("Must not reach port");
      return new CsvResult(0);
    });
    assertEquals(Code.UNSUPPORTED,
        assertThrows(CoreException.class,
            () -> service.export(source, RowScope.REMAINING, new Target("out"), options, () -> false))
            .code());
    assertEquals(1, source.closed);
  }
  @Test
  void selectedBatchIsExplicit() {
    try (RowSource source =
             CsvService.selectedBatch(List.of(), new Batch(List.of(new Row(List.of())), false))) {
      assertEquals(RowScope.SELECTED_BATCH, source.scope());
      assertTrue(source.fetch(1).complete());
      assertTrue(source.fetch(1).rows().isEmpty());
    }
  }
}
