package com.example.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户对待确认纲要提出的自然语言修改意见。 */
public record TaskMessageRequest(@NotBlank(message = "修改意见不能为空") @Size(max = 4000, message = "修改意见不能超过 4000 个字符") String message) { }
