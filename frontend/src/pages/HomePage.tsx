import type { TaskSummary } from "../types/task";
import { TaskInput } from "../components/TaskInput";

/** 应用首页，负责创建任务和展示可继续查看的历史任务。 */
export function HomePage({ tasks, onCreate, onOpen }: { tasks: TaskSummary[]; onCreate(question: string): Promise<void>; onOpen(taskId: string): void }) {
  return <main className="home"><header><h1>知识研究 Agent</h1><p>输入问题后，Agent 将自动生成纲要、详细解答和 Markdown 文档。</p></header>
    <TaskInput onSubmit={onCreate} />
    <section><h2>历史任务</h2>{tasks.length === 0 ? <p>还没有任务。</p> : <ul className="history">{tasks.map(task => <li key={task.taskId}><button onClick={() => onOpen(task.taskId)}>{task.question}</button><span>{task.status}</span></li>)}</ul>}</section>
  </main>;
}
