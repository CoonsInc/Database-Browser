package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Changes.ApplyResult;
import org.databasebrowser.core.domain.Changes.Prepared;
import org.databasebrowser.core.domain.Changes.Request;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Preparation is read-only and releases database locks before return.
 * applyChange executes exactly the recipe returned by prepareChange, under adapter protection;
 * rechecks schema AND data before mutation. Staleness returns CONFLICT, never replans.
 * Recipe owns no live resources. Unknown outcomes must be reported, never retried automatically.
 */
public interface SchemaChangePort {
    Prepared prepareChange(SessionId sessionId, Request request);

    ApplyResult applyChange(SessionId sessionId, Prepared plan);
}
