package com.example.agent.exception;

import com.example.agent.api.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将参数和业务异常统一转换为前端可识别的 JSON 错误结构。
 * 业务异常不泄露 Java 堆栈，未预期异常不回传底层消息，避免暴露文件路径、模型配置或内部实现。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /** HTTP 同步调用的最终异常出口，必须保留完整服务端诊断记录。 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 映射可预期的任务业务异常。
     * 任务终态冲突等状态问题使用 409，使前端可以刷新任务后提示用户重新操作。
     */
    @ExceptionHandler(TaskException.class)
    public ResponseEntity<ApiError> handleTask(TaskException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TASK_INVALID" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        log.warn("业务请求被拒绝：errorCode={}，retryable={}，message={}", ex.getCode(), ex.isRetryable(), ex.getMessage());
        return ResponseEntity.status(status).body(new ApiError(null, "FAILED", null, ex.getCode(), ex.getMessage(), ex.isRetryable()));
    }

    /** 将 Bean Validation 失败统一标记为 TASK_INVALID。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex) {
        log.warn("HTTP 参数校验失败：exceptionType={}，message={}", ex.getClass().getName(), ex.getMessage());
        return ResponseEntity.badRequest().body(new ApiError(null, "FAILED", null, "TASK_INVALID", ex.getMessage(), false));
    }

    /**
     * 兜底处理未预期异常。
     * 详细堆栈由服务端日志记录，HTTP 响应使用通用消息以避免暴露内部状态。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // 此处必须打印异常对象而非只打印 message，否则调用链、根因和行号会完全丢失。
        log.error("未处理的 HTTP 请求异常：exceptionType={}，message={}", ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError().body(new ApiError(null, "FAILED", null, "UNKNOWN_ERROR", "服务处理失败", false));
    }
}
