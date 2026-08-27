import type { AgentState, WorkflowEvent } from "../types/task";
import { ErrorPanel } from "../components/ErrorPanel";
import { PlanPanel } from "../components/PlanPanel";
import { TaskResult } from "../components/TaskResult";
import { WorkflowProgress } from "../components/WorkflowProgress";

/** 三栏任务详情页：状态、Plan/对话与 Workflow 进度始终围绕同一真实任务快照渲染。 */
export function TaskPage({ task, events, error, onMessage, onConfirm, onCancel, onBack }: { task?: AgentState; events: WorkflowEvent[]; error?: string; onMessage(message: string): Promise<void>; onConfirm(): Promise<void>; onCancel(): Promise<void>; onBack(): void }) {
  if (!task) return <main className="home"><button onClick={onBack}>← 返回任务列表</button><p>正在读取任务…</p></main>;
  return <main className="task-page"><button onClick={onBack}>← 返回任务列表</button><header><h1>{task.question}</h1><span className="status">{task.status}</span>{!["SUCCESS", "FAILED", "CANCELLED"].includes(task.status) && <button onClick={() => void onCancel()}>取消任务</button>}</header>
    <ErrorPanel task={task} connectionError={error} />
    <div className="columns"><aside className="panel"><h2>Task 状态</h2><p>当前阶段：{task.currentNode}</p><p>任务类型：{task.taskType ?? "待分析"}</p><p>Plan 版本：V{task.planVersion || "-"}</p></aside>
      <div><PlanPanel task={task} onMessage={onMessage} onConfirm={onConfirm} /><TaskResult task={task} /></div><WorkflowProgress task={task} /></div>
    <section className="panel"><h2>事件记录</h2><ul className="events">{events.slice(-20).reverse().map(event => <li key={event.eventId}>#{event.eventId} {event.type} · {event.stage}</li>)}</ul></section>
  </main>;
}
