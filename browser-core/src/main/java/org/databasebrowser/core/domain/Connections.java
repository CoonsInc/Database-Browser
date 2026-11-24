package org.databasebrowser.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.SessionId;

public final class Connections {
    private Connections() {}
    /**
     * No diagnostic rendering of either normal or secret properties (URLs can also contain
     * secrets).
     */

    public record Settings(AdapterId adapter, String displayName, Map<String, String> properties,
        Map<String, String> secrets) {
        public Settings {
            Objects.requireNonNull(adapter);
            properties = Map.copyOf(properties);
            secrets = Map.copyOf(secrets);
        }

        @Override
        public String toString() {
            return "Settings[adapter=" + adapter + ", properties=<redacted>, secrets=<redacted>]";
        }
    }

    public record Session(SessionId id, AdapterId adapter, String displayName, DatabaseInfo info) {}

    public record Opened(SessionId id, DatabaseInfo info) {}

    public record DatabaseInfo(
        AdapterId dialect, String product, String version, Map<String, String> attributes) {
        public DatabaseInfo {
            attributes = Map.copyOf(attributes);
        }
    }

    public enum Support { DIRECT, MIGRATION, UNSUPPORTED }

    public enum ChangeKind {
        CREATE_TABLE,
        DROP_TABLE,
        ADD_COLUMN,
        ALTER_COLUMN,
        DROP_COLUMN,
        ADD_CONSTRAINT,
        DROP_CONSTRAINT,
        CREATE_INDEX,
        DROP_INDEX,
        CREATE_VIEW,
        ALTER_VIEW,
        DROP_VIEW
    }

    public enum ExecutionMode { SINGLE_STATEMENT }

    public enum TransactionPolicy { AUTOCOMMIT, ATOMIC }

    public record Capabilities(Map<ChangeKind, Support> changes, boolean queryPlan, boolean analyze,
        Set<ExecutionMode> executionModes, Set<TransactionPolicy> transactions) {
        public Capabilities {
            changes = Map.copyOf(changes);
            executionModes = Set.copyOf(executionModes);
            transactions = Set.copyOf(transactions);
        }

        public Support support(ChangeKind kind) {
            return changes.getOrDefault(kind, Support.UNSUPPORTED);
        }
    }
    public record
        SupportedType(String name, List<String> parameterNames, Optional<String> description) {
        public SupportedType {
            parameterNames = List.copyOf(parameterNames);
        }
    }
    /** Adapter comparison may be case sensitive or follow database identifier semantics. */
    @FunctionalInterface

    public interface IdentifierRules {
        boolean equivalent(String left, String right);
    }
}
