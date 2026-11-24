package org.databasebrowser.core.application.usecase;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.Objects;
import org.databasebrowser.core.application.SessionScope;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Exports.Ddl;
import org.databasebrowser.core.domain.Exports.DdlOptions;
import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Exports.TextOptions;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.port.TextFilePort;

/**
 * Юзкейс строит SQL-предпросмотр по снимку схемы и сохраняет именно его в файл.
 * Используется как {@code Usecases.ddl()}: сначала {@link #previewDdl(DdlOptions)}, затем {@link #saveDdlPreview(Ddl, Target, TextOptions)}.
 * Повторное чтение БД при сохранении не выполняется — пользователь получает тот скрипт, который видел в предпросмотре.
 */
public final class ExportDdl {
    private final SessionScope scope;
    private final TextFilePort files;

    /** Создаёт юзкейс для сессии и порта текстовых файлов; аргументы не могут быть {@code null}. */
    public ExportDdl(SessionScope scope, TextFilePort files) {
        this.scope = Objects.requireNonNull(scope);
        this.files = Objects.requireNonNull(files);
    }

    /**
     * Проверяет {@code options} и возвращает {@link Ddl} для всей схемы или выбранных объектов.
     * Использует текущий снимок {@link SessionScope}; объекты должны существовать в нём и быть известны сессии.
     * Некорректные настройки дают {@link Code#INVALID_INPUT}, отсутствующие объекты — {@link Code#NOT_FOUND}.
     */
    public Ddl previewDdl(DdlOptions options) {
        return scope.work(() -> {
            if (options == null || options.dependencies() == null
                || options.wholeSchema() && !options.selected().isEmpty()
                || !options.wholeSchema() && options.selected().isEmpty())
                throw error(Code.INVALID_INPUT, "Select whole schema or a nonempty object set");
            Snapshot snap = scope.snapshot();
            for (ObjectId oid : options.selected()) {
                scope.requireObject(oid);
                if (snap.objects().stream().noneMatch(o -> o.id().equals(oid)))
                    throw error(Code.NOT_FOUND, "Selected export object not found");
            }
            return scope.ports().ddl().generateDdl(snap, options);
        });
    }

    /**
     * Строит предпросмотр и сохраняет его в {@code target}.
     * Возвращает тот же {@link Ddl}; запись выполняется без повторного чтения БД.
     */
    public Ddl saveDdl(DdlOptions options, Target target, TextOptions textOptions) {
        Ddl ddl = previewDdl(options);
        saveDdlPreview(ddl, target, textOptions);
        return ddl;
    }

    /**
     * Сохраняет готовый {@code ddl} в {@code target} с кодировкой и настройками текста {@code options}.
     * Используется только переданный {@link Ddl}: {@link SessionScope} и база данных не опрашиваются.
     * Требуются ненулевые {@code ddl}, {@code target} и {@code options.charset()}.
     */
    public void saveDdlPreview(Ddl ddl, Target target, TextOptions options) {
        if (ddl == null || target == null || options == null || options.charset() == null)
            throw error(Code.INVALID_INPUT, "DDL, target and text options are required");
        org.databasebrowser.core.domain.Errors.safe(() -> {
            files.writeText(target, ddl.sql(), options);
            return true;
        });
    }
}
