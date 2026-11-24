package org.databasebrowser.core.application;

import java.util.Objects;
import org.databasebrowser.core.port.CsvExportPort;
import org.databasebrowser.core.port.TextFilePort;

/**
 * Файловые порты, общие для всех сессий; они не являются адаптерами базы данных.
 * Запись передаётся в {@link AdapterRegistry} и дальше в {@link Usecases#bind}.
 * Благодаря этому сценарии экспорта CSV и текста работают одинаково для любого адаптера.
 * Оба порта обязательны: конструктор отклоняет {@code null}.
 */
public record ExportPorts(CsvExportPort csv, TextFilePort text) {
    public ExportPorts {
        Objects.requireNonNull(csv);
        Objects.requireNonNull(text);
    }
}
