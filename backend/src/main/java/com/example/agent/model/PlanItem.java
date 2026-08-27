package com.example.agent.model;

/**
 * 纲要中的一个知识主题，id 是跨版本稳定标识。
 * @param id 用作 Research/Answer/Review Map 键和中间工件文件名，不依赖会变化的标题
 * @param title 用户可读主题标题
 * @param description 主题覆盖范围和业务意图
 * @param order 最终阅读和 Markdown 文件命名前缀的顺序
 * @param required 是否为当前目标必须覆盖的主题
 * @param depth NORMAL/DEEP 等深度提示，由模型理解但不控制流程
 */
public record PlanItem(String id, String title, String description, int order,
                       boolean required, String depth) { }
