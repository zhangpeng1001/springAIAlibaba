# 知识研究与 Markdown 文档生成 Agent

这是一个以 Spring AI Alibaba StateGraph 为流程引擎的单机 Agent 系统：用户先审阅、反复修改并**显式确认**纲要；之后系统自动完成知识研究、审核修复、内容生成、Markdown 输出和本地状态持久化。

系统不使用数据库、RAG、MCP、网页搜索或浏览器工具。研究信息仅来自配置的大模型；文件系统承担任务状态和最终文档的持久化。

## 技术基线

- Java 21（已使用 `--release 21` 编译；高版本 JDK 也可运行）
- Spring Boot 4.0.0、Spring AI 2.0.0-M1、Spring AI Alibaba Graph 2.0.0-M1.1
- React、TypeScript、Vite
- SSE、JSON 状态文件、Maven

## 本地运行

先启动后端：

```powershell
mvn -f backend/pom.xml spring-boot:run
```

另开终端启动前端：

```powershell
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。后端默认监听 `http://localhost:8080`。

默认使用离线模板 LLM，因此无需 API Key 即可验证完整 Workflow、Human Gate、SSE、审核和 Markdown 输出。它用于开发/测试，不代表真实模型知识质量。

## 配置真实 OpenAI-compatible 模型

设置以下环境变量后，以 `openai` profile 启动。该适配器使用 Spring AI `ChatClient` 的结构化 DTO 映射，不手工解析模型全文。

```powershell
$env:SPRING_PROFILES_ACTIVE = "openai"
$env:OPENAI_API_KEY = "你的密钥"
# 请填写服务根地址，不要添加 /v1；当前 Spring AI 客户端会自动追加 /v1/chat/completions。
$env:OPENAI_BASE_URL = "https://你的兼容端点"
$env:OPENAI_MODEL = "你的模型名"
# 深度研究可能较慢，默认读取超时 300 秒；可按供应商响应速度调整
$env:LLM_READ_TIMEOUT_SECONDS = "300"
mvn -f backend/pom.xml spring-boot:run
```

如使用官方 OpenAI，`OPENAI_BASE_URL` 可省略。即使误填了末尾 `/v1`，后端也会在启动时自动清理并记录规范化后的根地址。密钥只从环境变量读取，绝不写入仓库。
模型 TCP 建连超时默认为 10 秒、响应读取超时默认为 300 秒；两者只限制单次 HTTP 调用，不限制整个工作流时长。

## 排障日志

应用会同时输出控制台日志和 `./logs/knowledge-agent.log`（可用 `LOG_FILE` 环境变量覆盖）。日志包含 HTTP 请求 ID、任务 ID、工作流节点、事件、模型调用耗时与完整异常栈；不会记录 API Key、完整 Prompt 或用户问题正文。模型调用失败时，前端会收到 `LLM_REQUEST_FAILED` 和安全提示，详细的远程错误响应请在后端日志中查看。

## 核心 API

| 方法 | 接口 | 说明 |
| --- | --- | --- |
| POST | `/api/tasks` | 异步创建任务 |
| GET | `/api/tasks` | 获取历史任务 |
| GET | `/api/tasks/{taskId}` | 查询真实任务快照 |
| GET | `/api/tasks/{taskId}/plan` | 查询当前纲要 |
| GET | `/api/tasks/{taskId}/plan/versions` | 查询不可变纲要历史 |
| POST | `/api/tasks/{taskId}/messages` | 提交纲要修改意见 |
| POST | `/api/tasks/{taskId}/plan/confirm` | 显式确认指定版本纲要 |
| GET | `/api/tasks/{taskId}/events` | SSE 快照、实时事件与断线回放 |
| POST | `/api/tasks/{taskId}/cancel` | 协作式取消任务 |

## 本地文件结构

```text
data/tasks/{taskId}/
├── state.json       # 原子写入的唯一任务状态来源
├── events.jsonl     # 带递增 eventId 的审计与 SSE 回放日志
├── plans/
├── research/
├── answers/
└── reviews/

answer/{标题}/
├── README.md
├── 01-主题.md
└── metadata.json
```

生成的 `data/` 和 `answer/` 目录被 `.gitignore` 排除。前端只展示后端返回的真实路径与文件列表，V1 不提供浏览器“打开服务器本机目录”的伪功能。

## 验证

```powershell
mvn -f backend/pom.xml test
cd frontend; npm run build; npm test
```

更多状态转换、恢复与安全边界见 [架构说明](docs/architecture.md)；需要逐节点跟踪 `TaskStateStore`、`AgentState`、SSE 事件与故障现场时，见 [工作流流转与排障说明（flowExplain）](docs/flowExplain.md)。
