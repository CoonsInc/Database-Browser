package org.databasebrowser.core.application;

import java.util.Objects;
import org.databasebrowser.core.application.usecase.ChangeSchema;
import org.databasebrowser.core.application.usecase.ExecuteSql;
import org.databasebrowser.core.application.usecase.ExplainQuery;
import org.databasebrowser.core.application.usecase.ExportCsv;
import org.databasebrowser.core.application.usecase.ExportDdl;
import org.databasebrowser.core.application.usecase.InspectDatabase;
import org.databasebrowser.core.application.usecase.ReadSchema;

/**
 * Готовые сценарии для одного открытого окна базы данных; порты остаются скрытыми внутри.
 * Адаптер создаёт набор через {@link #bind}, а UI вызывает {@link #inspect()}, {@link #read()}, {@link #change()}, {@link #sql()}, {@link #explain()}, {@link #ddl()} и {@link #csv()}.
 * Одно окно = один {@link SessionScope}; {@link #close()} закрывает именно эту сессию.
 * <p>
 * Пример: {@code
 * Usecases uc = Usecases.bind(new SessionScope(session, ports, rules), exports);
 * uc.read().readSchema();
 * uc.close();
 * }
 */
public record Usecases(InspectDatabase inspect, ReadSchema read, ChangeSchema change, ExecuteSql sql,
    ExplainQuery explain, ExportDdl ddl, ExportCsv csv, SessionScope scope)
    implements AutoCloseable {
    public Usecases {
        Objects.requireNonNull(inspect);
        Objects.requireNonNull(read);
        Objects.requireNonNull(change);
        Objects.requireNonNull(sql);
        Objects.requireNonNull(explain);
        Objects.requireNonNull(ddl);
        Objects.requireNonNull(csv);
        Objects.requireNonNull(scope);
    }

    /**
     * Удобная фабрика для адаптеров.
     * Собирает все сценарии вокруг одного {@link SessionScope} и общих {@link ExportPorts}.
     * Адаптеру достаточно создать scope и вернуть результат из {@link AdapterFactory#open}.
     */
    public static Usecases bind(SessionScope scope, ExportPorts exports) {
        return new Usecases(
            new InspectDatabase(scope),
            new ReadSchema(scope),
            new ChangeSchema(scope),
            new ExecuteSql(scope),
            new ExplainQuery(scope),
            new ExportDdl(scope, exports.text()),
            new ExportCsv(exports.csv()),
            scope);
    }

    /**
     * Закрывает единственный связанный {@link SessionScope}.
     * Повторное отключение соединения не выполняется.
     */
    @Override
    public void close() {
        scope.close();
    }
}
