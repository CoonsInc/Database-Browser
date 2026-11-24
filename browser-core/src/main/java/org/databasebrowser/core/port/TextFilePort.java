package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Exports.TextOptions;

/**
 * Порт записи готового текста в {@link Target}; не является БД-портом и входит в
 * {@code ExportPorts}. Вызывается {@code ExportDdl.saveDdlPreview()} для уже просмотренного
 * DDL и не должен перечитывать базу данных. Адаптер обязан соблюсти кодировку и правило
 * перезаписи из {@link TextOptions}, не изменять содержимое и не раскрывать путь
 * назначения в диагностике.
 */
public interface TextFilePort {
    /**
     * Записывает готовый текст в непрозрачное назначение.
     *
     * @param target назначение, интерпретируемое IO-адаптером
     * @param text текст, который нужно записать без изменений
     * @param options кодировка и правило перезаписи существующего файла
     */
    void writeText(Target target, String text, TextOptions options);
}
