# AI 知识研究与 Markdown 文档生成 Agent 系统开发规格

> **实现说明（2026-08）：** 当前代码已按线性流程实现：用户问题 → 任务理解 → 初始纲要 → 逐项详细解答 → 标题 → Markdown 文件 → 返回目录和文件列表。本文后续关于人工确认、研究审核和修复回环的章节属于历史设计记录，不代表当前实现；请以 [`docs/architecture.md`](docs/architecture.md) 为准。

> 本文档直接作为 Codex 的项目开发说明书使用。目标是生成一个包含**前端 + 后端 + Agent Workflow + 本地文件持久化**的完整可运行系统。
>
> 核心原则：**人工确认纲要，Agent 自动完成后续工作；不使用数据库；不使用外部网页搜索、URL 抓取、RAG、MCP 等外部资料工具；资料获取阶段仅通过 Prompt 调用大模型完成。**

---

# 1. 项目目标

开发一个“知识研究与文档生成 Agent”系统。

用户输入一个问题，例如：

- 如何学习 Java？
- 大模型 Agent 面试该学习准备哪些？
- 如何学习大模型？
- Java 工程师面试该学习准备哪些？

系统不是直接回答，而是分阶段完成：

```text
用户问题
  ↓
任务理解
  ↓
生成初始纲要
  ↓
【人工确认 / 人机对话】
  ↓
用户补充、删除、调整、修改范围
  ↓
重新生成纲要
  ↓
【继续人工确认】
  ↓
用户明确确认
  ↓
锁定最终纲要
  ↓
针对纲要逐项研究
  ↓
自动验证研究结果
  ↓
有问题则自动修正并重新验证
  ↓
针对研究结果生成详细解答
  ↓
自动验证解答
  ↓
有问题则自动修正并重新验证
  ↓
生成标题
  ↓
创建 answer/{标题}/
  ↓
生成多个 Markdown 文件
  ↓
返回目录路径和文件列表
```

---

# 2. 技术栈（必须按此实现）

## 后端

- Java 21+
- Spring Boot 4.x
- Spring AI 2.x
- Spring AI Alibaba Graph 作为核心 Workflow / StateGraph Runtime
- Spring AI Alibaba Agent Framework 作为可选的 Agent 抽象层
- Maven
- SSE

## 前端

建议：

- 原生 Fetch/EventSource 与后端 SSE 对接

前端必须和后端一起开发，不能只提供后端 API。

---

# 3. 明确禁止的技术和能力

本项目当前版本**不要实现**以下内容：

- 不使用 MySQL
- 不使用 PostgreSQL
- 不使用 MongoDB
- 不使用 Redis
- 不使用 Vector Database
- 不使用 RAG
- 不使用 MCP
- 不使用外部 Web Search
- 不访问外部 URL 抓取网页
- 不实现 Browser Tool
- 不调用搜索引擎 API
- 不引入 Temporal

“资料研究”只使用：

```text
用户问题/纲要
      ↓
Prompt
      ↓
大模型
      ↓
结构化研究结果
```

也就是说，本系统属于：

> **LLM Knowledge Generation + Workflow Orchestration**

而不是 Web Research Agent。

---

# 4. 核心架构思想

本项目采用：

> **Workflow-first + Human-in-the-loop + Agent-as-Node**

职责必须清晰：

## Workflow

负责：

- 流程控制
- 状态切换
- 条件路由
- 暂停
- 恢复
- 重试
- 循环
- 失败
- 完成

## Agent / LLM

负责：

- 理解问题
- 生成纲要
- 根据用户意见修改纲要
- 研究知识点
- 生成详细内容
- 审核内容
- 生成标题和文件名

## Java 代码

负责：

- 状态
- 参数校验
- 版本管理
- 审核结果路由
- 文件名校验
- 文件写入
- 错误处理
- SSE 推送
- 本地持久化

一句话：

> **代码控制流程，LLM 负责认知，文件系统负责持久化。**

---

# 5. 为什么必须使用 StateGraph

本项目有三个特殊需求：

1. Plan 阶段必须暂停等待人工。
2. Research Review 失败后需要回退。
3. Answer Review 失败后需要回退。

因此流程不是简单：

```text
A -> B -> C -> D
```

而是有状态、有条件、有人工中断的：

