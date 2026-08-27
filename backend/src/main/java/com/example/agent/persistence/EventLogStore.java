package com.example.agent.persistence;

import com.example.agent.model.WorkflowEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 追加写入 JSONL 事件并支持 SSE 连接建立时的历史回放。 */
@Component
public class EventLogStore {
    private final TaskStateStore stateStore;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public EventLogStore(TaskStateStore stateStore) { this.stateStore = stateStore; }

    /** 写入事件并返回带唯一编号的事件对象。 */
    public synchronized WorkflowEvent append(String taskId, String type, String stage,
                                             java.util.Map<String, Object> payload) {
        try {
            long id = counters.computeIfAbsent(taskId, key -> new AtomicLong(lastId(key))).incrementAndGet();
            WorkflowEvent event = new WorkflowEvent(id, type, taskId, java.time.Instant.now(), stage, payload);
            var path = stateStore.taskDir(taskId).resolve("events.jsonl");
            Files.writeString(path, mapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return event;
        } catch (IOException ex) {
            throw new IllegalStateException("写入事件日志失败", ex);
        }
    }

    /** 返回 eventId 大于 afterId 的事件；格式错误的历史行直接失败，避免静默丢事件。 */
    public synchronized List<WorkflowEvent> after(String taskId, long afterId) {
        var path = stateStore.taskDir(taskId).resolve("events.jsonl");
        if (!Files.exists(path)) return List.of();
        try {
            return Files.readAllLines(path).stream().filter(s -> !s.isBlank()).map(line -> {
                try { return mapper.readValue(line, WorkflowEvent.class); }
                catch (IOException ex) { throw new IllegalStateException("事件日志损坏", ex); }
            }).filter(e -> e.eventId() > afterId).toList();
        } catch (IOException ex) { throw new IllegalStateException("读取事件日志失败", ex); }
    }

    private long lastId(String taskId) { return after(taskId, 0).stream().mapToLong(WorkflowEvent::eventId).max().orElse(0); }
}
