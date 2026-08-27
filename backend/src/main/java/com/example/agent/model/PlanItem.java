package com.example.agent.model;

/** 纲要中的一个知识主题，id 是跨版本稳定标识。 */
public record PlanItem(String id, String title, String description, int order,
                       boolean required, String depth) { }
