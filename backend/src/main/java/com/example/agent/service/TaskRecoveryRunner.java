package com.example.agent.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 应用完成启动后恢复之前因进程退出而中断的自动任务。 */
@Component
public class TaskRecoveryRunner implements ApplicationRunner {
    private final TaskService taskService;
    public TaskRecoveryRunner(TaskService taskService) { this.taskService = taskService; }
    @Override public void run(ApplicationArguments args) { taskService.recoverIncompleteTasks(); }
}
