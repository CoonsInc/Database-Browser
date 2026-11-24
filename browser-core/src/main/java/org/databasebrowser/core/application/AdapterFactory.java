package org.databasebrowser.core.application;

import org.databasebrowser.core.domain.Connections.ConnectionSchema;
import org.databasebrowser.core.domain.Connections.ConnectionValues;
import org.databasebrowser.core.domain.Ids.AdapterId;

/**
 * Один адаптер базы данных: описывает настройки и открывает набор сценариев.
 * Реализация знает драйвер и создаёт порты с {@link SessionScope}, но не показывает их UI.
 * UI строит форму по {@link #schema()}, а при открытии соединения реестр вызывает {@link #open}.
 * Одно окно = одна сессия {@code SessionScope}; общие ID подключает {@link AdapterRegistry}.
 * <p>
 * Пример: {@code
 * ConnectionSchema form = factory.schema();
 * Usecases usecases = factory.open("Local", values, exports);
 * }
 */
public interface AdapterFactory {
    AdapterId id();

    String displayName();

    ConnectionSchema schema();

    /**
     * Создаёт сценарии для нового окна из значений, принятых реестром.
     * Возвращает результат {@link Usecases#bind}, чтобы все сценарии делили один {@link SessionScope}.
     */
    Usecases open(String displayName, ConnectionValues values, ExportPorts exports);
}
