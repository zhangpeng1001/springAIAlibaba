package com.example.agent.model;

import java.time.Instant;

/**
 * 用户对某个 Plan 版本提出的原始意见及 Agent 生成的摘要。
 * @param planVersion 提交意见时对应的旧 Plan 版本
 * @param message 用户原始自然语言意见，供对话历史和恢复审计使用
 * @param summary Agent 产生的修改摘要，供前端快速显示
 * @param createdAt 意见持久化时间
 */
public record PlanFeedback(int planVersion, String message, String summary, Instant createdAt) { }
