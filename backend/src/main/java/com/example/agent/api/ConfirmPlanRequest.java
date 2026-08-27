package com.example.agent.api;

import jakarta.validation.constraints.Min;

/** 人工确认必须带上用户实际看到的 Plan 版本，防止确认过期内容。 */
public record ConfirmPlanRequest(@Min(value = 1, message = "Plan 版本必须大于 0") int planVersion) { }
