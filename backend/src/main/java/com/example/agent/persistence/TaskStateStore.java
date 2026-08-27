package com.example.agent.persistence;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.AgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 本地 JSON 状态仓库。
 * 所有修改都通过同一任务锁执行，并先写临时文件再替换，确保进程异常不会留下半个 JSON。
 */
@Component
public class TaskStateStore {
    private final Path tasksRoot;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public TaskStateStore(AgentProperties properties) throws IOException {
        this.tasksRoot = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize().resolve("tasks");
        Files.createDirectories(tasksRoot);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** 新建任务目录并持久化初始状态。 */
    public void create(AgentState state) {
        withLock(state.getTaskId(), () -> {
            try {
                Path dir = taskDir(state.getTaskId());
                Files.createDirectories(dir.resolve("plans"));
                Files.createDirectories(dir.resolve("research"));
                Files.createDirectories(dir.resolve("answers"));
                Files.createDirectories(dir.resolve("reviews"));
                writeAtomic(statePath(state.getTaskId()), state);
            } catch (IOException ex) {
                throw new IllegalStateException("创建任务状态失败", ex);
            }
        });
    }

    /** 读取任务状态；不存在时抛出明确异常，避免调用方把缺失误判为新任务。 */
    public AgentState load(String taskId) {
        // Windows 上移动 state.json 时，另一个线程正在读取同一文件也可能导致原子替换失败。
        // 因此读取与写入共用任务锁；ReentrantLock 允许 update 内部再次读取。
        ReentrantLock lock = locks.computeIfAbsent(taskId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return mapper.readValue(statePath(taskId).toFile(), AgentState.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("任务不存在或状态文件损坏: " + taskId, ex);
        } finally {
            lock.unlock();
        }
    }

    /** 在任务锁内读取、修改并原子写回状态。 */
    public AgentState update(String taskId, Consumer<AgentState> mutator) {
        final AgentState[] result = new AgentState[1];
        withLock(taskId, () -> {
            AgentState state = load(taskId);
            mutator.accept(state);
            try {
                writeAtomic(statePath(taskId), state);
                result[0] = state;
            } catch (IOException ex) {
                throw new IllegalStateException("持久化任务状态失败: " + ex.getMessage(), ex);
            }
        });
        return result[0];
    }

    /** 扫描所有任务，为服务启动恢复提供候选快照。 */
    public java.util.List<AgentState> list() {
        try (var stream = Files.list(tasksRoot)) {
            return stream.filter(Files::isDirectory)
                    // 复用 load 的任务级锁，避免首页轮询与状态原子替换在 Windows 文件系统上冲突。
                    .filter(p -> Files.exists(p.resolve("state.json")))
                    .map(p -> load(p.getFileName().toString())).toList();
        } catch (IOException ex) {
            throw new IllegalStateException("扫描任务目录失败", ex);
        }
    }

    public Path taskDir(String taskId) { return tasksRoot.resolve(taskId).normalize(); }
    public Path file(String taskId, String subDir, String fileName) { return taskDir(taskId).resolve(subDir).resolve(fileName).normalize(); }

    private Path statePath(String taskId) { return taskDir(taskId).resolve("state.json"); }

    private void writeAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void withLock(String taskId, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(taskId, ignored -> new ReentrantLock());
        lock.lock();
        try { action.run(); } finally { lock.unlock(); }
    }
}