```text
A
↓
B
↓
WAIT USER
↓
┌───────────────┐
│ 用户修改       │
│      ↓        │
│ 重新规划       │
└───────┬───────┘
        ↓
   WAIT USER
        ↓
     用户确认
        ↓
       C
```

Spring AI Alibaba Graph 的核心模型正好适合这种场景：

```text
StateGraph
Node
Edge
Conditional Edge
OverAllState
CompiledGraph
InterruptableAction
```

官方 Graph 文档也明确支持条件边、全局 State、暂停/中断和 Human-in-the-loop，因此本项目应直接建立在 Graph 之上。 citeturn802998search3

---

# 6. 不使用数据库时如何保存状态

由于明确要求“不使用数据库”，但系统又要求支持任务恢复，因此采用：

> **本地文件系统 + JSON 状态文件**

目录：

```text
data/
├── tasks/
│   ├── task-20260827-001/
│   │   ├── state.json
│   │   ├── events.jsonl
│   │   ├── plans/
│   │   │   ├── plan-v1.json
│   │   │   ├── plan-v2.json
│   │   │   └── plan-v3.json
│   │   ├── research/
│   │   │   ├── JAVA-001.json
│   │   │   └── JAVA-002.json
│   │   ├── answers/
│   │   │   ├── JAVA-001.json
│   │   │   └── JAVA-002.json
│   │   └── reviews/
│   │       ├── plan-review.json
│   │       ├── research-review.json
│   │       └── answer-review.json
│
└── output/
    └── answer/
```

要求：

- 每次状态变化立即落盘。
- 写文件采用临时文件 + 原子替换，避免进程中断导致 JSON 损坏。
- Task 启动时自动扫描未完成任务。
- 服务重启后可以根据 `state.json` 恢复任务。
- 不要求分布式部署；本项目 V1 只考虑单机运行。

---

# 7. 整体 Workflow

核心 Workflow：

```text
START
  ↓
TASK_ANALYZE
  ↓
PLAN_DRAFT
  ↓
WAIT_USER_PLAN
  ├── MODIFY → PLAN_REVISE
  │              ↓
  │         WAIT_USER_PLAN
  │
  └── CONFIRM → PLAN_LOCK
                    ↓
                RESEARCH
                    ↓
              RESEARCH_REVIEW
                    ↓
              ┌─────┴─────┐
              │           │
            PASS         FAIL
              │           │
              │        RESEARCH_REPAIR
              │           │
              │           └──> RESEARCH_REVIEW
              ↓
         ANSWER_GENERATE
              ↓
         ANSWER_REVIEW
              ↓
          ┌───┴────┐
          │        │
        PASS      FAIL
          │        │
          │    ANSWER_REPAIR
          │        │
          │        └──> ANSWER_REVIEW
          ↓
       TITLE_GENERATE
          ↓
       FILE_GENERATE
          ↓
     RESULT_COLLECT
          ↓
         END
```

---

# 8. 最重要阶段：人工确认纲要

第一阶段不能由 AI 自动判定“纲要完整”后继续。

必须：

```text
Plan Agent
    ↓
Plan V1
    ↓
暂停
    ↓
展示给用户
    ↓
等待用户
```

这是整个系统的强制 Human Gate。

只有用户明确确认后：

```text
Plan CONFIRMED
    ↓
Plan LOCKED
    ↓
Research
```

才能继续。

---

# 9. 用户如何与 Plan Agent 交互

用户不仅可以点击“确认”，还可以通过自然语言对话修改。

支持：

### 增加

```text
增加 Linux、MySQL、Docker。
```

### 删除

```text
删除 Java 历史。
```

### 修改

```text
JVM 单独拆成一个章节。
```

### 合并

```text
Spring MVC 和 Spring Boot 合并成一个模块。
```

### 调整顺序

```text
Maven 和 Git 放到 Spring 前面。
```

### 限定范围

```text
我学习 Java 是为了做后端，不需要 Android。
```

### 调整深度

```text
Java 基础简单一点，JVM 和并发深入一些。
```

### 全量重新规划

```text
不要修改当前方案，按照 Java 后端工程师方向重新设计。
```

### 确认

```text
可以，开始执行。
```

---

# 10. Plan Conversation Agent

职责：

