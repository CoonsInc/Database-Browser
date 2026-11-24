package org.databasebrowser.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Контейнер типов предметной области для подключений, адаптеров и сессий.
 * Класс только группирует объявления и не создаётся: используйте вложенные record как неизменяемые
 * значения. Карты и списки в record копируются при создании. Секреты хранятся отдельно от свойств
 * и не выводятся в {@code toString}.
 */
public final class Connections {
    private Connections() {}
    /**
     * Формат значения настройки подключения: {@code STRING}, {@code PATH}, {@code NUMBER} или
     * {@code BOOLEAN}. Перечисление описывает только представление значения, а не способы его
     * показа. Сокрытие поля задаётся флагом {@code SettingField.secret}; конкретный тип адаптера
     * здесь не нужен.
     */
    public enum SettingKind { STRING, PATH, NUMBER, BOOLEAN }

    /**
     * Описание одной настройки, которую ожидает адаптер; UI строит форму по этому описанию, без
     * знания конкретного типа адаптера. {@code key} не должен быть пустым, {@code label} и
     * {@code kind} обязательны. Флаг {@code secret} показывает, что значение нужно скрывать;
     * {@code SettingKind} задаёт только формат значения.
     */
    public record SettingField(
        String key, String label, SettingKind kind, boolean required, boolean secret) {
        public SettingField {
            if (key == null || key.isBlank())
                throw new IllegalArgumentException("setting key");
            Objects.requireNonNull(label);
            Objects.requireNonNull(kind);
        }
    }

    /**
     * Схема настроек адаптера: упорядоченный список полей для построения формы подключения.
     * Список копируется в {@code List.copyOf}, поэтому изменение исходного списка не влияет на
     * схему. Порядок {@code fields} задаёт порядок полей в форме.
     */
    public record ConnectionSchema(List<SettingField> fields) {
        public ConnectionSchema {
            fields = List.copyOf(fields);
        }
    }

    /**
     * Значения одного подключения: только {@code properties} и {@code secrets}.
     * Идентификатор адаптера и отображаемое имя здесь не хранятся; они живут в {@code Session}.
     * Карты копируются при создании. Секреты не выводятся в {@code toString}: метод возвращает
     * {@code ConnectionValues[properties=<redacted>, secrets=<redacted>]}.
     */
    public record ConnectionValues(Map<String, String> properties, Map<String, String> secrets) {
        public ConnectionValues {
            properties = Map.copyOf(properties);
            secrets = Map.copyOf(secrets);
        }

        @Override
        public String toString() {
            return "ConnectionValues[properties=<redacted>, secrets=<redacted>]";
        }
    }

    /**
     * Сессия подключения: её идентификатор, адаптер, отображаемое имя и сведения о базе данных.
     * {@code id} имеет тип {@code SessionId}, {@code adapter} — {@code AdapterId}, а {@code info}
     * описывает фактическую базу. Используйте запись как неизменяемый снимок открытой сессии.
     */
    public record Session(SessionId id, AdapterId adapter, String displayName, DatabaseInfo info) {}

    /**
     * Результат открытия сессии: тот же {@code id} и фактические {@code info} базы данных.
     * Запись нужна, чтобы передать результат открытия вместе с проверенными сведениями о базе.
     * Идентификатор сессии не меняется при открытии.
     */
    public record Opened(SessionId id, DatabaseInfo info) {}

    /**
     * Сведения о базе данных: диалект, продукт, версия и дополнительные атрибуты.
     * {@code dialect} — {@code AdapterId}, {@code attributes} копируется в {@code Map.copyOf}.
     * Читайте {@code attributes} как неизменяемые пары имя/значение, специфичные для адаптера.
     */
    public record DatabaseInfo(
        AdapterId dialect, String product, String version, Map<String, String> attributes) {
        public DatabaseInfo {
            attributes = Map.copyOf(attributes);
        }
    }

    /**
     * Степень поддержки изменения схемы: {@code DIRECT}, {@code MIGRATION} или
     * {@code UNSUPPORTED}. Значение хранится в {@code Capabilities.changes} и показывает, как
     * адаптер применяет соответствующий {@code ChangeKind}.
     */
    public enum Support { DIRECT, MIGRATION, UNSUPPORTED }

    /**
     * Вид изменения схемы базы данных, для которого адаптер сообщает поддержку.
     * Значения используются как ключи {@code Capabilities.changes}.
     */
    public enum ChangeKind {
        CREATE_TABLE,
        DROP_TABLE,
        ADD_COLUMN,
        ALTER_COLUMN,
        DROP_COLUMN,
        ADD_CONSTRAINT,
        DROP_CONSTRAINT,
        CREATE_INDEX,
        DROP_INDEX,
        CREATE_VIEW,
        ALTER_VIEW,
        DROP_VIEW
    }

    /**
     * Допустимый способ выполнения оператора; сейчас доступен только {@code SINGLE_STATEMENT}.
     * Адаптер перечисляет поддерживаемые режимы в {@code Capabilities.executionModes}.
     */
    public enum ExecutionMode { SINGLE_STATEMENT }

    /**
     * Политика транзакции: {@code AUTOCOMMIT} или {@code ATOMIC}.
     * Адаптер сообщает поддерживаемые политики через {@code Capabilities.transactions}.
     */
    public enum TransactionPolicy { AUTOCOMMIT, ATOMIC }

    /**
     * Возможности адаптера: поддержка каждого {@code ChangeKind}, план запросов, анализ и
     * допустимые режимы выполнения и транзакций. Карты и множества копируются при создании.
     * Для вида, отсутствующего в {@code changes}, {@code support(kind)} возвращает
     * {@code Support.UNSUPPORTED}.
     */
    public record Capabilities(Map<ChangeKind, Support> changes, boolean queryPlan, boolean analyze,
        Set<ExecutionMode> executionModes, Set<TransactionPolicy> transactions) {
        public Capabilities {
            changes = Map.copyOf(changes);
            executionModes = Set.copyOf(executionModes);
            transactions = Set.copyOf(transactions);
        }

        public Support support(ChangeKind kind) {
            return changes.getOrDefault(kind, Support.UNSUPPORTED);
        }
    }
    /**
     * Описание типа данных, поддерживаемого адаптером: имя, имена параметров и необязательное
     * описание. {@code parameterNames} копируется в {@code List.copyOf}, а {@code description}
     * может быть пустым {@code Optional}. Используйте запись для подсказок в UI, не как конкретное
     * значение колонки.
     */
    public record
        SupportedType(String name, List<String> parameterNames, Optional<String> description) {
        public SupportedType {
            parameterNames = List.copyOf(parameterNames);
        }
    }
    /**
     * Правило сравнения идентификаторов: адаптер сам решает, учитывать регистр или следовать
     * семантике идентификаторов базы данных. {@code equivalent(left, right)} возвращает
     * {@code true}, если два имени обозначают один и тот же объект. Интерфейс функциональный,
     * поэтому его можно передать как лямбду.
     */
    @FunctionalInterface

    public interface IdentifierRules {
        boolean equivalent(String left, String right);
    }
}
