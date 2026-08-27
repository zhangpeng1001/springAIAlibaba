package com.example.agent.model;

import java.util.List;

/** 研究或答案审核结果；passed 决定是否进入修复边。 */
public record ReviewResult(boolean passed, double score, List<Issue> issues) {
    public ReviewResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public record Issue(String type, String severity, String message) { }
}
