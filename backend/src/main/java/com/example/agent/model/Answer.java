package com.example.agent.model;

import java.util.List;

/** 针对一个研究主题生成的 Markdown 内容模型。 */
public record Answer(String topicId, String title, String summary, List<Section> sections) {
    public Answer {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public record Section(String title, String content) { }
}
