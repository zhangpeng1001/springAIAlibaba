package com.example.agent.model;

import java.time.Instant;
import java.util.Map;

/**
 * SSE 与 events.jsonl 共用的事件结构。
 * @param eventId 单任务单调递增编号；客户端据此去重并从断点回放
 * @param type 事件类型，例如 PLAN_WAITING_USER、TASK_SUCCESS
 * @param taskId 事件所属任务
 * @param time 服务端记录事件的时间
 * @param stage 产生事件的工作流节点
 * @param payload 前端展示所需的最小结构化上下文，构造时复制以避免发布后被修改
 */
public record WorkflowEvent(long eventId, String type, String taskId, Instant time,
                            String stage, Map<String, Object> payload) {
    /** 将 null payload 规范为空不可变 Map，避免 SSE 序列化或回放出现空指针。 */
    public WorkflowEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