1. 阅读当前 Plan。
2. 阅读用户历史反馈。
3. 理解本次用户意见。
4. 判断用户是新增、删除、修改、合并、拆分、排序还是重新规划。
5. 生成新版本 Plan。
6. 输出修改摘要。
7. 再次进入等待用户状态。

输出必须结构化。

例如：

```json
{
  "action": "REVISE",
  "summary": {
    "added": ["Linux", "MySQL"],
    "removed": ["Java历史"],
    "modified": ["JVM", "Java并发"],
    "reordered": []
  },
  "plan": {
    "version": 2,
    "items": []
  }
}
```

---

# 11. Plan 版本管理

绝不能覆盖旧版本。

例如：

```text
Plan V1
↓
用户反馈
↓
Plan V2
↓
用户反馈
↓
Plan V3
↓
用户确认
↓
Plan V3 LOCKED
```

保留：

- Plan 版本
- 用户反馈
- 生成时间
- 修改摘要
- 最终确认时间

---

# 12. Plan 数据结构

建议 Java DTO：

```java
public record Plan(
    int version,
    String title,
    String goal,
    List<PlanItem> items
) {}
```

```java
public record PlanItem(
    String id,
    String title,
    String description,
    int order,
    boolean required,
    String depth
) {}
```

不要依赖自然语言文本作为内部状态。

---

# 13. Task Analyze

输入：

```text
如何学习 Java？
```

输出：

```json
{
  "taskType": "LEARNING",
  "target": "JAVA_BACKEND",
  "goal": "建立系统性的Java后端学习体系"
}
```

初期支持：

```text
LEARNING
INTERVIEW_PREPARATION
TECH_RESEARCH
KNOWLEDGE_SUMMARY
```

---

# 14. Research 阶段

最终 Plan Lock 后，对每一个 Plan Item 进行研究。

但是本项目明确规定：

> **Research 不使用互联网工具。**

Research Agent 的唯一输入：

```text
原始问题
+
用户确认后的 Plan
+
当前 Plan Item
+
已有 Research Context
```

然后调用 LLM：

```text
Prompt
 ↓
LLM
 ↓
ResearchResult
```

---

# 15. Research Prompt 思路

Research Prompt 必须明确要求模型：

1. 只针对当前知识点进行研究。
2. 输出该知识点必须覆盖的细节。
3. 解释核心概念。
4. 补充必要前置知识。
5. 指出常见误区。
6. 给出学习重点。
7. 不要无限扩张主题。
8. 不使用外部工具。
9. 不伪造来源。
10. 当前版本没有外部检索时，不要求生成 URL。

输出：

```json
{
  "topicId": "JAVA-001",
  "topic": "Java基础",
  "details": [
    {
      "id": "JAVA-001-01",
      "title": "Java是什么",
      "questions": [
        "Java是什么",
        "Java的核心特点是什么",
        "Java的运行方式是什么"
      ]
    }
  ]
}
```

---

# 16. Research Review

这里不需要用户再次参与，自动审核即可。

审核：

- 是否覆盖 Plan Item
- 是否存在明显遗漏
- 是否出现重复
- 是否偏题
- 是否前后矛盾
- 是否内容范围过大
- 是否需要拆分知识点

输出：

```json
{
  "passed": false,
  "score": 0.86,
  "issues": [
    {
      "type": "MISSING",
      "severity": "HIGH",
      "message": "缺少JVM基础概念"
    }
  ]
}
```

---

# 17. Research 修复

失败时：

```text
Research
 ↓
Review FAIL
 ↓
Repair
 ↓
Research Review
```

Repair 只针对问题修复，不要无意义重新生成全部内容。

最大次数：

```text
MAX_RESEARCH_REVIEW_ROUNDS = 3
```

达到上限：

```text
TASK FAILED
RESEARCH_REVIEW_MAX_ROUNDS
```

---

# 18. Answer Generate

Research Review 全部通过后进入答案生成。

推荐并行：

```text
                Research
                   ↓
       ┌───────────┼───────────┐
       ↓           ↓           ↓
    Answer A    Answer B    Answer C
```

每一个 Plan Item/Research Topic 独立生成答案。

输出：

```json
{
  "topicId": "JAVA-001",
  "title": "Java基础",
  "summary": "...",
  "sections": [
    {
      "title": "Java是什么",
      "content": "..."
    },
    {
      "title": "Java特点",
      "content": "..."
    }
  ]
}
```

---

# 19. Answer Review

