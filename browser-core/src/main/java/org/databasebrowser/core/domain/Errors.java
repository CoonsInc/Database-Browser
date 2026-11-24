package org.databasebrowser.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Контракты ошибок ядра: категории, важность, отдельные проблемы и диагностика адаптера.
 * Класс не создаётся; используйте {@code Problem}, {@code Diagnostic}, {@code CoreException} и
 * {@code safe}. Все вложенные записи хранят неизменяемые копии своих коллекций.
 */
public final class Errors {
    private Errors() {}

    /**
     * Категория ошибки: {@code INVALID_INPUT}, {@code UNKNOWN_ADAPTER}, {@code UNKNOWN_SESSION},
     * {@code WRONG_SESSION}, {@code NOT_FOUND}, {@code UNSUPPORTED}, {@code CONFLICT},
     * {@code CONSTRAINT}, {@code CONNECTION}, {@code BUSY_TIMEOUT}, {@code CANCELLED}, {@code SQL},
     * {@code IO}, {@code INTERNAL}, {@code ALREADY_APPLIED} или {@code OUTCOME_UNKNOWN}.
     * Значения используются в {@code CoreException.code} и как строковый {@code Problem.code}.
     * Код нужен вызывающей стороне, чтобы решить, что делать с ошибкой.
     */
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

    /**
     * Важность проблемы: {@code ERROR} или {@code WARNING}. Значение лежит в
     * {@code Problem.severity} и показывает, блокирует ли проблема операцию.
     */
    public enum Severity { ERROR, WARNING }

    /**
     * Одна машиночитаемая проблема: код, важность, путь, сообщение и дополнительные сведения.
     * {@code code} — строковый код категории, обычно одно из значений {@code Code}; {@code path}
     * указывает место, а {@code details}
     * копируется в {@code Map.copyOf}. Метод {@code error(code, path, message)} создаёт проблему
     * уровня {@code Severity.ERROR} с пустыми {@code details}. Обязательные поля проверяются при
     * создании.
     */
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
    /**
     * Диагностика от адаптера: {@code vendorCode}, {@code sqlState} и {@code safeMessage}.
     * Сырая диагностика поставщика остаётся в адаптере: в ядро передаются только обезличенные
     * сведения без учётных данных и чувствительных значений. {@code Optional} показывает, что код
     * поставщика или SQLSTATE может отсутствовать; {@code CoreException} хранит диагностику
     * отдельно от сообщения об ошибке.
     */
    public record
        Diagnostic(Optional<String> vendorCode, Optional<String> sqlState, String safeMessage) {}

    /**
     * Исключение ядра с кодом {@code Code}, списком {@code Problem} и необязательной
     * {@code Diagnostic}. Список проблем копируется, поэтому изменение исходного списка не влияет
     * на исключение. {@code safe} пробрасывает {@code CoreException} без изменений, сохраняя код и
     * диагностику; {@code problems()} и {@code diagnostic()} дают доступ к деталям.
     */
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

    /**
     * Выполняет {@code call} и нормализует сбои: {@code CoreException} пробрасывается как есть, а
     * любое другое {@code RuntimeException} заменяется на {@code Code.INTERNAL} с сообщением
     * {@code Adapter or contract failure; diagnostics withheld}. Исходное исключение и его
     * диагностика наружу не передаются, чтобы не утекли чувствительные данные. Используйте обёртку
     * для вызовов адаптера и проверки контрактов на границе ядра.
     */
    public static <T> T safe(Supplier<T> call) {
        try {
            return call.get();
        } catch (CoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw error(Code.INTERNAL, "Adapter or contract failure; diagnostics withheld");
        }
    }
}
