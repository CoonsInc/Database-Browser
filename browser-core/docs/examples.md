# Примеры

Импорты из `org.databasebrowser.core.domain.*` и вложенных групп используются
в примере ниже. Реальные зависимости передаёт bootstrap; тестовый комплект портов
находится в `CoreUseCasesTest.Fake`, исключительно в test source set.
UI должен вызывать блокирующие методы в фоновой задаче.

```java
import java.util.*;
import java.time.Duration;
import org.databasebrowser.core.application.CsvService;
import org.databasebrowser.core.application.schema.SchemaService;
import org.databasebrowser.core.application.session.ConnectionService;
import org.databasebrowser.core.application.session.SessionRegistry;
import org.databasebrowser.core.application.sql.SqlService;
import org.databasebrowser.core.domain.Ids.*;
import org.databasebrowser.core.domain.Connections.*;
import org.databasebrowser.core.domain.Schema.*;
import org.databasebrowser.core.domain.Changes.*;
import org.databasebrowser.core.domain.Exports.*;
import org.databasebrowser.core.domain.Sql;
import org.databasebrowser.core.port.*;

// Компоненты запуска передают готовые адаптеры.
void example(AdapterProvider provider, TextFilePort textFiles, CsvExportPort csvWriter) {
    try (var sessions = new SessionRegistry(List.of(provider))) {
        ConnectionService connections = sessions;
        SqlService sql = new SqlService(sessions);
        SchemaService schema = new SchemaService(sessions, textFiles);
        CsvService csv = new CsvService(csvWriter);

        Session session = connections.connect(new Settings(provider.id(), "Local",
                Map.of("location", "example.db"), Map.of()));
        SessionId id = session.id();

        Column column = new Column("id",
                new DataType("INTEGER", List.of(), Optional.empty(), true),
                false, Optional.empty(), Map.of());
        Table table = new Table(new ObjectId(id, "new:items"),
                new QualifiedName(Optional.empty(), Optional.empty(), "items"),
                List.of(column), List.of(new PrimaryKey(Optional.empty(), List.of("id"))),
                new Definition(Optional.empty(), Map.of(), true));
        Preparation preview = schema.prepareChange(id, new CreateTable(table));
        // Показать план. Продолжить после подтверждения пользователя.
        if (preview.plan().isPresent() && preview.plan().get().applicable()) {
            Plan shown = preview.plan().get();
            ApplyResult result = schema.applyChange(id, shown.id());
            // При CONFLICT показать причину и предложить новый план.
        }

        OperationId operation = sql.newOperationId();
        // Идентификатор нужен кнопке отмены.
        Sql.Execution execution = sql.execute(id, new Sql.Request(operation,
                "SELECT * FROM items", List.of(),
                new Sql.Options(ExecutionMode.SINGLE_STATEMENT,
                        TransactionPolicy.AUTOCOMMIT, 100, Duration.ofSeconds(30))));
        execution.result().ifPresent(result -> {
            try {
                Sql.Batch batch = execution.firstBatch();
                while (!batch.complete()) {
                    batch = sql.fetchRows(id, result, 100);
                }
            } finally {
                sql.closeResult(id, result);
            }
        });
        Ddl ddl = schema.previewDdl(id,
                new DdlOptions(true, Set.of(), DependencyPolicy.INCLUDE));
        // После выбора файла сохраняем показанный текст.
        schema.saveDdlPreview(ddl, new Target("schema.sql"),
                new TextOptions(java.nio.charset.StandardCharsets.UTF_8, false));

        // Экспортируем выбранную порцию.
        RowSource source = CsvService.selectedBatch(execution.columns(), execution.firstBatch());
        csv.export(source, RowScope.SELECTED_BATCH,
                new Target("batch.csv"),
                new CsvOptions(true, ',', java.nio.charset.StandardCharsets.UTF_8,
                        "NULL", Escaping.MINIMAL, 100), () -> false);
    }
}
```

Отмена из другого фонового действия: `sql.cancel(sessionId, operationId)`.
Тот же ID нельзя передать в новый execute; для нового запуска нужен newOperationId().
Для передачи оставшегося курсора:

```java
RowSource remaining = sql.remainingRows(sessionId, resultId);
csvService.export(remaining, RowScope.REMAINING, target, csvOptions, cancellation);
// CsvService закрыл источник и результат.
```

Ошибка формы: обработать `Preparation.problems()` и подсветить `Problem.path()`.
Ошибка операции: перехватить `Errors.CoreException`, обработать `code()`, показать
безопасное message и при необходимости diagnostic(). Не выполнять автоматический retry.
На завершении приложения вызвать sessions.close() в рабочем потоке и показать ошибки
закрытия; UI не должен считать смену статуса в core откатом SQL.
