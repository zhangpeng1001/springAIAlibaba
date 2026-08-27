import type { AgentState, Plan, TaskSummary, WorkflowEvent } from "../types/task";

const api = "/api/tasks";

/** 将非 2xx 响应解析成可展示的中文错误，而不是让页面显示底层 fetch 异常。 */
async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) }, ...init });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: "请求失败" }));
    throw new Error(body.message ?? "请求失败");
  }
  return response.json() as Promise<T>;
}

export const taskApi = {
  create: (question: string) => request<{ taskId: string; status: string }>(api, { method: "POST", body: JSON.stringify({ question }) }),
  list: () => request<TaskSummary[]>(api),
  get: (taskId: string) => request<AgentState>(`${api}/${taskId}`),
  plan: (taskId: string) => request<Plan>(`${api}/${taskId}/plan`),
  message: (taskId: string, message: string) => request(`${api}/${taskId}/messages`, { method: "POST", body: JSON.stringify({ message }) }),
  confirm: (taskId: string, planVersion: number) => request(`${api}/${taskId}/plan/confirm`, { method: "POST", body: JSON.stringify({ planVersion }) }),
  cancel: (taskId: string) => request(`${api}/${taskId}/cancel`, { method: "POST" })
};

/** 建立 SSE，并把严格结构化事件交给页面状态机；EventSource 会自动携带 Last-Event-ID 重连。 */
export function subscribeTask(taskId: string, onSnapshot: (state: AgentState) => void, onEvent: (event: WorkflowEvent) => void, onError: () => void): () => void {
  const source = new EventSource(`${api}/${taskId}/events`);
  source.addEventListener("TASK_SNAPSHOT", event => onSnapshot(JSON.parse((event as MessageEvent).data)));
  const eventTypes = ["TASK_CREATED", "TASK_ANALYZED", "TASK_RECOVERY_STARTED", "PLAN_GENERATED", "PLAN_REVISION_RECEIVED", "PLAN_WAITING_USER", "PLAN_REVISED", "PLAN_CONFIRMED", "PLAN_LOCKED", "RESEARCH_STARTED", "RESEARCH_PROGRESS", "RESEARCH_REVIEWING", "RESEARCH_REVIEW_FAILED", "RESEARCH_REPAIRED", "ANSWER_STARTED", "ANSWER_PROGRESS", "ANSWER_REVIEWING", "ANSWER_REVIEW_FAILED", "ANSWER_REPAIRED", "FILE_GENERATING", "FILE_WRITTEN", "TASK_SUCCESS", "TASK_FAILED", "WORKFLOW_CANCELLED"];
  eventTypes.forEach(type => source.addEventListener(type, event => onEvent(JSON.parse((event as MessageEvent).data))));
  source.onerror = onError;
  return () => source.close();
}
