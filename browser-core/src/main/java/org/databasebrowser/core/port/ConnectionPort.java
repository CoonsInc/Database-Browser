package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Connections.ConnectionValues;
import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Порт вызывается фабрикой адаптера при открытии соединения и прикладными сценариями через
 * {@code SessionScope}: {@code InspectDatabase} проверяет сессию, {@code SessionScope.close()}
 * закрывает её. {@code connect} принимает только {@link ConnectionValues}: адаптер получает
 * готовые свойства и секреты и не должен запрашивать их у UI. Адаптер обязан вернуть новый
 * {@link SessionId} на основе {@code UUID}, не повторно использовать старые идентификаторы и при
 * {@code disconnect} освободить результаты и выполняемую работу даже при параллельном {@code cancel}.
 * {@code disconnect} идемпотентен и потокобезопасен; об ошибках адаптер сообщает только безопасной
 * диагностикой без чувствительных данных.
 */
public interface ConnectionPort {
    /**
     * Открывает соединение строго по переданным {@link ConnectionValues} и создаёт сессию.
     *
     * @param values свойства и секреты адаптера без идентификатора и отображаемого имени
     * @return идентификатор свежей сессии и информация о базе данных
     */
    Opened connect(ConnectionValues values);

    /**
     * Проверяет, что сессия ещё пригодна для работы, не создавая новое соединение.
     *
     * @param sessionId идентификатор уже открытой сессии
     * @return {@code true}, если соединение активно
     */
    boolean checkConnection(SessionId sessionId);

    /**
     * Закрывает сессию; повторный вызов не меняет состояние и не выбрасывает ошибку.
     *
     * @param sessionId идентификатор закрываемой сессии
     */
    void disconnect(SessionId sessionId);
}
