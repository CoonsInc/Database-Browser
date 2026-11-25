# browser-sqlite

SQLite-адаптер для `browser-core`. Регистрируется через `new SqliteAdapterProvider()` в общем `SessionRegistry`. Каждая сессия открывает отдельное JDBC-подключение.

## Настройки подключения

| Ключ в `Settings.properties()` | Значение |
| --- | --- |
| `location` | Путь к файлу SQLite или `:memory:`. Обязательное поле. В режиме записи отсутствующий файл создаётся. |
| `readOnly` | `true` или `false`, по умолчанию `false`. |
| `busyTimeoutMs` | Сколько SQLite ждёт освобождения блокировки. По умолчанию `3000` мс. |

Идентификатор адаптера доступен как `SqliteAdapterProvider.ID`. Проверка внешних ключей включена при открытии подключения.

## Первый рабочий путь

```java
try (var connections = new SessionRegistry(List.of(new SqliteAdapterProvider()))) {
    var sql = new SqlService(connections);
    var id = connections.connect(new Settings(SqliteAdapterProvider.ID, "Моя база",
        Map.of("location", "local.sqlite"), Map.of())).id();

    var options = new Sql.Options(ExecutionMode.SINGLE_STATEMENT,
        TransactionPolicy.AUTOCOMMIT, 100, Duration.ZERO);
    var request = new Sql.Request(sql.newOperationId(),
        "SELECT name FROM sqlite_schema WHERE type = ? ORDER BY name",
        List.of(new Sql.Parameter(1, Optional.empty(), new Sql.TextValue("table"))), options);
    var result = sql.execute(id, request);
    // result.firstBatch() содержит первую порцию строк.
}
```

Для дерева объектов `SchemaService` использует тот же реестр. `readSchema(id)` возвращает таблицы, колонки, ключи, индексы, представления и триггеры. Исходный DDL доступен в `Definition.originalSql()`. Выражения `CHECK` и индексов сохраняются в исходном DDL. Зависимости из тел представлений и триггеров пока не разбираются.

`SqlService.fetchRows` читает следующие порции по `ResultId`. При закрытии вкладки вызовите `closeResult`. Запрос выполняется одной командой в режиме `AUTOCOMMIT`. Отмена и таймаут прерывают выполнение SQLite. Ожидание блокировки ограничивает настройка `busyTimeoutMs`. Целые числа передаются точно в диапазоне `INTEGER`, остальные числа приводятся к `REAL`. Даты и время передаются как ISO-текст.

Структурированные изменения схемы с предпросмотром, порт `EXPLAIN` и экспорт DDL относятся к следующим этапам. DDL можно выполнить командой через `SqlService`.

Интеграционные тесты из корня проекта: `./gradlew :browser-sqlite:test`.
