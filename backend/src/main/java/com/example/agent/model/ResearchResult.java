package com.example.agent.model;

import java.util.List;

/** LLM 针对单个 PlanItem 生成的结构化研究结果。 */
public record ResearchResult(String topicId, String topic, List<ResearchDetail> details) {
    public ResearchResult {
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** 研究结果中的可展开细节。 */
    public record ResearchDetail(String id, String title, List<String> questions) {
        public ResearchDetail {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }
}
