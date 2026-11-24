package org.databasebrowser.core.port;

import java.util.Objects;

/**
 * Запись содержит полный набор инструментов одного адаптера: подключение, метаданные,
 * чтение и изменение схемы, выполнение SQL, планы запросов и генерацию DDL. Её создаёт
 * адаптер и передаёт в {@code SessionScope}; UI не видит этот объект, прикладные сценарии
 * берут из него отдельные порты. {@link CsvExportPort} и {@link TextFilePort} сюда не входят:
 * это не БД-порты, они собраны в {@code ExportPorts}. Все компоненты обязательны,
 * компактный конструктор отвергает {@code null}.
 *
 * @param connection порт открытия, проверки и закрытия сессии
 * @param info порт метаданных и возможностей базы данных
 * @param schema порт чтения снимка схемы и определений объектов
 * @param changes порт подготовки и применения изменений схемы
 * @param sql порт выполнения SQL, выборки батчей, отмены и закрытия результатов
 * @param plans порт построения планов запросов
 * @param ddl порт генерации DDL по снимку схемы
 */
public record DatabasePorts(ConnectionPort connection, DatabaseInfoPort info, SchemaReaderPort schema,
    SchemaChangePort changes, SqlExecutionPort sql, QueryPlanPort plans, DdlExportPort ddl) {
    public DatabasePorts {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(info);
        Objects.requireNonNull(schema);
        Objects.requireNonNull(changes);
        Objects.requireNonNull(sql);
        Objects.requireNonNull(plans);
        Objects.requireNonNull(ddl);
    }
}
