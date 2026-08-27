package com.example.agent.service;

import com.example.agent.model.AgentState;
import com.example.agent.model.WorkflowEvent;
import com.example.agent.persistence.EventLogStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 连接管理器。
 * 事件先写磁盘日志再推送内存订阅者，因此浏览器断线不会丢失任务的审计记录。
 */
@Service
public class TaskEventService {
    private final EventLogStore eventLogStore;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>> emitters = new ConcurrentHashMap<>();

    public TaskEventService(EventLogStore eventLogStore) { this.eventLogStore = eventLogStore; }

    /** 记录事件并立即分发给当前在线客户端。 */
    public WorkflowEvent publish(String taskId, String type, String stage, Map<String, Object> payload) {
        WorkflowEvent event = eventLogStore.append(taskId, type, stage, payload);
        emitters.getOrDefault(taskId, new ConcurrentHashMap<>()).forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(event.eventId())).name(event.type())
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                emitter.complete();
                emitters.getOrDefault(taskId, new ConcurrentHashMap<>()).remove(id);
            }
        });
        return event;
    }

    /** 建立连接，先发送当前状态快照，再重放 afterId 之后的事件。 */
    public SseEmitter connect(AgentState snapshot, long afterId) {
        SseEmitter emitter = new SseEmitter(0L);
        String connectionId = java.util.UUID.randomUUID().toString();
        emitters.computeIfAbsent(snapshot.getTaskId(), ignored -> new ConcurrentHashMap<>()).put(connectionId, emitter);
        emitter.onCompletion(() -> remove(snapshot.getTaskId(), connectionId));
        emitter.onTimeout(() -> remove(snapshot.getTaskId(), connectionId));
        try {
            emitter.send(SseEmitter.event().name("TASK_SNAPSHOT").data(snapshot, MediaType.APPLICATION_JSON));
            for (WorkflowEvent event : eventLogStore.after(snapshot.getTaskId(), afterId)) {
                emitter.send(SseEmitter.event().id(String.valueOf(event.eventId())).name(event.type())
                        .data(event, MediaType.APPLICATION_JSON));
            }
        } catch (Exception ex) {
            remove(snapshot.getTaskId(), connectionId);
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    private void remove(String taskId, String connectionId) {
        emitters.getOrDefault(taskId, new ConcurrentHashMap<>()).remove(connectionId);
    }
}
