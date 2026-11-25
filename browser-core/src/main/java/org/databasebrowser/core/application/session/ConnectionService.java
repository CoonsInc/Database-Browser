package org.databasebrowser.core.application.session;

import java.util.List;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.Session;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Ids.SessionId;

/** Открывает и закрывает подключения и предоставляет сведения о них. */
public interface ConnectionService {
    /** Открывает сессию через адаптер из настроек. */
    Session connect(Settings settings);

    /** Возвращает открытые сессии. */
    List<Session> list();

    /** Проверяет, отвечает ли СУБД через указанное подключение. */
    boolean checkConnection(SessionId id);

    /** Получает название и версию СУБД. */
    DatabaseInfo databaseInfo(SessionId id);

    /** Получает возможности адаптера для сессии. */
    Capabilities capabilities(SessionId id);

    /** Закрывает выбранную сессию и связанные ресурсы. */
    void disconnect(SessionId id);
}
