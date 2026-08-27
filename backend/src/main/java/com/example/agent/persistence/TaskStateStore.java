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
 *
 * <p>这是单机 V1 的状态唯一真相：Graph 内存、SSE 订阅器都不是可恢复数据源。</p>
 */
@Component
public class TaskStateStore {
    /**
     * 所有任务目录的绝对规范化根路径，即 {@code {storage.root}/tasks}。
     * 后续路径均从该根路径派生，避免工作目录变化导致状态散落到不同位置。
     */
    private final Path tasksRoot;
    /**
     * JSON 序列化器；显式注册 JavaTimeModule，保证 Instant 能稳定写入和恢复。
     */
    private final ObjectMapper mapper;
    /**
     * 任务级可重入锁。Research/Answer 可并行运行，但同一 taskId 的读取、修改和原子替换必须串行。
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 初始化状态根目录与 JSON 映射器。
     *
     * @param properties 配置的本地持久化根目录
     * @throws IOException 根目录无法创建时阻止应用启动，避免运行中才暴露不可持久化问题
     */
    public TaskStateStore(AgentProperties properties) throws IOException {
        this.tasksRoot = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize().resolve("tasks");
        Files.createDirectories(tasksRoot);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * 新建标准任务目录并持久化初始状态。
     *
     * <p>目录在写 state.json 前全部创建，保证后续任一节点都可直接写 plans、research、answers、reviews
     * 工件；不在这里创建 answer 目录，因为最终输出要在标题净化后由 OutputDirectoryManager 决定。</p>
     *
     * @param state 待创建的最小合法任务状态
     */
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

    /**
     * 读取任务状态；不存在时抛出明确异常，避免调用方把缺失误判为新任务。
     *
     * @param taskId 任务标识
     * @return 从 state.json 完整反序列化出的最新状态
     */
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

    /**
     * 在任务锁内读取、修改并原子写回状态。
     *
     * <p>mutator 只能修改传入的状态对象，不能执行长时间 LLM 调用；否则会长时间占有任务锁，
     * 使 SSE 轮询和并行主题写入被无谓阻塞。</p>
     *
     * @param taskId 需要更新的任务
     * @param mutator 对最新状态执行的短事务修改
     * @return 已成功落盘的状态对象
     */
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

    /**
     * 扫描所有任务，为服务启动恢复和首页任务列表提供候选快照。
     * 无 state.json 的不完整目录不返回，避免临时/人工创建目录被视为可恢复任务。
     */
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

    /**
     * 返回某任务的规范化目录路径。
     * taskId 由服务端生成，不接受 LLM 或浏览器提供的相对路径；调用方仍须固定子目录名。
     */
    public Path taskDir(String taskId) {
        Path directory = tasksRoot.resolve(taskId).normalize();
        // taskId 虽由服务端创建，但 Controller 路径参数来自外部请求；这里仍需阻止 ../ 逃逸。
        if (!directory.startsWith(tasksRoot)) throw new IllegalArgumentException("非法任务路径");
        return directory;
    }

    /**
     * 构造中间工件路径，例如 research/TOPIC-001.json。
     * 该方法仅服务于固定内部目录，最终用户可见文件必须再经过 OutputDirectoryManager 校验。
     */
    public Path file(String taskId, String subDir, String fileName) { return taskDir(taskId).resolve(subDir).resolve(fileName).normalize(); }

    /** 返回 state.json 固定位置，集中定义以避免不同调用方写到不同状态文件。 */
    private Path statePath(String taskId) { return taskDir(taskId).resolve("state.json"); }

    /**
     * 临时文件写入后原子替换目标状态文件。
     *
     * <p>优先使用 ATOMIC_MOVE，避免进程崩溃留下损坏 JSON；少数文件系统不支持时降级为替换移动，
     * 仍然保证不会直接以截断方式覆写原状态文件。</p>
     */
    private void writeAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 在 taskId 对应锁内运行短事务。
     * ReentrantLock 使 update 内部可以安全调用同一线程的 load，而不会自锁死。
     */
    private void withLock(String taskId, Runnable action) {
        ReentrantLock lock = locks.computeIfAbsent(taskId, ignored -> new ReentrantLock());
        lock.lock();
        try { action.run(); } finally { lock.unlock(); }
    }
}
