package org.databasebrowser.core.application.usecase;

import java.util.List;
import java.util.Objects;
import org.databasebrowser.core.application.SessionScope;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.SupportedType;

/**
 * Юзкейс возвращает статус соединения, метаданные БД и поддерживаемые возможности без изменений данных и схемы.
 * Все методы привязаны к {@link SessionScope} текущего окна.
 * Используется как {@code Usecases.inspect()}; порты остаются скрытыми от вызывающего кода.
 */
public final class InspectDatabase {
    private final SessionScope scope;

    /** Создаёт юзкейс для заданной сессии; {@code null} запрещён. */
    public InspectDatabase(SessionScope scope) {
        this.scope = Objects.requireNonNull(scope);
    }

    /** Проверяет соединение текущей сессии. Возвращает {@code true} при подтверждённой доступности и {@code false} иначе. */
    public boolean checkConnection() {
        return scope.work(() -> scope.ports().connection().checkConnection(scope.session().id()));
    }

    /** Возвращает {@link DatabaseInfo} открытой БД. Метод только читает метаданные через порт информации. */
    public DatabaseInfo databaseInfo() {
        return scope.work(() -> scope.ports().info().getDatabaseInfo(scope.session().id()));
    }

    /** Возвращает {@link Capabilities} адаптера для текущей сессии: режимы, транзакции и возможности планов. */
    public Capabilities capabilities() {
        return scope.work(() -> scope.ports().info().getCapabilities(scope.session().id()));
    }

    /** Возвращает неизменяемый список {@link SupportedType}, объявленных адаптером. Порядок задаётся адаптером. */
    public List<SupportedType> supportedTypes() {
        return scope.work(
            () -> List.copyOf(scope.ports().info().getSupportedTypes(scope.session().id())));
    }
}
