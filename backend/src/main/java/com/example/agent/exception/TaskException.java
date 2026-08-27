package com.example.agent.exception;

/**
 * 可映射为 API 错误的业务异常。
 * code 固定为设计约定的错误码，避免客户端通过脆弱的自然语言文本判断失败原因。
 */
public class TaskException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public TaskException(String code, String message) { this(code, message, false); }
    public TaskException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
}
