package com.example.agent.model;

/**
 * Task Analyzer 的结构化输出，taskType 只能是系统支持的四种任务类型之一。
 * @param taskType LEARNING、INTERVIEW_PREPARATION、TECH_RESEARCH 或 KNOWLEDGE_SUMMARY
 * @param target 模型识别的目标方向，仅用于上下文和展示
 * @param goal 模型建议的任务目标，实际范围仍以用户最终锁定 Plan 为准
 */
public record TaskAnalysis(String taskType, String target, String goal) { }
