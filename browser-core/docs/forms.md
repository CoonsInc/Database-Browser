# Контракт форм

Формы JavaFX создаёт UI. Core описывает поля через request-модели, capabilities,
supportedTypes и ошибки. Списки сохраняют порядок. Optional.empty означает
отсутствие значения; пустая строка в обязательном поле даёт required.
Коллекции и Optional должны создаваться корректно; null вместо коллекции,
null-элемент и null вместо Optional — ошибка построения DTO, не пользовательский ввод.
Основной валидатор: ChangeValidator.validate(Request); результат подготовки:
Preparation(Optional<Plan>, List<Problem>). WARNING не блокирует сам по себе.
Ограничения движка и сравнение имён определяются выбранным адаптером.

| Форма / request | Поля | Общие проверки и пути |
|---|---|---|
| Таблица / CreateTable(Table) | id (назначает UI в namespace сессии), name: QualifiedName; упорядоченные columns; constraints; definition | table.id, table.name.name обязательны; catalog/namespace необязательны, но непусты при наличии. table.columns непуст; table.columns[i] проверяются как колонки. Дубликаты имён: table.columns[i]. Не более одного PK: table.constraints. Дубликаты имён ограничений: table.constraints[i]. |
| Колонка / AddColumn(table,column), AlterColumn(table,originalName,desired) | table обязателен; originalName у изменения обязателен; name, DataType(name,parameters,originalDeclaration,known), nullable, defaultExpression, attributes | column.name, column.type.name обязательны; column.type.parameters[i] непусты. У изменения пути desired.name/desired.type.name. originalName идентифицирует исходную колонку, desired полностью задаёт итоговые свойства. Тип и параметры уточняет адаптер. |
| Удаление колонки / DropColumn | table, column | Оба обязательны. Существование проверяется по snapshot. Ссылки ограничений проверяются на итоговой структуре. |
| Ограничение / AddConstraint(table,constraint) | name необязательно; PK/Unique: columns; FK: columns, referencedTable, referencedColumns, onDelete/onUpdate; Check: expression | constraint.columns непуст, порядок значим; constraint.columns[i] без дублей. FK: равное число локальных и удалённых колонок, обе стороны непусты, referencedTable.name обязательно. constraint.referencedColumns — foreign_key_arity. constraint.expression обязательно для CHECK. Ссылки локальных колонок при подготовке: table.constraints[i].columns[j], unknown_column. |
| Удаление ограничения / DropConstraint | table, constraintName | Обязательны; для безымянного ограничения MVP не поддерживает адресное удаление. Адаптер явно отклоняет операцию. |
| Индекс / CreateIndex(Index) | id, name, table, unique, terms, predicate, definition | index.id, index.name.name, index.table обязательны. index.terms непуст; каждый terms[i] содержит ровно column либо expression; index.terms[i].direction обязателен, collation необязателен. Порядок/направление сохраняются. Проверка существования колонок/выражений — адаптер. |
| Представление / CreateView(View), AlterView(original,desired) | id, name, query, columnNames, definition; original для изменения | view.id, view.name.name, view.query обязательны. view.columnNames[i] непусты и без дублей; список может быть пуст. SQL не разбирается core. original обязателен и принадлежит сессии. Пути желаемого представления также view.*, как в валидаторе. |
| Удаление / DropTable, DropIndex, DropView | ID соответствующего объекта | table/index/view обязателен, принадлежит сессии, найден в снимке и имеет правильный вид. |
| Подтверждение / applyChange(sessionId,planId) | ID плана; подтверждение UI после просмотра | Показать steps по порядку, весь previewSql, checks с неизвестным passed, problems, dataLoss, affected, dependencies.complete/limitations, rollback и applicable. Кнопка Apply недоступна при applicable=false либо ERROR. Отправлять только ID показанного плана; CONFLICT требует нового preview. |

Типичные коды: required, empty, duplicate, unknown_column, multiple_primary_keys,
foreign_key_arity, index_term. Path — строковый путь к полю, индексы с нуля.
У первичного запроса AddConstraint ошибки структуры имеют constraint.*, а проверка
ссылок в полном контексте таблицы — table.constraints[i].*. UI может использовать
исходную таблицу для сопоставления индекса ограничения форме.

DEFAULT отсутствует: Optional.empty(); явно DEFAULT NULL: Optional.of("NULL").
Отключённое поле DEFAULT не следует превращать в строку "NULL".
Выражения CHECK, DEFAULT, WHERE индекса и SQL представления непрозрачны:
никакого split, переписывания регистра или синтаксического анализа UI/core.

Проверки БД представлены DataCheck с CheckKind: OBJECT_EXISTS, NAME_COLLISION,
NOT_NULL, UNIQUE, CONVERSION, FOREIGN_KEY, ENGINE. Optional.empty у passed
означает, что проверка ещё не дала результата, а не успешность. Предварительная
успешность не освобождает адаптер от повторной проверки перед изменением данных.

Definition для нового объекта: originalSql=Optional.empty(), attributes=Map.of(),
editable=true. Для обнаруженного сложного объекта адаптер может установить
editable=false, сохранив originalSql и дополнительные атрибуты для экспорта.
Имена constraint/column не нормализуются core: IdentifierRules провайдера
определяет, какие дубликаты core способен достоверно распознать.
