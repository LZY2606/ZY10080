package langalign;

import java.util.Map;

/** 业务错误：区分 输入格式(400) / 状态冲突(409) / 未找到(404) / 内部故障(500)。 */
public class ApiException extends RuntimeException {
    public static final String INPUT_FORMAT = "INPUT_FORMAT";
    public static final String STATE_CONFLICT = "STATE_CONFLICT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL = "INTERNAL";

    public final int status;
    public final String type;
    public final Map<String, Object> details;

    public ApiException(int status, String type, String message) {
        this(status, type, message, null);
    }

    public ApiException(int status, String type, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.type = type;
        this.details = details;
    }

    public static ApiException badInput(String message) { return new ApiException(400, INPUT_FORMAT, message); }
    public static ApiException conflict(String message) { return new ApiException(409, STATE_CONFLICT, message); }
    public static ApiException conflict(String message, Map<String, Object> details) { return new ApiException(409, STATE_CONFLICT, message, details); }
    public static ApiException notFound(String message) { return new ApiException(404, NOT_FOUND, message); }
}
