package com.example.agent.model;

/**
 * 任务生命周期状态。
 * 状态由 Java 工作流控制，不允许由 LLM 直接修改，避免模型输出影响程序路由。
 */
public enum TaskStatus {
    CREATED,
    ANALYZING,
    PLAN_DRAFTING,
    WAITING_USER_PLAN,
    PLAN_REVISING,
    PLAN_LOCKED,
    RESEARCHING,
    RESEARCH_REVIEWING,
    RESEARCH_REPAIRING,
    ANSWER_GENERATING,
    ANSWER_REVIEWING,
    ANSWER_REPAIRING,
    TITLE_GENERATING,
    FILE_GENERATING,
    SUCCESS,
    FAILED,
    CANCELLED
}
