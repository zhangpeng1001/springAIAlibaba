import type { AgentState } from "../types/task";

/** 将后端错误码、阶段和说明放在同一位置，方便用户判断是否需新建任务。 */
export function ErrorPanel({ task, connectionError }: { task?: AgentState; connectionError?: string }) {
  if (!connectionError && task?.status !== "FAILED" && task?.status !== "CANCELLED") return null;
  return <section className="error"><strong>{task?.status === "CANCELLED" ? "任务已取消" : "任务异常"}</strong><p>{task?.errorCode} {task?.errorMessage ?? connectionError}</p></section>;
}
