package com.example.agent;

import com.example.agent.llm.TemplateLlmService;
import com.example.agent.model.Plan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证修改意见创建新版本，不会覆盖传入的旧 Plan。 */
class PlanConversationTest {
    @Test
    void revisionIncrementsVersionAndKeepsOriginalPlan() {
        TemplateLlmService llm = new TemplateLlmService();
        Plan v1 = llm.draftPlan("如何学习 Java？");
        Plan v2 = llm.revisePlan("如何学习 Java？", v1, "增加 Docker");
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertTrue(v2.items().stream().anyMatch(item -> item.title().contains("Docker")));
    }
}
