package org.databasebrowser.core.application.usecase;

import java.util.Objects;
import org.databasebrowser.core.application.SessionScope;
import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Schema.Definition;
import org.databasebrowser.core.domain.Schema.Dependencies;
import org.databasebrowser.core.domain.Schema.Snapshot;

/**
 * Юзкейс читает снимок схемы, определение объекта и зависимости выбранного объекта.
 * Все чтения привязаны к {@link SessionScope} открытого окна.
 * Используется как {@code Usecases.read()}; схема и данные не изменяются.
 */
public final class ReadSchema {
    private final SessionScope scope;

    /** Создаёт юзкейс для заданной сессии; {@code null} запрещён. */
    public ReadSchema(SessionScope scope) {
        this.scope = Objects.requireNonNull(scope);
    }

    /** Возвращает текущий {@link Snapshot} схемы сессии. Чтение не изменяет схему и не открывает новое соединение. */
    public Snapshot readSchema() {
        return scope.work(scope::snapshot);
    }

    /** Возвращает {@link Definition} объекта {@code object}. Идентификатор должен быть известен текущей сессии. */
    public Definition readObjectDefinition(ObjectId object) {
        scope.requireObject(object);
        return scope.work(() -> scope.ports().schema().readObjectDefinition(scope.session().id(), object));
    }

    /** Возвращает {@link Dependencies} объекта {@code object}. Результат зависит от состояния адаптера на момент вызова. */
    public Dependencies findDependencies(ObjectId object) {
        scope.requireObject(object);
        return scope.work(() -> scope.ports().schema().findDependencies(scope.session().id(), object));
    }
}
