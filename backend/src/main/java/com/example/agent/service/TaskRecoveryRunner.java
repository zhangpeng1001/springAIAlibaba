package com.example.agent.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用完成启动后恢复之前因进程退出而中断的自动任务。
 * 该 Runner 在 Web 服务可用后触发，避免恢复任务刚启动就需要推送 SSE 时事件服务尚未初始化。
 */
@Component
public class TaskRecoveryRunner implements ApplicationRunner {
    /** 启动恢复入口日志，用于区分服务首次启动和任务恢复期间的后台操作。 */
    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryRunner.class);

    /** 执行扫描、路由和后台恢复的任务门面。 */
    private final TaskService taskService;

    /** @param taskService 任务恢复服务 */
    public TaskRecoveryRunner(TaskService taskService) { this.taskService = taskService; }

    /**
     * Spring Boot 启动回调。
     * 人工等待态保持原状，只有可自动继续的任务才会重新提交到执行器。
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("应用启动完成，开始执行未完成任务恢复扫描");
        taskService.recoverIncompleteTasks();
        log.info("未完成任务恢复扫描已提交");
    }
}
