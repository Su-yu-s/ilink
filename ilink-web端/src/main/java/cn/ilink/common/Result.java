package cn.ilink.common;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一 API 响应包装类
 * 序列化后的 JSON 结构: {code, message, data, timestamp, extra}
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private Map<String, Object> extra;
    private long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 成功 ====================

    public static <T> Result<T> ok() {
        return new Result<>(200, "操作成功", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    // ==================== 失败 ====================

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> badRequest(String message) {
        return new Result<>(400, message, null);
    }

    public static <T> Result<T> unauthorized() {
        return new Result<>(401, "未登录或登录已过期", null);
    }

    public static <T> Result<T> forbidden() {
        return new Result<>(403, "无权限访问", null);
    }

    public static <T> Result<T> notFound(String message) {
        return new Result<>(404, message != null ? message : "资源不存在", null);
    }

    public static <T> Result<T> error(String message) {
        return fail(message);
    }

    // ==================== 分页辅助 ====================

    public Result<T> withPagination(int page, int size, long total) {
        if (this.extra == null) {
            this.extra = new HashMap<>();
        }
        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", page);
        pagination.put("size", size);
        pagination.put("total", total);
        this.extra.put("pagination", pagination);
        return this;
    }

    public Result<T> withExtra(String key, Object value) {
        if (this.extra == null) {
            this.extra = new HashMap<>();
        }
        this.extra.put(key, value);
        return this;
    }

    public Result<T> withExtra(Map<String, Object> extra) {
        if (extra != null) {
            if (this.extra == null) {
                this.extra = new HashMap<>();
            }
            this.extra.putAll(extra);
        }
        return this;
    }
}
