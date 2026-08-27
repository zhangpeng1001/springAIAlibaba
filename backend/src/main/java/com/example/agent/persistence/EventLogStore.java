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

/**
 * 追加写入 JSONL 事件并支持 SSE 连接建立时的历史回放。
 *
 * <p>状态文件记录“现在是什么”，事件日志记录“如何走到这里”；两者分离使前端重连、
 * 运维审计和问题复盘不依赖内存中的 SSE 订阅器。</p>
 */
@Component
public class EventLogStore {
    /** 用于定位任务目录和 events.jsonl 文件的状态仓库。 */
    private final TaskStateStore stateStore;
    /** JSONL 单行事件序列化器，支持 Instant 时间字段。 */
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    /**
     * 进程内每个任务的最后事件号缓存。
     * 首次访问时会从 events.jsonl 恢复最后编号，以支持应用重启后的编号连续性。
     */
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** @param stateStore 本地任务状态与目录访问服务 */
    public EventLogStore(TaskStateStore stateStore) { this.stateStore = stateStore; }

    /**
     * 追加一条事件并返回带唯一递增编号的对象。
     *
     * <p>方法同步是为了让“读取/初始化计数器 → 自增 → 写入 JSONL”作为一个顺序临界区，
     * 避免同一任务两个并行主题写出相同 eventId 或颠倒日志顺序。</p>
     *
     * @param taskId 事件所属任务
     * @param type 事件类型，例如 PLAN_GENERATED
     * @param stage 产生事件的工作流阶段
     * @param payload 前端展示所需的最小结构化上下文
     * @return 已成功追加到 events.jsonl 的事件
     */
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

    /**
     * 返回 eventId 大于 afterId 的历史事件，供 SSE 首连与断线重放。
     *
     * <p>若历史日志某一行损坏则直接失败而不是静默跳过；静默丢失事件会导致前端与后端状态无法解释地不一致。</p>
     *
     * @param taskId 任务标识
     * @param afterId 客户端已经收到的最后事件编号；传 0 表示从第一条开始
     * @return 按文件顺序返回的后续事件
     */
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

    /**
     * 从历史文件恢复任务最后事件号。
     * 仅在计数器第一次进入内存时调用，避免每次 append 都全量扫描 JSONL。
     */
    private long lastId(String taskId) { return after(taskId, 0).stream().mapToLong(WorkflowEvent::eventId).max().orElse(0); }
}
