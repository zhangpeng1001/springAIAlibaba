package com.example.agent.exception;

import com.example.agent.api.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将参数和业务异常统一转换为前端可识别的 JSON 错误结构。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(TaskException.class)
    public ResponseEntity<ApiError> handleTask(TaskException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TASK_INVALID", "PLAN_CONFIRM_FAILED", "PLAN_REVISION_FAILED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ApiError(null, "FAILED", null, ex.getCode(), ex.getMessage(), ex.isRetryable()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex) {
        return ResponseEntity.badRequest().body(new ApiError(null, "FAILED", null, "TASK_INVALID", ex.getMessage(), false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        return ResponseEntity.internalServerError().body(new ApiError(null, "FAILED", null, "UNKNOWN_ERROR", "服务处理失败", false));
    }
}
