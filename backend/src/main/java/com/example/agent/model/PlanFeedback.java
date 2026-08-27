package com.example.agent.model;

import java.time.Instant;

/** 用户对某个 Plan 版本提出的原始意见及 Agent 生成的摘要。 */
public record PlanFeedback(int planVersion, String message, String summary, Instant createdAt) { }