每个 Answer 自动审核：

```text
Accuracy
Completeness
Relevance
Structure
Readability
Hallucination Risk
```

为了简化 V1，可以先使用一个 Reviewer Agent，但内部返回结构化结果。

后续再拆成多个 Reviewer。

---

# 20. Answer Repair

失败：

```text
Answer
 ↓
Review FAIL
 ↓
Repair
 ↓
New Answer Version
 ↓
Review
```

最大次数：

```text
MAX_ANSWER_REVIEW_ROUNDS = 3
```

---

# 21. 标题与文件名

最终生成一个顶层标题。

例如：

```text
Java后端学习路线
```

限制：

- 不超过 10 个字
- 禁止 `/ \ : * ? " < > |`
- 不允许 `..`
- 不允许路径穿越

Java 程序必须重新校验。

LLM 不能直接决定最终文件路径。

---

# 22. 文件输出

根目录：

```text
answer/
```

例如：

```text
answer/
└── Java学习路线/
    ├── README.md
    ├── 01-Java基础.md
    ├── 02-Java开发环境.md
    ├── 03-Java语法.md
    ├── 04-面向对象.md
    ├── 05-Java集合.md
    ├── 06-Java并发.md
    ├── 07-JVM.md
    ├── 08-Spring体系.md
    ├── 09-Redis.md
    └── metadata.json
```

---

# 23. README.md

README 内容至少包括：

```text
原始问题
学习目标
最终确认的学习纲要
文件目录
推荐阅读顺序
生成时间
```

---

# 24. metadata.json

例如：

```json
{
  "taskId": "task-20260827-001",
  "question": "如何学习Java？",
  "title": "Java学习路线",
  "status": "SUCCESS",
  "planVersion": 3,
  "createdAt": "2026-08-27T09:00:00+08:00",
  "updatedAt": "2026-08-27T10:00:00+08:00"
}
```

---

# 25. SSE 实时通信

前端必须可以实时看到 Workflow 状态。

接口：

```http
GET /api/tasks/{taskId}/events
```

事件类型：

```text
TASK_CREATED
PLAN_GENERATED
PLAN_WAITING_USER
PLAN_REVISED
PLAN_CONFIRMED
PLAN_LOCKED

RESEARCH_STARTED
RESEARCH_PROGRESS
RESEARCH_REVIEWING
RESEARCH_REPAIRED

ANSWER_STARTED
ANSWER_PROGRESS
ANSWER_REVIEWING
ANSWER_REPAIRED

FILE_GENERATING
FILE_WRITTEN
TASK_SUCCESS
TASK_FAILED
```

SSE 事件示例：

```text
event: PLAN_WAITING_USER
data: {"taskId":"xxx","message":"请确认当前纲要"}
```

---

# 26. 前端必须实现

不能只做 API。

前端页面至少包括：

## 页面 1：任务主页

包含：

```text
输入框
开始任务按钮
历史任务列表
```

---

## 页面 2：任务详情

三栏布局：

```text
┌─────────────┬────────────────────┬─────────────┐
│ Task 状态   │ Agent 对话         │ Workflow    │
│             │                    │             │
│ 进行阶段    │ Agent：            │ ✓ 分析      │
│             │ 当前纲要是……       │ ✓ 生成纲要  │
│             │                    │ ⏸ 等待确认  │
│             │ 用户：             │ ○ Research  │
│             │ 增加 Linux         │ ○ Answer    │
│             │                    │ ○ File      │
└─────────────┴────────────────────┴─────────────┘
```

---

# 27. Plan 人工确认界面

这是前端最重要的页面。

需要展示：

```text
当前纲要 V3

1. Java基础
2. Java并发
3. JVM
4. MySQL
5. Spring
6. Redis

[确认并开始执行]

输入修改意见：
[________________________________]

[发送修改意见]
```

用户每次发送修改意见：

```text
前端 POST /api/tasks/{id}/messages
       ↓
后端
       ↓
Plan Conversation Agent
       ↓
Plan V4
       ↓
SSE 推送
       ↓
前端更新
       ↓
继续等待
```

---

# 28. 前端对话要求

用户输入：

```text
增加 Docker，删除 Java 历史。
```

前端显示：

```text
你：

增加 Docker，删除 Java 历史。

Agent：

已修改：

新增：
+ Docker

删除：
- Java历史

当前纲要已更新到 V4，请继续确认。
```

