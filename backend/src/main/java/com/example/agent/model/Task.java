package com.example.agent.model;

import java.time.Instant;

/**
 * 任务列表使用的轻量视图。
 * 完整可恢复数据保存在 AgentState；该模型避免首页历史列表传输全部研究和答案正文。
 * @param taskId 任务标识
 * @param question 原始问题，用作历史列表标题
 * @param status 当前生命周期状态
 * @param currentNode 最近工作流节点，便于列表展示进度
 * @param planVersion 当前纲要版本
 * @param createdAt 创建时间
 * @param updatedAt 最后状态修改时间
 */
public record Task(String taskId, String question, TaskStatus status, String currentNode,
                   int planVersion, Instant createdAt, Instant updatedAt) {
    /**
     * 将完整状态转换为无敏感正文的列表摘要。
     * 研究和答案正文仅在用户进入任务详情页后按 taskId 单独读取，避免首页接口变得巨大。
     */
    public static Task from(AgentState state) {
        return new Task(state.getTaskId(), state.getQuestion(), state.getStatus(), state.getCurrentNode(),
                state.getPlanVersion(), state.getCreatedAt(), state.getUpdatedAt());
    }
}
