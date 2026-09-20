package org.research.timeline.util;

import java.util.List;
import java.util.Map;
import org.research.timeline.service.ApiError;

/** Type-safe, fail-fast accessors for parsed JSON maps. */
public final class Maps {

    private Maps() {
    }

    public static Object req(Map<String, Object> map, String key) {
        if (!map.containsKey(key) || map.get(key) == null) {
            throw ApiError.input("Missing required field '" + key + "'");
        }
        return map.get(key);
    }

    public static String str(Map<String, Object> map, String key) {
        Object value = req(map, key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw ApiError.input("Field '" + key + "' must be a non-empty string");
        }
        return s;
    }

    public static String optStr(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String s)) {
            throw ApiError.input("Field '" + key + "' must be a string");
        }
        return s;
    }

    public static int integer(Map<String, Object> map, String key) {
        Object value = req(map, key);
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw ApiError.input("Field '" + key + "' must be an integer");
        }
        long longValue = n.longValue();
        if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw ApiError.input("Field '" + key + "' is out of integer range");
        }
        return (int) longValue;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> map, String key) {
        Object value = req(map, key);
        if (!(value instanceof Map<?, ?>)) {
            throw ApiError.input("Field '" + key + "' must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> optObj(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw ApiError.input("Field '" + key + "' must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {
        Object value = req(map, key);
        if (!(value instanceof List<?>)) {
            throw ApiError.input("Field '" + key + "' must be an array");
        }
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object value, String context) {
        if (!(value instanceof Map<?, ?>)) {
            throw ApiError.input(context + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value, String context) {
        if (!(value instanceof List<?>)) {
            throw ApiError.input(context + " must be an array");
        }
        return (List<Object>) value;
    }

    public static String asStr(Object value, String context) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw ApiError.input(context + " must be a non-empty string");
        }
        return s;
    }
}
