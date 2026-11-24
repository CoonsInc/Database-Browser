package org.databasebrowser.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Непрозрачные идентификаторы ядра, уникальные в рамках запущенного экземпляра ядра.
 * Класс не создаётся: вложенные record используются как значения-обёртки над {@code UUID} или
 * строкой. {@code AdapterId} и {@code ObjectId} хранят строковое значение, остальные
 * идентификаторы построены на {@code UUID}. Область уникальности — один запущенный экземпляр
 * ядра, поэтому не используйте их как постоянные внешние ключи.
 */
public final class Ids {
    private Ids() {}

    /**
     * Идентификатор адаптера: непустая строка, заданная реализацией адаптера.
     * Значение непрозрачно для ядра и сравнивается как есть; пустая строка запрещена.
     */
    public record AdapterId(String value) {
        public AdapterId {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("adapter id");
        }
    }

    /**
     * Идентификатор сессии подключения на основе {@code UUID}. Уникален в рамках запущенного ядра
     * и используется в {@code Session.id} и {@code ObjectId.session}.
     */
    public record SessionId(UUID value) {
        public SessionId {
            Objects.requireNonNull(value);
        }
    }

    /**
     * Идентификатор объекта внутри сессии: {@code session} задаёт область уникальности,
     * {@code value} — само значение. Одинаковые значения {@code value} в разных сессиях
     * обозначают разные объекты. Оба поля обязательны.
     */
    public record ObjectId(SessionId session, String value) {
        public ObjectId {
            Objects.requireNonNull(session);
            Objects.requireNonNull(value);
        }
    }

    /**
     * Идентификатор операции на основе {@code UUID}, уникальный в рамках запущенного ядра.
     * Связывает операцию с её результатом и отслеживает выполнение в асинхронном сценарии.
     */
    public record OperationId(UUID value) {
        public OperationId {
            Objects.requireNonNull(value);
        }
    }

    /**
     * Идентификатор результата операции на основе {@code UUID}, уникальный в рамках ядра.
     * Используйте его для получения уже вычисленного результата, а не как идентификатор самой
     * операции.
     */
    public record ResultId(UUID value) {
        public ResultId {
            Objects.requireNonNull(value);
        }
    }

    /**
     * Идентификатор плана изменений схемы на основе {@code UUID}, уникальный в рамках ядра.
     * Служит ключом плана между его построением и применением.
     */
    public record ChangePlanId(UUID value) {
        public ChangePlanId {
            Objects.requireNonNull(value);
        }
    }
}
