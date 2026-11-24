package org.databasebrowser.core.application.usecase;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.Objects;
import org.databasebrowser.core.application.SessionScope;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.QueryPlans.Mode;
import org.databasebrowser.core.domain.QueryPlans.QueryPlan;
import org.databasebrowser.core.domain.Sql;

/**
 * Юзкейс запрашивает план выполнения SQL в заданном {@link Mode} и проверяет поддержку адаптером.
 * Запрос регистрируется как операция в {@link SessionScope}, как и обычный SQL.
 * Используется как {@code Usecases.explain()}; {@link Mode#ANALYZE} дополнительно требует {@link Capabilities#analyze()}.
 */
public final class ExplainQuery {
    private final SessionScope scope;
    private final ExecuteSql sql;

    /** Создаёт юзкейс для заданной сессии; {@code null} запрещён. */
    public ExplainQuery(SessionScope scope) {
        this.scope = Objects.requireNonNull(scope);
        this.sql = new ExecuteSql(scope);
    }

    /**
     * Проверяет {@code mode} и {@link Capabilities} адаптера, затем возвращает {@link QueryPlan}.
     * Для {@link Mode#ANALYZE} требуется поддержка анализа; иначе результат — {@link Code#UNSUPPORTED}.
     * SQL-запрос выполняется под уникальным {@code OperationId} через {@link ExecuteSql}.
     */
    public QueryPlan explain(Sql.Request request, Mode mode) {
        return scope.work(() -> {
            Capabilities c = scope.ports().info().getCapabilities(scope.session().id());
            if (mode == null)
                throw error(Code.INVALID_INPUT, "Explain mode is required");
            if (!c.queryPlan() || mode == Mode.ANALYZE && !c.analyze())
                throw error(Code.UNSUPPORTED, "Query plan mode unsupported");
            return sql.operation(
                request, () -> scope.ports().plans().explain(scope.session().id(), request, mode));
        });
    }
}
