# Примеры вызовов

Импорты из `org.databasebrowser.core.application.*`, `org.databasebrowser.core.domain.*`
и вложенных групп. Реальные адаптеры и файловые порты передаёт bootstrap; тестовый
комплект находится в `AdapterRegistryTest.Factory`. UI вызывает блокирующие методы
в фоновых задачах.

```java
void example(AdapterFactory sqliteFactory, AdapterFactory postgresFactory,
             CsvExportPort csvWriter, TextFilePort textWriter) {
    AdapterRegistry registry = new AdapterRegistry(
            List.of(sqliteFactory, postgresFactory),
            new ExportPorts(csvWriter, textWriter));

    List<AdapterInfo> adapters = registry.available();
    AdapterId chosen = adapters.get(0).id();
    ConnectionSchema form = registry.schema(chosen);   // поля формы подключения

    try (Usecases uc = registry.open(chosen, "Local",
            new ConnectionValues(Map.of("path", "/tmp/app.db"),
                    Map.of("password", "secret")))) {

        OperationId operation = uc.sql().newOperationId();  // до запуска execute
        Sql.Execution execution = uc.sql().execute(new Sql.Request(operation,
                "SELECT * FROM items", List.of(),
                new Sql.Options(ExecutionMode.SINGLE_STATEMENT,
                        TransactionPolicy.AUTOCOMMIT, 100, Duration.ofSeconds(30))));

        execution.result().ifPresent(resultId -> {
            try {
                Sql.Batch batch = execution.firstBatch();
                while (!batch.complete())
                    batch = uc.sql().fetchRows(resultId, 100);
            } finally {
                uc.sql().closeResult(resultId);             // обязателен даже при complete
            }
        });

        Column column = new Column("id",
                new DataType("INTEGER", List.of(), Optional.empty(), true),
                false, Optional.empty(), Map.of());
        Table table = new Table(new ObjectId(uc.scope().session().id(), "new:items"),
                new QualifiedName(Optional.empty(), Optional.empty(), "items"),
                List.of(column),
                List.of(new PrimaryKey(Optional.empty(), List.of("id"))),
                new Definition(Optional.empty(), Map.of(), true));

        Preparation preview = uc.change().prepareChange(new CreateTable(table));
        preview.plan().ifPresent(plan -> uc.change().applyChange(plan.id()));
        // CONFLICT или ALREADY_APPLIED: показать пользователю и сделать новый preview.

        Ddl ddl = uc.ddl().previewDdl(
                new DdlOptions(true, Set.of(), DependencyPolicy.INCLUDE));
        uc.ddl().saveDdlPreview(ddl, new Target("schema.sql"),
                new TextOptions(StandardCharsets.UTF_8, false));

        RowSource selected = ExportCsv.selectedBatch(
                execution.columns(), execution.firstBatch());
        uc.csv().export(selected, RowScope.SELECTED_BATCH,
                new Target("batch.csv"),
                new CsvOptions(true, ',', StandardCharsets.UTF_8,
                        "NULL", Escaping.MINIMAL, 100),
                () -> false);
    }
}
```

Отмена из другого фонового действия: `uc.sql().cancel(operation)`. Тот же
`OperationId` нельзя передать в новый `execute`; для нового запуска нужен
`uc.sql().newOperationId()`.

Передача оставшегося курсора в CSV:

```java
RowSource remaining = uc.sql().remainingRows(resultId);
uc.csv().export(remaining, RowScope.REMAINING, target, csvOptions, cancellation);
```

`ExportCsv` и `uc.csv()` принимают владение источником сразу и закрывают его
при успехе, ошибке, отмене и неверных параметрах. UI после передачи не читает
и не закрывает этот курсор сам.

Ошибки формы: разобрать `Preparation.problems()` и подсветить `Problem.path()`.
Ошибки операций: перехватить `Errors.CoreException`, обработать `code()`, показать
безопасное сообщение и при необходимости `diagnostic()`. Автоматический retry
не выполняется. Закрытие окна — `uc.close()`; отдельного глобального `close()` нет.
