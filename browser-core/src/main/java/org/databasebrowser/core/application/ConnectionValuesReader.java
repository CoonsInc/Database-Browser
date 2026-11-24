package org.databasebrowser.core.application;

import static org.databasebrowser.core.domain.Errors.error;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.databasebrowser.core.domain.Connections.ConnectionValues;
import org.databasebrowser.core.domain.Errors.Code;

/**
 * Мост от универсальной карты {@link ConnectionValues} к конкретным {@link Path}, {@code boolean}, {@code int} и секретам.
 * Пустые строки считаются отсутствием значения; исключение с кодом {@link Code#INVALID_INPUT} бросается только для обязательных параметров и некорректных значений.
 * Адаптер вызывает методы этого класса внутри {@link AdapterFactory#open} и передаёт результат в свои порты.
 * <p>
 * Пример: {@code
 * Path file = ConnectionValuesReader.path(values, "path");
 * boolean wal = ConnectionValuesReader.flag(values, "wal", false);
 * String password = ConnectionValuesReader.secret(values, "password");
 * }
 */
public final class ConnectionValuesReader {
    private ConnectionValuesReader() {}

    /**
     * Возвращает непустое значение свойства или бросает ошибку с кодом {@link Code#INVALID_INPUT}.
     */
    public static String required(ConnectionValues v, String key) {
        String raw = v.properties().get(key);
        if (raw == null || raw.isBlank())
            throw error(Code.INVALID_INPUT, "Missing parameter: " + key);
        return raw;
    }

    /**
     * Возвращает значение свойства или {@code fallback}, если оно отсутствует или пусто.
     */
    public static String optional(ConnectionValues v, String key, String fallback) {
        String raw = v.properties().get(key);
        return raw == null || raw.isBlank() ? fallback : raw;
    }

    /**
     * Преобразует обязательное свойство в {@link Path}.
     * Некорректный путь даёт ошибку с кодом {@link Code#INVALID_INPUT}.
     */
    public static Path path(ConnectionValues v, String key) {
        try {
            return Path.of(required(v, key));
        } catch (InvalidPathException e) {
            throw error(Code.INVALID_INPUT, "Invalid path for: " + key);
        }
    }

    /**
     * Читает {@code boolean}: пустое значение даёт {@code fallback}, любое другое значение, кроме {@code true}, даёт {@code false}.
     */
    public static boolean flag(ConnectionValues v, String key, boolean fallback) {
        String raw = optional(v, key, null);
        return raw == null ? fallback : Boolean.parseBoolean(raw);
    }

    /**
     * Читает {@code int}: пустое значение даёт {@code fallback}, нечисло — ошибку {@link Code#INVALID_INPUT}.
     */
    public static int number(ConnectionValues v, String key, int fallback) {
        String raw = optional(v, key, null);
        if (raw == null)
            return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw error(Code.INVALID_INPUT, "Invalid number for: " + key);
        }
    }

    /**
     * Возвращает обязательный секрет из {@link ConnectionValues#secrets()}, а не из обычных свойств.
     */
    public static String secret(ConnectionValues v, String key) {
        String raw = v.secrets().get(key);
        if (raw == null || raw.isBlank())
            throw error(Code.INVALID_INPUT, "Missing secret: " + key);
        return raw;
    }
}
