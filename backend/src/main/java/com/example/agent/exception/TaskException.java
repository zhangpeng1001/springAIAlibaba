package com.example.agent.exception;

/**
 * 可映射为 API 错误的业务异常。
 * code 固定为设计约定的错误码，避免客户端通过脆弱的自然语言文本判断失败原因。
 */
public class TaskException extends RuntimeException {
    /** 稳定业务错误码，前端/测试根据该值判断失败类别而不是解析中文异常文案。 */
    private final String code;
    /** 是否建议调用方在不改变用户输入的前提下重试，例如临时文件系统错误。 */
    private final boolean retryable;

    /** 创建不可重试业务异常。 */
    public TaskException(String code, String message) { this(code, message, false); }

    /**
     * 创建带错误码和重试语义的业务异常。
     * @param code 设计约定的错误码
     * @param message 面向用户和日志的简要错误说明
     * @param retryable 是否可在相同状态下安全重试
     */
    public TaskException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
    /** @return 稳定业务错误码 */
    public String getCode() { return code; }
    /** @return 当前失败是否可安全重试 */
    public boolean isRetryable() { return retryable; }
}
