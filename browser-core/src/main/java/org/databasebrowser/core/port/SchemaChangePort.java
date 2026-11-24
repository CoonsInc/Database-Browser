package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Порт вызывается {@code ChangeSchema} после проверки возможностей и согласованного снимка схемы.
 * Подготовка не изменяет БД и снимает блокировки до возврата; {@link Prepared} содержит
 * только описание плана и рецепт без живых ресурсов драйвера. {@code applyChange} выполняет
 * исключительно рецепт из {@link #prepareChange(SessionId, Request)} под защитой адаптера.
 * Перед изменением адаптер повторно проверяет схему и данные; при устаревании возвращает
 * {@code ApplyStatus.CONFLICT}, не перестраивая план. Неизвестный исход передаётся как
 * {@code OUTCOME_UNKNOWN}; автоматический повтор запрещён.
 */
public interface SchemaChangePort {
    /**
     * Готовит предварительный просмотр изменений без их применения и без удержания блокировок.
     *
     * @param sessionId идентификатор открытой сессии
     * @param request проверенный запрос на изменение схемы
     * @return описание плана и неизменяемый рецепт для последующего применения
     */
    Prepared prepareChange(SessionId sessionId, Request request);

    /**
     * Применяет исключительно рецепт, возвращённый {@link #prepareChange(SessionId, Request)}.
     *
     * @param sessionId идентификатор открытой сессии
     * @param plan ранее подготовленный план с рецептом адаптера
     * @return статус применения, проблемы и новый токен схемы при успехе
     */
    ApplyResult applyChange(SessionId sessionId, Prepared plan);
}
