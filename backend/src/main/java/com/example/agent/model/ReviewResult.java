package com.example.agent.model;

import java.util.List;

/**
 * 研究或答案审核结果；passed 决定是否进入修复边。
 * @param passed 是否允许 Graph 进入下一阶段
 * @param score 供展示和后续阈值策略使用的质量评分，不直接绕过 passed
 * @param issues 定向修复问题列表；构造时复制避免外部篡改审计结果
 */
public record ReviewResult(boolean passed, double score, List<Issue> issues) {
    public ReviewResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * 审核发现的问题。
     * @param type 问题类别，例如 MISSING、OFF_TOPIC
     * @param severity HIGH/MEDIUM/LOW 等严重级别
     * @param message 给 Repair Agent 的具体修复说明
     */
    public record Issue(String type, String severity, String message) { }
}
