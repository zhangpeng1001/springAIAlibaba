package com.example.agent.model;

import java.util.List;

/**
 * 用户可审阅、可版本化的研究纲要。
 * @param version 单调递增版本号；确认接口用它防止锁定过期页面
 * @param title 面向用户展示的纲要标题，不直接作为文件路径
 * @param goal 研究或学习目标，用于约束 Research 与 Answer 范围
 * @param items 按 order 排序的稳定主题列表；构造时复制以防外部集合后续修改
 */
public record Plan(int version, String title, String goal, List<PlanItem> items) {
    /** 将 null 主题列表转换为空不可变列表，避免恢复/模型异常导致后续 NPE。 */
    public Plan {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
