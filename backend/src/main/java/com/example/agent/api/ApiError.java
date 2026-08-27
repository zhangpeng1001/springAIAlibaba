package com.example.agent.api;

/**
 * 统一错误响应，客户端可根据 errorCode 和 retryable 展示下一步操作。
 * @param taskId 关联任务；参数校验失败时可能为空
 * @param status 面向客户端的失败状态
 * @param stage 可定位的工作流阶段；请求前校验失败时可能为空
 * @param errorCode 稳定业务错误码，不要求客户端解析 message
 * @param message 简要中文错误说明
 * @param retryable 当前状态下是否建议重试
 */
public record ApiError(String taskId, String status, String stage, String errorCode,
                       String message, boolean retryable) { }
