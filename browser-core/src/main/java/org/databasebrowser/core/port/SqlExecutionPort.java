package org.databasebrowser.core.port;

import org.databasebrowser.core.domain.Ids.OperationId;
import org.databasebrowser.core.domain.Ids.ResultId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Sql;

/**
 * Порт вызывается {@code ExecuteSql} для каждой операции и {@code SessionScope.close()}
 * для отмены активных операций; отмена идёт вне блокировки сессии, поэтому адаптер должен
 * поддерживать её параллельно с выполнением. {@link Sql.Request} уже содержит уникальный
 * {@code OperationId}, проверенные параметры и режим {@code SINGLE_STATEMENT}: адаптер
 * обязан отклонить скрипт целиком, а не выполнять только первый оператор. Запрещены разделение
 * по точке с запятой, скрытое отбрасывание последующих результатов, неявные повторы и
 * переписывание SQL. Отмену нужно зарегистрировать до начала работы движка и корректно
 * обрабатывать гонку регистрации с отменой. Идентификаторы результатов должны быть уникальными
 * {@code UUID}; закрытие соединения освобождает все результаты и незавершённую работу.
 */
public interface SqlExecutionPort {
    /**
     * Выполняет один оператор и регистрирует отмену до начала работы движка.
     *
     * @param sessionId идентификатор открытой сессии
     * @param request запрос с {@code OperationId}, SQL, параметрами и режимом выполнения
     * @return статус, первый батч, статистика и предупреждения; результат может отсутствовать
     */
    Sql.Execution execute(SessionId sessionId, Sql.Request request);

    /**
     * Возвращает следующий ограниченный батч строк ранее выполненного запроса.
     *
     * @param sessionId идентификатор открытой сессии
     * @param resultId идентификатор результата из {@link Sql.Execution}
     * @param batchSize максимальное число строк в батче
     * @return батч с признаком полной выборки
     */
    Sql.Batch fetchRows(SessionId sessionId, ResultId resultId, int batchSize);

    /**
     * Запрашивает отмену активной операции, безопасную при гонке с её регистрацией.
     *
     * @param sessionId идентификатор открытой сессии
     * @param operationId идентификатор операции из {@link Sql.Request}
     * @return было ли запрошено завершение, неактивна ли операция или отмена не поддерживается
     */
    Sql.CancelResult cancel(SessionId sessionId, OperationId operationId);

    /**
     * Освобождает курсор и ресурсы результата; повторный вызов безопасен.
     *
     * @param sessionId идентификатор открытой сессии
     * @param resultId идентификатор закрываемого результата
     */
    void closeResult(SessionId sessionId, ResultId resultId);
}
