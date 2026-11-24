package org.databasebrowser.core.application;

import org.databasebrowser.core.domain.Ids.AdapterId;

/**
 * Сведения, которые UI показывает в списке выбора адаптера.
 * Запись содержит только {@link AdapterId} и отображаемое имя адаптера.
 * Порты и схема настроек сюда не входят: для формы нужен {@link AdapterFactory#schema()}.
 * {@link AdapterRegistry#available()} возвращает список таких записей, отсортированный по ID.
 */
public record AdapterInfo(AdapterId id, String displayName) {}
