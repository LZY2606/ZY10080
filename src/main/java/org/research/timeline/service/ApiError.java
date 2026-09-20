package org.research.timeline.service;

import java.util.Map;

/**
 * Error categories that map to distinct HTTP status codes and stable error codes,
 * so clients can tell malformed input, state conflicts and internal faults apart.
 */
public class ApiError extends RuntimeException {

    public enum Category {
        INPUT_FORMAT(400, "INPUT_FORMAT"),
        STATE_CONFLICT(409, "STATE_CONFLICT"),
        NOT_FOUND(404, "NOT_FOUND"),
        INTERNAL(500, "INTERNAL");

        public final int httpStatus;
        public final String code;

        Category(int httpStatus, String code) {
            this.httpStatus = httpStatus;
            this.code = code;
        }
    }

    private final Category category;
    private final Map<String, Object> details;

    public ApiError(Category category, String message) {
        this(category, message, null);
    }

    public ApiError(Category category, String message, Map<String, Object> details) {
        super(message);
        this.category = category;
        this.details = details;
    }

    public static ApiError input(String message) {
        return new ApiError(Category.INPUT_FORMAT, message);
    }

    public static ApiError conflict(String message) {
        return new ApiError(Category.STATE_CONFLICT, message);
    }

    public static ApiError conflict(String message, Map<String, Object> details) {
        return new ApiError(Category.STATE_CONFLICT, message, details);
    }

    public static ApiError notFound(String message) {
        return new ApiError(Category.NOT_FOUND, message);
    }

    public static ApiError internal(String message, Throwable cause) {
        return new ApiError(Category.INTERNAL, message + ": " + cause, null);
    }

    public Category category() {
        return category;
    }

    public Map<String, Object> details() {
        return details;
    }
}