用户还可以继续输入。

---

# 29. 确认按钮

必须有明确按钮：

```text
确认纲要并开始执行
```

调用：

```http
POST /api/tasks/{taskId}/plan/confirm
```

请求：

```json
{
  "planVersion": 4
}
```

后端校验：

1. Task 当前必须是 WAITING_USER_PLAN。
2. Plan Version 必须是当前版本。
3. Plan 必须存在。
4. 用户必须明确确认。

确认后：

```text
WAITING_USER_PLAN
      ↓
PLAN_CONFIRMED
      ↓
PLAN_LOCKED
      ↓
RESEARCH
```

---

# 30. 前端任务进度

必须展示：

```text
任务状态

✓ 任务理解
✓ 纲要生成
✓ 纲要人工确认
⟳ 知识研究
○ 研究审核
○ 内容生成
○ 内容审核
○ 文件生成
```

对于自动循环：

```text
研究审核
第 1 次：失败
第 2 次：通过
```

---

# 31. 前端最终结果

成功后：

```text
任务完成

输出目录：
answer/Java学习路线/

生成文件：

README.md
01-Java基础.md
02-Java并发.md
03-JVM.md
04-MySQL.md
05-Spring体系.md
06-Redis.md

[打开任务目录]
```

前端不能伪造目录内容，必须读取后端真实返回结果。

---

# 32. 后端项目结构

推荐：

```text
src/main/java/com/example/agent/

├── controller/
│   ├── TaskController.java
│   ├── TaskMessageController.java
│   └── SseController.java
│
├── service/
│   ├── TaskService.java
│   ├── PlanService.java
│   ├── ResearchService.java
│   ├── AnswerService.java
│   └── FileService.java
│
├── workflow/
│   ├── AgentWorkflow.java
│   ├── AgentState.java
│   ├── WorkflowConfig.java
│   └── nodes/
│
├── agent/
│   ├── TaskAnalyzerAgent.java
│   ├── PlannerAgent.java
│   ├── PlanConversationAgent.java
│   ├── ResearchAgent.java
│   ├── ResearchReviewerAgent.java
│   ├── AnswerAgent.java
│   ├── AnswerReviewerAgent.java
│   └── TitleAgent.java
│
├── model/
│   ├── Task.java
│   ├── Plan.java
│   ├── PlanItem.java
│   ├── PlanFeedback.java
│   ├── ResearchResult.java
│   ├── Answer.java
│   └── ReviewResult.java
│
├── persistence/
│   ├── FileTaskRepository.java
│   ├── JsonStateStore.java
│   └── EventLogStore.java
│
├── llm/
│   ├── LlmService.java
│   ├── LlmRouter.java
│   └── PromptService.java
│
├── file/
│   ├── MarkdownWriter.java
│   ├── FileNameSanitizer.java
│   └── OutputDirectoryManager.java
│
└── exception/
    ├── AgentException.java
    └── GlobalExceptionHandler.java
```

---

# 33. 前端项目结构

推荐：

```text
frontend/
├── src/
│   ├── api/
│   │   ├── taskApi.ts
│   │   └── sseApi.ts
│   │
│   ├── pages/
│   │   ├── HomePage.tsx
│   │   └── TaskPage.tsx
│   │
│   ├── components/
│   │   ├── TaskInput.tsx
│   │   ├── PlanPanel.tsx
│   │   ├── PlanDiff.tsx
│   │   ├── ChatPanel.tsx
│   │   ├── WorkflowProgress.tsx
│   │   ├── TaskResult.tsx
│   │   └── ErrorPanel.tsx
│   │
│   ├── hooks/
│   │   ├── useTask.ts
│   │   └── useTaskSse.ts
│   │
│   ├── types/
│   │   └── task.ts
│   │
│   └── App.tsx
│
├── package.json
├── vite.config.ts
└── tsconfig.json
```

---

# 34. API 设计

## 创建任务

```http
POST /api/tasks
```

```json
{
  "question": "如何学习Java？"
}
```

返回：

```json
{
  "taskId": "task-xxx",
  "status": "PLAN_DRAFTING"
}
```

---

## 查询任务

```http
GET /api/tasks/{taskId}
```

---

## 查询当前 Plan

```http
GET /api/tasks/{taskId}/plan
```

