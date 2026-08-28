# 工作流流转与排障说明

## 一次任务如何完成

`POST /api/tasks` 只创建任务并返回 `202 + taskId`。后台线程随后执行：

1. `TASK_ANALYZE`：调用 `LlmService.analyze`，保存受限任务类型。
2. `PLAN_DRAFT`：调用 `draftPlan`，校验版本、主题数量和稳定 ID，写入 `plans/plan-v1.json`。
3. `ANSWER_GENERATE`：对每个 `PlanItem` 调用一次 `generateAnswer`，受限并行并写入 `answers/{topicId}.json`。
4. `TITLE_GENERATE`：调用 `generateTitle`，由 Java 净化目录名。
5. `FILE_GENERATE`：创建 `answer/{标题}/`，写入 README、主题 Markdown 和 metadata。
6. `RESULT_COLLECT`：设置 `SUCCESS`，并发布包含目录和文件列表的 `TASK_SUCCESS`。

## 状态是唯一真相

`AgentState` 持久化在 `data/tasks/{taskId}/state.json`，保存任务理解、初始纲要、答案、标题、输出目录、文件列表和错误信息。
Graph 内存只保存 `taskId` 与入口节点；SSE 只负责实时传输和历史回放，不能替代状态文件。

## 恢复规则

启动时扫描非终态任务，根据状态选择入口：

| 状态 | 恢复入口 |
| --- | --- |
| `CREATED` / `ANALYZING` | `TASK_ANALYZE` |
| `PLAN_DRAFTING` | `PLAN_DRAFT` |
| `ANSWER_GENERATING` | `ANSWER_GENERATE` |
| `TITLE_GENERATING` | `TITLE_GENERATE` |
| `FILE_GENERATING` | `FILE_GENERATE` |

节点会复用已持久化的 Plan、答案、标题和输出目录。无法识别的旧版人工确认/审核状态会被标记为
`LEGACY_WORKFLOW_UNSUPPORTED`，不与新流程混用。

## 常见排障

- `LLM_INVALID_OUTPUT`：检查模型是否返回合法结构化 DTO，以及答案 `topicId` 是否匹配当前纲要项。
- `LLM_REQUEST_FAILED` / `LLM_PROMPT_REJECTED`：查看后端模型调用日志，前端只展示稳定错误提示。
- `FILE_WRITE_FAILED`：检查 `answer-root` 权限、磁盘空间和目标文件；状态中的 `outputDirectory` 可用于定位现场。
- 长时间停留在 `ANSWER_GENERATING`：查看最后一条 `ANSWER_PROGRESS`、线程池并行度和模型读取超时。
- SSE 断线：浏览器会自动重连并使用 `Last-Event-ID` 回放 `events.jsonl`。

## 文件与事件

```text
data/tasks/{taskId}/
├── state.json
├── events.jsonl
├── plans/plan-v1.json
└── answers/{topicId}.json
```

主要事件为：`TASK_CREATED`、`TASK_ANALYZED`、`PLAN_GENERATED`、`ANSWER_STARTED`、
`ANSWER_PROGRESS`、`TITLE_GENERATED`、`FILE_GENERATING`、`FILE_WRITTEN`、
`TASK_SUCCESS`、`TASK_FAILED`、`WORKFLOW_CANCELLED`。
