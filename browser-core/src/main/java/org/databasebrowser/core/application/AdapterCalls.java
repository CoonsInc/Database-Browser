package org.databasebrowser.core.application;

import static org.databasebrowser.core.domain.Errors.error;

import java.util.function.Supplier;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.CoreException;

/** Сохраняет ошибки core и заменяет неожиданную диагностику адаптера. */
public final class AdapterCalls {
    private AdapterCalls() {}

    /** Переводит неожиданные ошибки адаптера в безопасную ошибку core. */
    public static <T> T safely(Supplier<T> call) {
        try {
            return call.get();
        } catch (CoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw error(Code.INTERNAL, "Adapter or contract failure; diagnostics withheld");
        }
    }

    /** Выполняет вызов адаптера, который не возвращает результат. */
    public static void safelyRun(Runnable call) {
        safely(() -> {
            call.run();
            return null;
        });
    }
}
