package com.example.agent.model;

import java.util.List;

/**
 * 针对一个纲要项生成的 Markdown 内容模型。
 * @param topicId 必须与 PlanItem.id 相同，避免并行主题串写
 * @param title 最终 Markdown 一级标题
 * @param summary 本章摘要
 * @param sections 二级章节列表，构造时复制以稳定后续渲染输入
 */
public record Answer(String topicId, String title, String summary, List<Section> sections) {
    public Answer {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * Markdown 二级章节。
     * @param title 二级标题文本
     * @param content 可直接写入 Markdown 的正文片段
     */
    public record Section(String title, String content) { }
}
