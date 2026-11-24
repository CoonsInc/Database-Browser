package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
import org.databasebrowser.core.domain.Schema.Snapshot;

/**
 * Порт вызывается {@code ExportDdl.previewDdl()} с уже полученным снимком схемы
 * и проверенными идентификаторами объектов. Адаптер не должен перечитывать или изменять БД:
 * он обязан сохранить диалект источника, явно предупредить об отсутствующих определениях
 * и зависимостях и честно отметить {@code independentlyRestorable}. Неподдерживаемые
 * конструкты должны попасть в {@code Ddl.warnings()}, а не быть молча отброшены.
 */
public interface DdlExportPort {
    /**
     * Строит SQL-скрипт DDL по согласованному снимку схемы.
     *
     * @param schemaSnapshot снимок схемы с непрозрачным токеном состояния
     * @param options признак полной схемы, выбранные объекты и политика зависимостей
     * @return скрипт, предупреждения и признак независимой восстановимости
     */
    Ddl generateDdl(Snapshot schemaSnapshot, DdlOptions options);
}
