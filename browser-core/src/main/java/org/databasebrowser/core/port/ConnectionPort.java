package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Connections.Opened;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Provider generates a fresh UUID session ID and must not reuse it. disconnect is idempotent,
 * thread safe with cancel, releases results and running work; failures must report safe diagnostics.
 */
public interface ConnectionPort {
  Opened connect(Settings settings);
  boolean checkConnection(SessionId sessionId);
  void disconnect(SessionId sessionId);
}