---

## 查询 Plan 历史版本

```http
GET /api/tasks/{taskId}/plan/versions
```

---

## 用户发送修改意见

```http
POST /api/tasks/{taskId}/messages
```

```json
{
  "message": "增加Linux和MySQL，删除Java历史"
}
```

---

## 确认 Plan

```http
POST /api/tasks/{taskId}/plan/confirm
```

```json
{
  "planVersion": 4
}
```

---

## SSE

```http
GET /api/tasks/{taskId}/events
```

---

## 取消任务

```http
POST /api/tasks/{taskId}/cancel
```

---

# 35. AgentState

核心状态：

```java
public class AgentState {

    private String taskId;

    private String question;

    private String taskType;

    private TaskStatus status;

    private String currentNode;

    private Plan currentPlan;

    private int planVersion;

    private boolean planConfirmed;

    private boolean planLocked;

    private List<PlanFeedback> planFeedbackHistory;

    private Map<String, ResearchResult> researchResults;

    private Map<String, Answer> answers;

    private Map<String, ReviewResult> reviewResults;

    private int researchReviewRound;

    private int answerReviewRound;

    private String title;

    private String outputDirectory;

    private List<String> outputFiles;

    private String errorCode;

    private String errorMessage;
}
```

---

# 36. Prompt 管理

Prompt 不要全部直接写在 Java 字符串中。

建议：

```text
src/main/resources/prompts/

├── task-analyzer/
│   └── system.txt
├── planner/
│   └── system.txt
├── plan-conversation/
│   └── system.txt
├── researcher/
│   └── system.txt
├── research-reviewer/
│   └── system.txt
├── answer/
│   └── system.txt
├── answer-reviewer/
│   └── system.txt
└── title/
    └── system.txt
```

每个 Prompt 明确：

- 输入格式
- 输出格式
- 业务规则
- 不允许做什么
- JSON Schema

---

# 37. Structured Output

以下 Agent 必须使用结构化输出：

```text
TaskAnalyzer
Planner
PlanConversation
Researcher
ResearchReviewer
Answer
AnswerReviewer
TitleGenerator
```

必须映射 Java DTO。

例如：

```java
Plan plan = chatClient
    .prompt()
    .user(...)
    .call()
    .entity(Plan.class);
```

具体 API 以当前项目依赖版本为准。

不要使用：

```text
String result = model.call(...)
```

然后手动解析全文。

---

# 38. 模型配置

实现：

```text
application.yml
```

示例：

```yaml
spring:
  application:
    name: knowledge-agent

agent:
  model:
    planner: ${PLANNER_MODEL}
    researcher: ${RESEARCHER_MODEL}
    writer: ${WRITER_MODEL}
    reviewer: ${REVIEWER_MODEL}

  limits:
    max-research-review-rounds: 3
    max-answer-review-rounds: 3
    max-llm-retries: 3
    workflow-timeout-minutes: 60

  storage:
    root: ./data
    answer-root: ./answer
```

具体模型可以根据用户环境配置，不要将某一个模型厂商写死。

---

# 39. 任务持久化

由于无数据库：

```text
data/tasks/{taskId}/state.json
```

每个状态更新都要：

```text
load
→ modify
→ validate
→ write temp
→ atomic replace
```

不要直接覆盖 JSON 文件。

---

# 40. Event Log

每一个 Workflow 状态变化写入：

```text
events.jsonl
```

例如：

```json
{"type":"PLAN_GENERATED","time":"..."}
{"type":"PLAN_WAITING_USER","time":"..."}
{"type":"PLAN_REVISED","version":2,"time":"..."}
{"type":"PLAN_CONFIRMED","version":2,"time":"..."}
{"type":"PLAN_LOCKED","version":2,"time":"..."}
{"type":"RESEARCH_STARTED","time":"..."}
```

用于：

- 调试
- 恢复
- 审计
- 前端历史展示

---

# 41. 文件系统安全

所有输出必须位于：

```text
./answer/
```

禁止：

```text
../
../../
/etc
C:\
```

标题和文件名必须经过：

```text
sanitize
normalize
validate
```

文件名最大长度按需求限制。

---

# 42. 错误体系

统一：

