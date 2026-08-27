package com.example.agent.api;

/** 统一错误响应，客户端可根据 errorCode 和 retryable 展示下一步操作。 */
public record ApiError(String taskId, String status, String stage, String errorCode,
                       String message, boolean retryable) { }
