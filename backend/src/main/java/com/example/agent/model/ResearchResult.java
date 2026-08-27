package com.example.agent.model;

import java.util.List;

/**
 * LLM 针对单个 PlanItem 生成的结构化研究结果。
 * @param topicId 必须与 PlanItem.id 相同，Java 会校验以防模型写错主题
 * @param topic 当前主题显示名
 * @param details 可展开的研究子项；构造时复制保证状态快照不可被外部修改
 */
public record ResearchResult(String topicId, String topic, List<ResearchDetail> details) {
    public ResearchResult {
        details = details == null ? List.of() : List.copyOf(details);
    }

    /**
     * 研究结果中的可展开细节。
     * @param id 当前主题内的细节稳定标识
     * @param title 子知识点标题
     * @param questions 写作阶段必须覆盖的引导问题
     */
    public record ResearchDetail(String id, String title, List<String> questions) {
        /** 将 null 问题列表规范为空不可变列表，供审核器安全判断遗漏。 */
        public ResearchDetail {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }
}
