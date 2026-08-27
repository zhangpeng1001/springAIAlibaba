# 系统架构

## 职责边界

StateGraph 负责节点和条件边；Java 服务负责状态校验、审核轮次、取消、文件安全与持久化；LLM 仅输出受约束的认知结果，无法决定路径或程序状态。

```text
问题 → TASK_ANALYZE → PLAN_DRAFT → WAITING_USER_PLAN
                                    │
                    用户修改 ──────┘
                                    │ 显式确认
                              PLAN_LOCK → RESEARCH → RESEARCH_REVIEW
                                                    │失败
                                            RESEARCH_REPAIR ─┘
                                                    │通过
                                           ANSWER_GENERATE → ANSWER_REVIEW
                                                    │失败
                                             ANSWER_REPAIR ─┘
                                                    │通过
                                     TITLE_GENERATE → FILE_GENERATE → SUCCESS
```

`WAITING_USER_PLAN` 是 Graph 的终止分支。只有确认接口校验“当前状态、当前 Plan、客户端版本”全部一致后，自动分支才从 `PLAN_LOCK` 开始。

## 状态与恢复

`state.json` 是唯一业务状态源。每次变更在任务级锁内写入临时文件，再使用原子替换；Graph 的内存状态不会承担恢复职责。启动时：

- `WAITING_USER_PLAN` 保持等待；
- Plan 修订中的任务使用已落盘 `pendingPlanFeedback` 继续；
- 已确认/锁定的任务重新进入自动 Graph 分支；
- 已写入的 research/answer 工件不会重复生成。

事件先追加到 `events.jsonl`，再分发给 SSE。连接先收到任务快照，再按 `Last-Event-ID` 或 `afterId` 回放遗漏事件。

## 安全和边界

- 研究 Prompt 明确禁止外部搜索、URL、RAG、MCP 和伪造来源。
- 研究与写作并行，但每次 `state.json` 修改按任务锁串行化。
- 文件名经过 Unicode 规范化、非法字符清理、保留设备名拦截和 `answer` 根目录边界校验。
- Review 循环最多三轮，可由 `application.yml` 配置；超过上限进入失败终态。
- 一旦 Plan 锁定，后续阶段不允许修改范围；发现缺口只能以审核失败或异常记录呈现。
