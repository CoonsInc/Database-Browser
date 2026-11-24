package org.databasebrowser.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Errors {
    private Errors() {}

    public enum Code {
        INVALID_INPUT,
        UNKNOWN_ADAPTER,
        UNKNOWN_SESSION,
        WRONG_SESSION,
        NOT_FOUND,
        UNSUPPORTED,
        CONFLICT,
        CONSTRAINT,
        CONNECTION,
        BUSY_TIMEOUT,
        CANCELLED,
        SQL,
        IO,
        INTERNAL,
        ALREADY_APPLIED,
        OUTCOME_UNKNOWN
    }

    public enum Severity { ERROR, WARNING }

    public record Problem(
        String code, Severity severity, String path, String message, Map<String, String> details) {
        public Problem {
            Objects.requireNonNull(code);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(path);
            Objects.requireNonNull(message);
            details = Map.copyOf(details);
        }

        public static Problem error(String code, String path, String message) {
            return new Problem(code, Severity.ERROR, path, message, Map.of());
        }
    }
    /** Adapter supplies only diagnostics already redacted of credentials and sensitive values. */
    public record
        Diagnostic(Optional<String> vendorCode, Optional<String> sqlState, String safeMessage) {}

    public static final class CoreException extends RuntimeException {
        private final Code code;
        private final List<Problem> problems;
        private final Optional<Diagnostic> diagnostic;

        public CoreException(Code code, String message) {
            this(code, message, List.of(), Optional.empty());
        }

        public CoreException(
            Code code, String message, List<Problem> problems, Optional<Diagnostic> diagnostic) {
            super(message);
            this.code = Objects.requireNonNull(code);
            this.problems = List.copyOf(problems);
            this.diagnostic = Objects.requireNonNull(diagnostic);
        }

        public Code code() {
            return code;
        }

        public List<Problem> problems() {
            return problems;
        }

        public Optional<Diagnostic> diagnostic() {
            return diagnostic;
        }
    }

    public static CoreException error(Code code, String message) {
        return new CoreException(code, message);
    }
}
