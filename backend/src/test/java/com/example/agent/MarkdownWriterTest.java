package com.example.agent;

import com.example.agent.file.MarkdownWriter;
import com.example.agent.model.Answer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Markdown 渲染测试保证结构化章节会转换成可阅读的标题层级。 */
class MarkdownWriterTest {
    @Test
    void rendersHeadingSummaryAndSections() {
        String markdown = new MarkdownWriter().answer(new Answer("T-1", "Java 基础", "摘要", List.of(new Answer.Section("语法", "内容"))));
        assertTrue(markdown.contains("# Java 基础"));
        assertTrue(markdown.contains("## 语法"));
        assertTrue(markdown.contains("内容"));
    }
}
