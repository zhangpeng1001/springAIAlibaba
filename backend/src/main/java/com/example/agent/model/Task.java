package com.example.agent.model;

import java.time.Instant;

/**
 * 任务列表使用的轻量视图。
 * 完整可恢复数据保存在 AgentState；该模型避免首页历史列表传输全部研究和答案正文。
 */
public record Task(String taskId, String question, TaskStatus status, String currentNode,
                   int planVersion, Instant createdAt, Instant updatedAt) {
    /** 将完整状态转换为无敏感正文的列表摘要。 */
    public static Task from(AgentState state) {
        return new Task(state.getTaskId(), state.getQuestion(), state.getStatus(), state.getCurrentNode(),
                state.getPlanVersion(), state.getCreatedAt(), state.getUpdatedAt());
    }
}
