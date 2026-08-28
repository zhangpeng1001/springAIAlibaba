package com.example.agent;

import com.example.agent.llm.TemplateLlmService;
import com.example.agent.model.Plan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证离线模型可以直接生成唯一的初始纲要，不再存在 Plan 对话版本。 */
class PlanConversationTest {
    @Test
    void createsSingleInitialPlan() {
        Plan plan = new TemplateLlmService().draftPlan("如何学习 Java？");
        assertEquals(1, plan.version());
        assertFalse(plan.items().isEmpty());
    }
}
