package org.databasebrowser.core.domain;

import java.util.Objects;
import java.util.UUID;

/** Непрозрачные идентификаторы, уникальные в пределах экземпляра core. */
public final class Ids {
    private Ids() {}

    public record AdapterId(String value) {
        public AdapterId {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("adapter id");
            }
        }
    }

    public record SessionId(UUID value) {
        public SessionId {
            Objects.requireNonNull(value);
        }
    }

    public record ObjectId(SessionId session, String value) {
        public ObjectId {
            Objects.requireNonNull(session);
            Objects.requireNonNull(value);
        }
    }

    public record OperationId(UUID value) {
        public OperationId {
            Objects.requireNonNull(value);
        }
    }

    public record ResultId(UUID value) {
        public ResultId {
            Objects.requireNonNull(value);
        }
    }

    public record ChangePlanId(UUID value) {
        public ChangePlanId {
            Objects.requireNonNull(value);
        }
    }
}
