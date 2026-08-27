package com.example.agent.api;

/**
 * 异步操作的受理回执；详细进度通过 SSE 和 GET 接口获取。
 * @param taskId 服务端生成的任务标识
 * @param status 接收请求时的状态提示，不替代后续真实任务状态
 */
public record AcceptedTaskResponse(String taskId, String status) { }
