package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Definition;
import org.databasebrowser.core.domain.Schema.Dependencies;
import org.databasebrowser.core.domain.Schema.Snapshot;

/**
 * Порт чтения схемы открытой сессии. Вызывается {@code ReadSchema}, {@code SessionScope.snapshot()}
 * и {@code ExportDdl}; все операции только читают метаданные и не изменяют БД.
 * Адаптер обязан вернуть согласованный {@link Snapshot} с непрозрачным токеном состояния,
 * соответствующий {@link SessionId} и диалекту сессии. Для объектов он возвращает исходное
 * определение и граф зависимостей, честно помечая неполноту в {@code Dependencies.complete()}
 * и ограничения в {@code Dependencies.limitations()}.
 */
public interface SchemaReaderPort {
    /**
     * Возвращает согласованный снимок схемы с токеном состояния.
     *
     * @param sessionId идентификатор открытой сессии
     * @return снимок, принадлежащий этой сессии и её диалекту
     */
    Snapshot readSchema(SessionId sessionId);

    /**
     * Возвращает исходное определение объекта и признак его редактируемости.
     *
     * @param sessionId идентификатор открытой сессии
     * @param objectId идентификатор объекта внутри этой сессии
     * @return определение с исходным SQL и атрибутами адаптера
     */
    Definition readObjectDefinition(SessionId sessionId, ObjectId objectId);

    /**
     * Возвращает зависимости объекта от других объектов схемы.
     *
     * @param sessionId идентификатор открытой сессии
     * @param objectId идентификатор объекта внутри этой сессии
     * @return рёбра зависимостей, флаг полноты и список ограничений
     */
    Dependencies findDependencies(SessionId sessionId, ObjectId objectId);
}
