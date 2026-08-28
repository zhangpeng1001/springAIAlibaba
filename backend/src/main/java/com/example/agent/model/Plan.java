package com.example.agent.model;

import java.util.List;

/**
 * 自动生成的一次性知识解答纲要。
 * @param version 初始纲要固定为 1，保留字段便于结构化输出和审计
 * @param title 面向用户展示的纲要标题，不直接作为文件路径
 * @param goal 研究或学习目标，用于约束逐项答案范围
 * @param items 按 order 排序的稳定主题列表；构造时复制以防外部集合后续修改
 */
public record Plan(int version, String title, String goal, List<PlanItem> items) {
    /** 将 null 主题列表转换为空不可变列表，避免恢复/模型异常导致后续 NPE。 */
    public Plan {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
