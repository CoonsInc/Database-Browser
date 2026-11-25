package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Каждая сессия получает уникальный UUID. Повторное отключение безопасно.
 * Отключение совместимо с отменой и освобождает результаты и активные операции.
 * Ошибки содержат безопасную диагностику.
 */
public interface ConnectionPort {
    Opened connect(Settings settings);

    boolean checkConnection(SessionId sessionId);

    void disconnect(SessionId sessionId);
}