```text
TASK_INVALID
PLAN_GENERATION_FAILED
PLAN_REVISION_FAILED
PLAN_CONFIRM_FAILED

RESEARCH_FAILED
RESEARCH_REVIEW_FAILED
RESEARCH_REVIEW_MAX_ROUNDS

ANSWER_GENERATION_FAILED
ANSWER_REVIEW_FAILED
ANSWER_REVIEW_MAX_ROUNDS

TITLE_GENERATION_FAILED
FILE_CREATE_FAILED
FILE_WRITE_FAILED

LLM_TIMEOUT
LLM_RATE_LIMIT
LLM_INVALID_OUTPUT
LLM_CONTEXT_OVERFLOW

WORKFLOW_TIMEOUT
WORKFLOW_CANCELLED
TASK_RECOVERY_FAILED
UNKNOWN_ERROR
```

错误返回：

```json
{
  "taskId": "xxx",
  "status": "FAILED",
  "stage": "RESEARCH",
  "errorCode": "LLM_TIMEOUT",
  "message": "研究阶段模型调用超时",
  "retryable": true
}
```

---

# 43. 循环控制

禁止无限循环。

配置：

```yaml
agent:
  limits:
    max-research-review-rounds: 3
    max-answer-review-rounds: 3
```

Plan 人工修改不建议设置很小的次数限制，但必须允许用户主动取消任务。

---

# 44. 并行设计

Research 和 Answer 都可以并行。

例如：

```text
Plan
 ↓
Parallel Research
 ├── Topic A
 ├── Topic B
 ├── Topic C
 └── Topic D
 ↓
Research Review
```

Answer：

```text
Research
 ↓
Parallel Answer
 ├── Answer A
 ├── Answer B
 ├── Answer C
 └── Answer D
 ↓
Answer Review
```

第一版可以使用 Java CompletableFuture / Spring 异步机制实现。

不要为了并行引入新的分布式组件。

---

# 45. 不要过度 Agent 化

以下模块不必设计成 Agent：

```text
TaskRepository
FileService
SseService
PlanLockService
ReviewAggregator
TitleSanitizer
MarkdownWriter
```

只有真正需要语言理解和推理的模块使用 LLM。

推荐：

```text
TaskAnalyzerAgent
PlannerAgent
PlanConversationAgent
ResearchAgent
ResearchReviewerAgent
AnswerAgent
AnswerReviewerAgent
TitleAgent
```

---

# 46. 第一阶段 Plan 不允许自动跳过人工确认

这是最高优先级业务规则：

```text
PLAN_DRAFT
    ↓
WAITING_USER_PLAN
```

即使：

```text
PlanReview = PASS
```

也不能继续。

必须：

```text
用户明确确认
```

才可以：

```text
PLAN_LOCKED
```

---

# 47. 用户确认后的 Plan 不允许偷偷变化

一旦：

```text
PLAN_LOCKED
```

后续 Agent 不允许修改 Plan。

如果 Research 阶段发现：

```text
需要增加新知识点
```

V1 不自动增加。

应该记录：

```text
researchGap
```

并将任务标记为异常或后续人工扩展。

避免后续阶段偷偷改变用户确认的任务范围。

---

# 48. MVP 范围

第一版必须完成：

```text
前端
+
后端
+
Plan Agent
+
人工确认
+
Plan 多轮修改
+
StateGraph
+
Research Agent
+
Research Review
+
Answer Agent
+
Answer Review
+
Markdown 文件
+
本地状态持久化
+
SSE
+
错误处理
```

---

# 49. 暂不做

V1 不做：

```text
RAG
MCP
Web Search
Browser
数据库
Redis
复杂 Multi-Agent
Temporal
Kubernetes
权限系统
多租户
用户注册登录
在线协作
云文件存储
```

保持架构简单，先实现完整闭环。

---

# 50. 测试要求

必须包含：

## 单元测试

至少：

```text
PlanServiceTest
PlanConversationTest
FileNameSanitizerTest
MarkdownWriterTest
TaskStateStoreTest
```

## Workflow 测试

至少：

```text
Plan生成 -> 等待用户
用户修改 -> Plan V2
用户修改 -> Plan V3
用户确认 -> Research

Research Fail -> Repair -> Review Pass

Answer Fail -> Repair -> Review Pass

Review 达到最大次数 -> FAILED

任务恢复 -> 从当前节点继续
```

## 前端测试

至少覆盖：

