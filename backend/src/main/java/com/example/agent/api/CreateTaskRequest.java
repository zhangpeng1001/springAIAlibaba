package com.example.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建任务的输入。
 * @param question 原始用户问题；限制长度以保护 Prompt、JSON 状态文件和事件日志
 */
public record CreateTaskRequest(@NotBlank(message = "问题不能为空") @Size(max = 2000, message = "问题不能超过 2000 个字符") String question) { }
