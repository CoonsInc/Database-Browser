package org.databasebrowser.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Набор неизменяемых моделей для описания схемы базы данных: имена, типы, объекты и зависимости.
 * Используйте {@code Snapshot} как консистентный срез схемы, а {@code SchemaObject} для перечисления
 * таблиц, индексов, представлений и триггеров. Адаптер возвращает срез вместе с непрозрачным
 * {@code stateToken}, который затем проверяется при подготовке изменений.
 */
public final class Schema {
    private Schema() {}

    /**
     * Полное имя объекта схемы: каталог, пространство имён и локальное имя.
     * Пустой {@code Optional} означает отсутствующий уровень. Используйте запись при сравнении
     * имён, полученных от разных адаптеров.
     */
    public record QualifiedName(Optional<String> catalog, Optional<String> namespace, String name) {
    }

    /**
     * Описание типа данных колонки: имя, параметры и исходное объявление.
     * Флаг {@code known} показывает, распознал ли адаптер тип. {@code originalDeclaration}
     * сохраняется как непрозрачный текст для точного воспроизведения DDL.
     */
    public record DataType(
        String name, List<String> parameters, Optional<String> originalDeclaration, boolean known) {
        public DataType {
            parameters = List.copyOf(parameters);
        }
    }
    /**
     * Описание колонки таблицы: имя, тип, допустимость {@code NULL} и выражение {@code DEFAULT}.
     * {@code Optional.empty()} означает отсутствие {@code DEFAULT}; {@code Optional.of("NULL")} — явный {@code DEFAULT NULL}.
     * Выражение {@code defaultExpression} непрозрачно; используйте {@code attributes} для
     * адаптерных свойств. Конструктор копирует атрибуты, поэтому запись неизменяема.
     */

    public record Column(String name, DataType type, boolean nullable,
        Optional<String> defaultExpression, Map<String, String> attributes) {
        public Column {
            attributes = Map.copyOf(attributes);
        }
    }

    /**
     * Действие ссылочной целостности для {@code ON DELETE} или {@code ON UPDATE}. Используйте
     * значение при описании внешнего ключа {@code ForeignKey}.
     */
    public enum ForeignKeyAction { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT }

    /**
     * Закрытый базовый тип ограничений таблицы. Используйте {@code instanceof} или переключение
     * по {@code PrimaryKey}, {@code ForeignKey}, {@code Unique} и {@code Check}. Метод
     * {@code name()} может быть пустым для безымянных ограничений.
     */
    public sealed interface Constraint permits PrimaryKey, ForeignKey, Unique, Check {
        Optional<String> name();
    }

    /**
     * Ограничение первичного ключа со списком колонок. Используйте {@code columns()} в том
     * порядке, в каком ключ объявлен в DDL.
     */
    public record PrimaryKey(Optional<String> name, List<String> columns) implements Constraint {
        public PrimaryKey {
            columns = List.copyOf(columns);
        }
    }

    /**
     * Ограничение уникальности со списком колонок. Используйте {@code name()}, если адаптер
     * вернул имя ограничения.
     */
    public record Unique(Optional<String> name, List<String> columns) implements Constraint {
        public Unique {
            columns = List.copyOf(columns);
        }
    }

    /**
     * Внешний ключ: локальные {@code columns} ссылаются на {@code referencedColumns} таблицы
     * {@code referencedTable}. Действия {@code onDelete} и {@code onUpdate} задают поведение
     * адаптера при удалении или изменении родительской строки.
     */
    public record ForeignKey(Optional<String> name, List<String> columns,
        QualifiedName referencedTable, List<String> referencedColumns, ForeignKeyAction onDelete,
        ForeignKeyAction onUpdate) implements Constraint {
        public ForeignKey {
            columns = List.copyOf(columns);
            referencedColumns = List.copyOf(referencedColumns);
        }
    }

    /**
     * Проверочное ограничение с непрозрачным выражением {@code expression}. Используйте
     * {@code name()}, только если адаптер вернул имя.
     */
    public record Check(Optional<String> name, String expression) implements Constraint {}
    /**
     * Определение объекта схемы: исходный SQL, адаптерные атрибуты и признак редактируемости.
     * Если {@code editable} равен {@code false}, адаптер не гарантирует точное воспроизведение
     * объекта после изменения, поэтому такой объект нельзя использовать как цель правки.
     */
    public record
        Definition(Optional<String> originalSql, Map<String, String> attributes, boolean editable) {
        public Definition {
            attributes = Map.copyOf(attributes);
        }
    }

