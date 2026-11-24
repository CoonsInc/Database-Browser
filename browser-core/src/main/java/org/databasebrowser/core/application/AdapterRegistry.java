package org.databasebrowser.core.application;

import static org.databasebrowser.core.domain.Errors.error;
import static org.databasebrowser.core.domain.Errors.safe;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.databasebrowser.core.domain.Connections.ConnectionSchema;
import org.databasebrowser.core.domain.Connections.ConnectionValues;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Ids.AdapterId;

/**
 * Явный фиксированный список адаптеров без {@code ServiceLoader}: реестр создаётся один раз при запуске.
 * Он хранит уникальные {@link AdapterId}, общие файловые порты и один {@link SharedIds} на все окна.
 * {@link #schema(AdapterId)} даёт UI форму подключения для выбранного адаптера, {@link #available()} — список выбора, а {@link #open} создаёт набор сценариев и подключает его {@link SessionScope} к общим ID.
 * <p>
 * Пример: {@code
 * AdapterRegistry registry = new AdapterRegistry(List.of(factory), exports);
 * ConnectionSchema form = registry.schema(id);
 * Usecases usecases = registry.open(id, "Main", values);
 * }
 */
public final class AdapterRegistry {
    private final Map<AdapterId, AdapterFactory> factories;
    private final ExportPorts exports;
    private final SharedIds ids = new SharedIds();

    public AdapterRegistry(Collection<AdapterFactory> factories, ExportPorts exports) {
        this.exports = Objects.requireNonNull(exports);
        Map<AdapterId, AdapterFactory> m = new HashMap<>();
        for (AdapterFactory f : factories)
            if (m.putIfAbsent(f.id(), f) != null)
                throw error(Code.INVALID_INPUT, "Duplicate adapter ID");
        this.factories = Map.copyOf(m);
    }

    /**
     * Возвращает адаптеры для списка выбора, отсортированные по идентификатору.
     * Никаких соединений метод не открывает.
     */
    public List<AdapterInfo> available() {
        return factories.values()
            .stream()
            .map(f -> new AdapterInfo(f.id(), f.displayName()))
            .sorted(Comparator.comparing(i -> i.id().value()))
            .toList();
    }

    /**
     * Возвращает схему настроек известного адаптера для построения формы в UI.
     * Для неизвестного {@link AdapterId} бросает {@code CoreException} с кодом {@link Code#UNKNOWN_ADAPTER}.
     */
    public ConnectionSchema schema(AdapterId id) {
        AdapterFactory f = factories.get(id);
        if (f == null)
            throw error(Code.UNKNOWN_ADAPTER, "Unknown adapter: " + id);
        return f.schema();
    }

    /**
     * Проверяет имя и значения, находит адаптер и открывает новое окно.
     * Одно окно = один {@link SessionScope}; его {@link SessionScope#attachIds} уже подключил общие ID реестра.
     * Возвращаемые сценарии нужно закрыть через {@link Usecases#close()}.
     */
    public Usecases open(AdapterId id, String displayName, ConnectionValues values) {
        if (displayName == null || displayName.isBlank())
            throw error(Code.INVALID_INPUT, "Connection display name is required");
        if (values == null)
            throw error(Code.INVALID_INPUT, "Connection values are required");
        AdapterFactory f = factories.get(id);
        if (f == null)
            throw error(Code.UNKNOWN_ADAPTER, "Unknown adapter: " + id);
        Usecases usecases = safe(() -> f.open(displayName, values, exports));
        if (usecases == null)
            throw error(Code.INTERNAL, "Adapter returned no use cases");
        usecases.scope().attachIds(ids);
        return usecases;
    }
}
