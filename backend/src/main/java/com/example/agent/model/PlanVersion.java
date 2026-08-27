package com.example.agent.model;

import java.time.Instant;

/** Plan 的不可变审计记录，旧版本永不覆盖。 */
public record PlanVersion(Plan plan, String changeSummary, Instant createdAt,
                          Instant confirmedAt, boolean locked) { }
