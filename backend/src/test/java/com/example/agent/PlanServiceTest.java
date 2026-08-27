package com.example.agent;

import com.example.agent.llm.TemplateLlmService;
import com.example.agent.model.Plan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证离线模型提供的 Plan 结构满足工作流的基本版本约束。 */
class PlanServiceTest {
    private final TemplateLlmService llm = new TemplateLlmService();

    @Test
    void draftPlanCreatesFirstVersionWithTopics() {
        Plan plan = llm.draftPlan("如何学习 Java？");
        assertEquals(1, plan.version());
        assertFalse(plan.items().isEmpty());
        assertTrue(plan.items().stream().allMatch(item -> !item.id().isBlank()));
    }
}
