package org.databasebrowser.core.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.databasebrowser.core.domain.Ids.ChangePlanId;
import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;

/**
 * Владение ID в масштабах реестра.
 * Это глобальная уникальность ID между окнами: повторный claim не меняет владельца.
 * Записи не удаляются после использования, поэтому ID никогда не переиспользуются.
 * Один экземпляр передаётся всем {@link SessionScope} через {@link AdapterRegistry#open}; методы пакетно-видимы и вызываются только внутри пакета.
 */
public final class SharedIds {
    private final ConcurrentMap<OperationId, SessionId> operations = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResultId, SessionId> results = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChangePlanId, SessionId> plans = new ConcurrentHashMap<>();

    boolean claimOperation(OperationId id, SessionId owner) {
        return operations.putIfAbsent(id, owner) == null;
    }

    boolean claimResult(ResultId id, SessionId owner) {
        return results.putIfAbsent(id, owner) == null;
    }

    boolean claimPlan(ChangePlanId id, SessionId owner) {
        return plans.putIfAbsent(id, owner) == null;
    }

    boolean knownOperation(OperationId id) {
        return operations.containsKey(id);
    }

    boolean knownResult(ResultId id) {
        return results.containsKey(id);
    }

    boolean knownPlan(ChangePlanId id) {
        return plans.containsKey(id);
    }

    boolean ownedOperation(OperationId id, SessionId session) {
        return session.equals(operations.get(id));
    }
}
