import { useEffect, useState } from "react";
import { taskApi } from "./api/taskApi";
import { useTask } from "./hooks/useTask";
import { HomePage } from "./pages/HomePage";
import { TaskPage } from "./pages/TaskPage";
import type { TaskSummary } from "./types/task";

/** 根组件只保存当前任务 ID；详情快照由 useTask 从后端和 SSE 同步。 */
export default function App() {
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [taskId, setTaskId] = useState<string>();
  const { task, events, error } = useTask(taskId);
  const refreshTasks = async () => setTasks(await taskApi.list());
  useEffect(() => { void refreshTasks(); }, []);
  if (!taskId) return <HomePage tasks={tasks} onOpen={setTaskId} onCreate={async question => { const created = await taskApi.create(question); setTaskId(created.taskId); await refreshTasks(); }} />;
  return <TaskPage task={task} events={events} error={error} onBack={() => { setTaskId(undefined); void refreshTasks(); }} onMessage={async message => { await taskApi.message(taskId, message); }} onConfirm={async () => { if (task) await taskApi.confirm(taskId, task.planVersion); }} onCancel={async () => { await taskApi.cancel(taskId); }} />;
}
