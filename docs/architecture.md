# 系统架构

## 线性流程

系统只执行一条自动链路：

```text
用户问题
   ↓
TASK_ANALYZE（任务理解）
   ↓
PLAN_DRAFT（生成初始纲要）
   ↓
ANSWER_GENERATE（按纲要项受限并行生成详细解答）
   ↓
TITLE_GENERATE（生成并净化标题）
   ↓
FILE_GENERATE（创建 answer/{标题}/ 并写文件）
   ↓
RESULT_COLLECT（返回目录路径和文件列表）
```

不再存在人工确认、纲要修订、独立研究、研究审核、答案审核或修复回环。

## 职责边界

- `AgentWorkflow` 只定义固定节点顺序，不保存任务业务数据。
- `TaskWorkflowNodes` 负责状态切换、LLM 调用、中间工件和最终文件。
- `TaskService` 负责创建、异步提交、取消、恢复和失败兜底。
- `TaskStateStore` 以 `data/tasks/{taskId}/state.json` 作为唯一可恢复状态源。
- `TaskEventService` 将事件写入 `events.jsonl` 后通过 SSE 推送。
- LLM 只能返回结构化 DTO，不能决定状态、目录或下一节点。

## 状态与恢复

有效状态为：`CREATED`、`ANALYZING`、`PLAN_DRAFTING`、`ANSWER_GENERATING`、
`TITLE_GENERATING`、`FILE_GENERATING`、`SUCCESS`、`FAILED`、`CANCELLED`。

每个节点先持久化当前状态，再执行耗时操作。答案按纲要项并行生成，单项完成后立即写入
`answers/{topicId}.json`；恢复时已存在的答案会跳过。最终目录一经创建就写回状态，恢复会复用该目录。

旧版人工确认或审核状态不参与新流程，启动恢复时会标记为 `LEGACY_WORKFLOW_UNSUPPORTED`，提示用户重新提交。

## 输出结构

```text
data/tasks/{taskId}/
├── state.json
├── events.jsonl
├── plans/plan-v1.json
└── answers/{topicId}.json

answer/{标题}/
├── README.md
├── 01-主题.md
├── 02-主题.md
└── metadata.json
```

`TASK_SUCCESS` SSE 事件和任务详情接口都会返回真实的 `outputDirectory` 与 `outputFiles`。

## 安全与失败处理

- 标题和纲要项文件名经过 Unicode 规范化、非法字符清理、保留设备名拦截和根目录边界校验。
- LLM 返回的答案必须包含匹配的 `topicId`、标题和非空章节，否则任务失败。
- 模型调用、JSON 解析、状态持久化和文件写入异常均固化为稳定错误码并发布 `TASK_FAILED`。
- 取消采用协作式标记；远程调用返回后不会再推进后续节点。
