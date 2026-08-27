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
 * 内存 emitter 仅是实时传输通道，服务重启后由 EventLogStore 重放，不被视为持久化状态。
 */
@Service
public class TaskEventService {
    /** 事件 JSONL 持久化与按 eventId 回放服务。 */
    private final EventLogStore eventLogStore;
    /**
     * 当前在线连接：taskId → connectionId → emitter。
     * 一个任务可能被多个浏览器标签页查看，因此不能只保存单个 emitter。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** @param eventLogStore 可恢复的事件日志仓库 */
    public TaskEventService(EventLogStore eventLogStore) { this.eventLogStore = eventLogStore; }

    /**
     * 记录事件并立即分发给当前在线客户端。
     *
     * <p>必须先 append 再 send：发送失败或客户端断线时，事件仍可在下次连接通过 eventId 回放。</p>
     *
     * @param taskId 事件所属任务
     * @param type SSE event 名称
     * @param stage 产生事件的工作流节点
     * @param payload UI 展示所需最小数据
     * @return 已持久化的事件
     */
    public WorkflowEvent publish(String taskId, String type, String stage, Map<String, Object> payload) {
        WorkflowEvent event = eventLogStore.append(taskId, type, stage, payload);
        emitters.getOrDefault(taskId, new ConcurrentHashMap<>()).forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(event.eventId())).name(event.type())
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                // 单个浏览器连接失败不能影响工作流或其他观察者；清理该 emitter 即可。
                emitter.complete();
                emitters.getOrDefault(taskId, new ConcurrentHashMap<>()).remove(id);
            }
        });
        return event;
    }

    /**
     * 建立 SSE 连接，先发送当前状态快照，再重放 afterId 之后的事件。
     *
     * <p>快照解决首次连接时“不知道当前状态”的问题，事件回放补齐断线期间的过程；
     * 前端按 eventId 去重，以应对快照和实时推送交错到达。</p>
     *
     * @param snapshot 连接建立瞬间从 state.json 读取的状态
     * @param afterId 客户端最后处理的事件编号，0 表示从头回放
     * @return 长连接 SseEmitter，终态任务也会在发送完历史事件后由客户端自行关闭
     */
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

    /** 在连接完成、超时或发送失败时移除内存订阅，防止 emitter 泄漏。 */
    private void remove(String taskId, String connectionId) {
        emitters.getOrDefault(taskId, new ConcurrentHashMap<>()).remove(connectionId);
    }
}
