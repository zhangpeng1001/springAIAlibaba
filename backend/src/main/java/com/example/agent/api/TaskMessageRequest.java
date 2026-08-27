package com.example.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户对待确认纲要提出的自然语言修改意见。
 * @param message 单轮意见；它只能触发 Plan 修订，不能替代显式确认按钮
 */
public record TaskMessageRequest(@NotBlank(message = "修改意见不能为空") @Size(max = 4000, message = "修改意见不能超过 4000 个字符") String message) { }
