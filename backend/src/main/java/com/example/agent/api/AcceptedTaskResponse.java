package com.example.agent.api;

/** 异步操作的受理回执；详细进度通过 SSE 和 GET 接口获取。 */
public record AcceptedTaskResponse(String taskId, String status) { }
