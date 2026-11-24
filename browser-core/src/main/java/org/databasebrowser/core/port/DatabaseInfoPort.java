package org.databasebrowser.core.port;

import java.util.*;
import org.databasebrowser.core.domain.Connections.Capabilities;
import org.databasebrowser.core.domain.Connections.DatabaseInfo;
import org.databasebrowser.core.domain.Connections.SupportedType;
import org.databasebrowser.core.domain.Ids.SessionId;

public interface DatabaseInfoPort {
  DatabaseInfo getDatabaseInfo(SessionId sessionId);
  Capabilities getCapabilities(SessionId sessionId);
  List<SupportedType> getSupportedTypes(SessionId sessionId);
}
