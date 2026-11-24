package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Ids.ObjectId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Definition;
import org.databasebrowser.core.domain.Schema.Dependencies;
import org.databasebrowser.core.domain.Schema.Snapshot;

public interface SchemaReaderPort {
  Snapshot readSchema(SessionId sessionId);
  Definition readObjectDefinition(SessionId sessionId, ObjectId objectId);
  Dependencies findDependencies(SessionId sessionId, ObjectId objectId);
}
