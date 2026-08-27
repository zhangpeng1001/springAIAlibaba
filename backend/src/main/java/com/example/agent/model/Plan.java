package com.example.agent.model;

import java.util.List;

/** 用户可审阅、可版本化的研究纲要。 */
public record Plan(int version, String title, String goal, List<PlanItem> items) {
    public Plan {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
