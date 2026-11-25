package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Ids.SessionId;

/** Готовит и применяет изменения схемы. Рецепты не хранят открытые ресурсы. */
public interface SchemaChangePort {
    /**
     * Проверяет черновик, включая колонки без типа. БД доступна только для чтения.
     * Взятые при подготовке блокировки снимаются до возврата.
     */
    Prepared prepareChange(SessionId sessionId, Request request);

    /**
     * Повторно проверяет схему и данные с защитой от конкурентных изменений.
     * Применяет подтверждённый рецепт. Для устаревшего плана возвращает CONFLICT.
     * При неизвестном исходе сообщает об этом без повтора.
     */
    ApplyResult applyChange(SessionId sessionId, Prepared plan);
}
