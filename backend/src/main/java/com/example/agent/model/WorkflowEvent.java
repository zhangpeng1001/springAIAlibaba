package com.example.agent.model;

import java.time.Instant;
import java.util.Map;

/** SSE 与 events.jsonl 共用的事件结构。 */
public record WorkflowEvent(long eventId, String type, String taskId, Instant time,
                            String stage, Map<String, Object> payload) {
    public WorkflowEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
