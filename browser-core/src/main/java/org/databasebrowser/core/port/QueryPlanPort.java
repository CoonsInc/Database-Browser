package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.QueryPlans.Mode;
import org.databasebrowser.core.domain.QueryPlans.QueryPlan;
import org.databasebrowser.core.domain.Sql;

public interface QueryPlanPort {
  QueryPlan explain(SessionId sessionId, Sql.Request request, Mode mode);
}
