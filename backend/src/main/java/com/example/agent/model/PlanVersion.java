package com.example.agent.model;

import java.time.Instant;

/**
 * Plan 的不可变审计记录，旧版本永不覆盖。
 * @param plan 对应版本的完整纲要快照
 * @param changeSummary 本次初始创建或修订的摘要
 * @param createdAt 该版本生成时间
 * @param confirmedAt 显式确认并锁定时的时间；未确认版本为 null
 * @param locked 是否为当前已锁定版本
 */
public record PlanVersion(Plan plan, String changeSummary, Instant createdAt,
                          Instant confirmedAt, boolean locked) { }
