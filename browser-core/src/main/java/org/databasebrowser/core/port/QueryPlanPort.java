package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.QueryPlans.Mode;
import org.databasebrowser.core.domain.QueryPlans.QueryPlan;
import org.databasebrowser.core.domain.Sql;

/**
 * Порт построения планов запросов для уже открытой сессии. Вызывается {@code ExplainQuery}
 * только после проверки {@code Capabilities.queryPlan()} и {@code Capabilities.analyze()};
 * {@code SessionScope} передаёт тот же {@code OperationId}, что и для выполнения SQL.
 * Адаптер не должен изменять данные: {@link Mode#ESTIMATED} даёт оценочный план,
 * а {@link Mode#ANALYZE} может собирать фактическую статистику, если это разрешено
 * возможностями. План должен соответствовать переданному {@link Sql.Request} и обрабатывать
 * отмену так же, как выполнение SQL.
 */
public interface QueryPlanPort {
    /**
     * Строит план для запроса без изменения пользовательских данных.
     *
     * @param sessionId идентификатор открытой сессии
     * @param request запрос с {@code OperationId}, SQL и параметрами
     * @param mode оценочный или фактический режим построения плана
     * @return план с узлами и исходным выводом адаптера
     */
    QueryPlan explain(SessionId sessionId, Sql.Request request, Mode mode);
}
