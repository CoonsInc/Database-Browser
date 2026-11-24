# browser-core

Java 17, Gradle `java-library`, без runtime-зависимостей. Сборка из корня:
`./gradlew :browser-core:build`. JUnit Jupiter используется только в тестах.

Цель модуля: UI работает только с готовыми use cases. Порты, SQL, JDBC и детали
конкретной СУБД остаются внутри адаптера и наружу не светятся.

## Слои

- `domain` — неизменяемые DTO и контракты: `Ids`, `Connections`, `Errors`, `Schema`,
  `Sql`, `Changes`, `QueryPlans`, `Exports`. Типы вложены в группы-пространства имён,
  например `org.databasebrowser.core.domain.Schema.Table`.
- `port` — SPI адаптера: `ConnectionPort`, `DatabaseInfoPort`, `SchemaReaderPort`,
  `SchemaChangePort`, `SqlExecutionPort`, `QueryPlanPort`, `DdlExportPort`.
  `DatabasePorts` собирает их в один комплект. UI этот комплект не видит.
- `application` — `AdapterRegistry`, `AdapterFactory`, `SessionScope`, `Usecases`,
  `ExportPorts` и сами use cases. Здесь живут лок, владение курсорами и история ID.
- `ExportPorts` — не БД-порты, а общие файловые порты `CsvExportPort` и `TextFilePort`.
  Их предоставляет bootstrap один раз и передаёт всем сессиям.

Concrete-адаптера в core нет. Bootstrap явно передаёт список `AdapterFactory`
в `AdapterRegistry`; `ServiceLoader` не используется.

## Обычный поток UI

```java
AdapterRegistry registry = new AdapterRegistry(
    List.of(sqliteFactory, postgresFactory),
    new ExportPorts(csvWriter, textWriter));

List<AdapterInfo> adapters = registry.available();            // список для пикера
ConnectionSchema form = registry.schema(chosenId);            // поля формы подключения

try (Usecases uc = registry.open(
        chosenId, "Local", new ConnectionValues(properties, secrets))) {
    AdapterId dialect = uc.scope().session().info().dialect();

    OperationId op = uc.sql().newOperationId();               // до запуска execute
    uc.sql().execute(new Sql.Request(op, "SELECT 1", List.of(), sqlOptions));
    uc.sql().cancel(op);                                      // из другой фоновой задачи

    Preparation preview = uc.change().prepareChange(changeRequest);
    preview.plan().ifPresent(plan -> uc.change().applyChange(plan.id()));

    Ddl ddl = uc.ddl().previewDdl(ddlOptions);
    uc.ddl().saveDdlPreview(ddl, target, textOptions);         // сохраняет показанный текст

    RowSource rest = uc.sql().remainingRows(resultId);
    uc.csv().export(rest, RowScope.REMAINING, target, csvOptions, cancellation);
}
```

`Usecases` привязан к одному `SessionScope`: одно окно = одна сессия = один комплект
портов = один лок. Закрытие окна — `uc.close()`. Блокирующие вызовы UI запускает
в фоновых задачах; `cancel` специально обходит лок сессии и работает во время SQL.

## Подключение

`ConnectionSchema` описывает поля через `SettingField(key, label, kind, required, secret)`.
`SettingKind` задаёт только формат значения: `STRING`, `PATH`, `NUMBER`, `BOOLEAN`.
Сокрытие поля определяется флагом `secret`, а не отдельным kind. Значения приходят
в `ConnectionValues`: обычные — в `properties`, чувствительные — в `secrets`.
Ни адаптера, ни display name внутри нет; их задаёт вызов `registry.open(...)`.

Адаптер обязан перепроверить значения сам. `ConnectionValuesReader` — общий мост
от строковых `Map` к `Path`, `boolean`, `int` и secret внутри `AdapterFactory.open`.
`ConnectionValues.toString()` не печатает значения.

## Что сохранять при изменениях

- UI не получает `DatabasePorts` и не знает про адаптерные порты.
- `registry.open` проверяет display name, values и известность `AdapterId`.
- `SessionScope` держит один лок на сессию; разные окна работают параллельно.
- `SharedIds` хранит владельцев operation/result/plan ID на уровне реестра.
  ID не переиспользуются, даже после завершения операции.
- `ExecuteSql.newOperationId()` вызывается до `execute`/`explain`.
- План изменения одноразовый: вторая попытка того же `ChangePlanId` даёт
  `ALREADY_APPLIED`, включая потери связи во время первой попытки.
- `ExecutionRecipe` и `Prepared` не уходят в UI; applying идёт только по ID плана.
- `RowSource` сразу забирает курсор в исключительное владение; `ExportCsv`
  закрывает его при успехе, ошибке и отмене.
- Адаптер переводит свои ошибки в `CoreException` с безопасной диагностикой.
  `Errors.safe` превращает посторонние `RuntimeException` в `INTERNAL` без текста
  поставщика; `CoreException` проходит без изменений.
- Снимок схемы обязан совпадать с сессией, диалектом и `stateToken`.

Подробности контрактов адаптера: [docs/forms.md](docs/forms.md).
Обновлённый пример вызовов: [docs/examples.md](docs/examples.md).
Живой пример фейковых фабрик и портов: `AdapterRegistryTest`.
