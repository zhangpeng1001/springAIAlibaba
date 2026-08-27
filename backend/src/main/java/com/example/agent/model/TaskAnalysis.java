package com.example.agent.model;

/** Task Analyzer 的结构化输出，taskType 只能是系统支持的四种任务类型之一。 */
public record TaskAnalysis(String taskType, String target, String goal) { }
