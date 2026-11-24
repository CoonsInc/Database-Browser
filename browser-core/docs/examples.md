# Примеры

Импорты из `org.databasebrowser.core.domain.*` и вложенных групп используются
в примере ниже. Реальные зависимости передаёт bootstrap; тестовый комплект портов
находится в `BrowserServiceTest.Fake`, исключительно в test source set.
UI должен вызывать блокирующие методы в фоновой задаче.

```java
import java.util.*;
import java.time.Duration;
import org.databasebrowser.core.application.*;
import org.databasebrowser.core.domain.Ids.*;
import org.databasebrowser.core.domain.Connections.*;
import org.databasebrowser.core.domain.Schema.*;
import org.databasebrowser.core.domain.Changes.*;
import org.databasebrowser.core.domain.Exports.*;
import org.databasebrowser.core.domain.Sql;
import org.databasebrowser.core.port.*;

// provider, textFiles, csvWriter — реализации адаптеров из bootstrap.
void example(AdapterProvider provider, TextFilePort textFiles, CsvExportPort csvWriter) {
    try (BrowserService browser = new BrowserService(List.of(provider), textFiles)) {
        Session session = browser.connect(new Settings(provider.id(), "Local",
                Map.of("location", "example.db"), Map.of()));
        SessionId id = session.id();
        Column column = new Column("id",
                new DataType("INTEGER", List.of(), Optional.empty(), true),
                false, Optional.empty(), Map.of());
        Table table = new Table(new ObjectId(id, "new:items"),
                new QualifiedName(Optional.empty(), Optional.empty(), "items"),
                List.of(column), List.of(new PrimaryKey(Optional.empty(), List.of("id"))),
                new Definition(Optional.empty(), Map.of(), true));
        Preparation preview = browser.prepareChange(id, new CreateTable(table));
        // Передать preview в UI. После явного подтверждения пользователем:
        if (preview.plan().isPresent() && preview.plan().get().applicable()) {
            Plan shown = preview.plan().get();
            ApplyResult result = browser.applyChange(id, shown.id());
            // CONFLICT => показать результат и предложить новый preview.
        }

        OperationId operation = browser.newOperationId();
        // ID уже можно передать действию Cancel в UI.
        Sql.Execution execution = browser.execute(id, new Sql.Request(operation,
                "SELECT * FROM items", List.of(),
                new Sql.Options(ExecutionMode.SINGLE_STATEMENT,
                        TransactionPolicy.AUTOCOMMIT, 100, Duration.ofSeconds(30))));
        execution.result().ifPresent(result -> {
            try {
                Sql.Batch batch = execution.firstBatch();
                while (!batch.complete()) batch = browser.fetchRows(id, result, 100);
            } finally {
                browser.closeResult(id, result);
            }
        });
        Ddl ddl = browser.previewDdl(id,
                new DdlOptions(true, Set.of(), DependencyPolicy.INCLUDE));
        // После выбора назначения сохраняем именно показанный текст:
        browser.saveDdlPreview(ddl, new Target("schema.sql"),
                new TextOptions(java.nio.charset.StandardCharsets.UTF_8, false));

        // Экспорт выбранной UI порции (не всего результата):
        RowSource source = CsvService.selectedBatch(execution.columns(), execution.firstBatch());
        new CsvService(csvWriter).export(source, RowScope.SELECTED_BATCH,
                new Target("batch.csv"),
                new CsvOptions(true, ',', java.nio.charset.StandardCharsets.UTF_8,
                        "NULL", Escaping.MINIMAL, 100), () -> false);
    }
}
```

Отмена из другого фонового действия: `browser.cancel(sessionId, operationId)`.
Тот же ID нельзя передать в новый execute; для нового запуска нужен newOperationId().
Для передачи оставшегося курсора:

```java
RowSource remaining = browser.remainingRows(sessionId, resultId);
csvService.export(remaining, RowScope.REMAINING, target, csvOptions, cancellation);
// CsvService закрыл remaining и соответствующий результат.
```

Ошибка формы: обработать `Preparation.problems()` и подсветить `Problem.path()`.
Ошибка операции: перехватить `Errors.CoreException`, обработать `code()`, показать
безопасное message и при необходимости diagnostic(). Не выполнять автоматический retry.
На завершении приложения вызвать browser.close() в рабочем потоке и показать ошибки
закрытия; UI не должен считать смену статуса в core откатом SQL.