    /**
     * Закрытый базовый тип объектов схемы: идентификатор, полное имя и определение.
     * Используйте {@code ObjectId} для связей между объектами, а {@code Definition} для проверки
     * возможности редактирования перед подготовкой изменения.
     */
    public sealed interface SchemaObject permits Table, Index, View, Trigger {
        ObjectId id();

        QualifiedName name();

        Definition definition();
    }

    /**
     * Таблица со столбцами, ограничениями и определением. Передавайте экземпляр в
     * {@code Changes.CreateTable} или используйте как источник колонок при подготовке изменений.
     */
    public record Table(ObjectId id, QualifiedName name, List<Column> columns,
        List<Constraint> constraints, Definition definition) implements SchemaObject {
        public Table {
            columns = List.copyOf(columns);
            constraints = List.copyOf(constraints);
        }
    }

    /** Направление сортировки в индексе; {@code UNSPECIFIED} означает, что адаптер не вернул направление. */
    public enum Direction { ASC, DESC, UNSPECIFIED }
    /**
     * Термин индекса: либо колонка {@code column}, либо выражение {@code expression}. Остальные
     * поля задают направление и сортировку; выражение сохраняет индексы, не являющиеся простыми
     * списками колонок, включая функциональные.
     */

    public record IndexTerm(Optional<String> column, Optional<String> expression,
        Direction direction, Optional<String> collation) {}

    /**
     * Индекс таблицы с признаком уникальности, списком терминов и необязательным предикатом.
     * Поле {@code table} содержит идентификатор владельца. {@code predicate} непрозрачен и
     * сохраняется вместе с {@code Definition} для точного воспроизведения DDL.
     */
    public record Index(ObjectId id, QualifiedName name, ObjectId table, boolean unique,
        List<IndexTerm> terms, Optional<String> predicate, Definition definition)
        implements SchemaObject {
        public Index {
            terms = List.copyOf(terms);
        }
    }

    /**
     * Представление с текстом запроса {@code query} и именами возвращаемых колонок.
     * Используйте {@code columnNames}, когда адаптер не раскрывает типы колонок. Проверяйте
     * {@code Definition.editable()} перед подготовкой {@code Changes.AlterView}.
     */
    public record View(ObjectId id, QualifiedName name, String query, List<String> columnNames,
        Definition definition) implements SchemaObject {
        public View {
            columnNames = List.copyOf(columnNames);
        }
    }

    /**
     * Триггер с идентификатором владельца {@code owner} и определением. Используйте
     * {@code Definition.editable()}, чтобы решить, можно ли изменять триггер.
     */
    public record Trigger(ObjectId id, QualifiedName name, ObjectId owner, Definition definition)
        implements SchemaObject {}

    /**
     * Ребро зависимости: {@code prerequisite} должен существовать до {@code dependent}.
     * Необязательный {@code kind} уточняет тип связи, если адаптер его вернул.
     */
    public record Dependency(ObjectId prerequisite, ObjectId dependent, Optional<String> kind) {}

    /**
     * Граф зависимостей в виде списка рёбер {@code edges}. Флаг {@code complete} показывает,
     * полон ли граф. Список {@code limitations} объясняет, какие связи адаптер не смог определить.
     */
    public record Dependencies(List<Dependency> edges, boolean complete, List<String> limitations) {
        public Dependencies {
            edges = List.copyOf(edges);
            limitations = List.copyOf(limitations);
        }
    }
    /**
     * Адаптер обязан вернуть согласованный срез и непрозрачный токен версии.
     * Срез схемы сессии: пространства имён, объекты, зависимости и {@code stateToken}. Передавайте
     * {@code stateToken} в {@code Changes.Plan.schemaToken()}, чтобы убедиться, что план подготовлен
     * именно для этого состояния схемы.
     */

    public record Snapshot(SessionId session, AdapterId dialect, String stateToken,
        List<QualifiedName> namespaces, List<SchemaObject> objects, Dependencies dependencies) {
        public Snapshot {
            namespaces = List.copyOf(namespaces);
            objects = List.copyOf(objects);
        }
    }
}
