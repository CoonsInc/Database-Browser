package org.databasebrowser.core.port;

import java.util.List;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.SupportedType;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Порт метаданных открытой сессии. Вызывается сценариями {@code InspectDatabase},
 * {@code ExecuteSql}, {@code ExplainQuery} и {@code ChangeSchema} через {@code SessionScope},
 * чтобы получить информацию о продукте, возможностях и поддерживаемых типах без запросов к UI.
 * Адаптер обязан вернуть согласованные данные для {@link SessionId} текущей сессии,
 * не выполнять пользовательский SQL и не раскрывать секреты подключения.
 */
public interface DatabaseInfoPort {
    /**
     * Возвращает базовую информацию о базе данных открытой сессии.
     *
     * @param sessionId идентификатор открытой сессии
     * @return диалект, продукт, версия и атрибуты базы данных
     */
    DatabaseInfo getDatabaseInfo(SessionId sessionId);

    /**
     * Возвращает поддерживаемые адаптером изменения, режимы выполнения, транзакции
     * и построение планов запросов.
     *
     * @param sessionId идентификатор открытой сессии
     * @return возможности, соответствующие этому соединению
     */
    Capabilities getCapabilities(SessionId sessionId);

    /**
     * Возвращает типы данных, которые адаптер умеет отображать в доменную модель.
     *
     * @param sessionId идентификатор открытой сессии
     * @return список поддерживаемых типов с параметрами и описаниями
     */
    List<SupportedType> getSupportedTypes(SessionId sessionId);
}