```text
创建任务
显示 Plan
发送修改意见
更新 Plan
点击确认
显示 SSE 状态
显示最终文件列表
显示错误信息
```

---

# 51. Codex 开发方式

请不要一次性生成大量代码后结束。

建议按照以下阶段执行：

## Phase 1

先搭建：

```text
Spring Boot
+
React
+
Maven
+
基础 API
+
SSE
```

确保前后端可运行。

## Phase 2

实现：

```text
AgentState
+
本地 JSON 持久化
+
StateGraph
```

先让：

```text
Task -> Plan -> WAIT_USER
```

跑通。

## Phase 3

实现：

```text
Plan Conversation
+
版本管理
+
用户确认
+
Plan Lock
```

## Phase 4

实现：

```text
Research
+
Research Review
+
Repair Loop
```

## Phase 5

实现：

```text
Answer
+
Answer Review
+
Repair Loop
```

## Phase 6

实现：

```text
Title
+
Markdown
+
README
+
metadata
```

## Phase 7

完成：

```text
任务恢复
+
SSE
+
错误处理
+
测试
+
前端体验
```

---

# 52. 开发完成后的验收场景

## 场景 1

输入：

```text
如何学习Java？
```

系统生成 Plan 后必须暂停。

前端显示：

```text
等待你的确认
```

---

## 场景 2

用户：

```text
删除Java历史，增加Linux、MySQL、Docker。
```

系统生成 Plan V2。

前端显示修改差异。

继续等待。

---

## 场景 3

用户再次：

```text
加强JVM和并发，弱化IO。
```

系统生成 Plan V3。

继续等待。

---

## 场景 4

用户：

```text
确认，开始执行。
```

系统：

```text
Plan V3 LOCKED
```

进入 Research。

---

## 场景 5

Research Review 第一次失败。

系统：

```text
Research Repair
```

再次 Review。

---

## 场景 6

Answer Review 第一次失败。

系统重新生成该 Answer，而不是全部重做。

---

## 场景 7

所有内容成功。

生成：

```text
answer/Java学习路线/
```

并返回文件列表。

---

## 场景 8

服务重启。

任务继续执行。

---

# 53. 推荐最终目录

```text
project-root/
│
├── backend/
│   ├── pom.xml
│   └── src/
│
├── frontend/
│   ├── package.json
│   └── src/
│
├── data/
│   └── tasks/
│
├── answer/
│
├── docs/
│   └── architecture.md
│
├── docker-compose.yml
└── README.md
```

V1 即使不需要数据库，也可以提供 Docker Compose，但不要引入数据库服务；主要用于统一运行环境。

---

# 54. 开发原则

必须遵守：

1. 不写巨型 Service。
2. 不写一个万能 Agent。
3. 不把 Workflow 逻辑埋进 Prompt。
4. 不让 LLM 控制文件系统。
5. 不让 LLM 决定程序状态。
6. 不用自然语言解析核心状态。
7. 不覆盖历史 Plan。
8. 不无限循环。
9. 不因为“AI认为完成”而跳过人工确认。
10. 所有关键状态都可以观察、持久化和恢复。
11. 前端和后端必须同时交付。
12. 每一个核心流程都必须能够通过自动化测试验证。

---

# 55. 最终系统定位

这个项目不是：

```text
一个聊天机器人
```

也不是：

```text
一个万能 Autonomous Agent
```

而是：

> **一个以 Spring AI Alibaba Graph 为核心、以人工确认 Plan 为入口、以 LLM Prompt 为主要知识生成方式、以 StateGraph 管理流程的知识研究与文档生成 Workflow Agent。**

完整闭环：

```text
             ┌──────────────────┐
             │      用户问题     │
             └────────┬─────────┘
                      ↓
               Task Analyzer
                      ↓
                  Planner
                      ↓
               ┌────────────┐
               │ Human Gate │
               └─────┬──────┘
                     ↓
                Plan Locked
                     ↓
                 Research
                     ↓
                Review Loop
                     ↓
                  Answer
                     ↓
                Review Loop
                     ↓
                   Title
                     ↓
                 Markdown
                     ↓
               Local File System
                     ↓
                   Result
```

最核心的设计原则：

> **人工决定“做什么”，Agent 决定“怎么研究和怎么写”，Workflow 决定“什么时候做下一步”，Java 决定“系统规则是否允许继续”。**
