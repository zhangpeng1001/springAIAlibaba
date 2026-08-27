import { useCallback, useEffect, useRef, useState } from "react";
import { subscribeTask, taskApi } from "../api/taskApi";
import type { AgentState, WorkflowEvent } from "../types/task";

/** 维护任务快照与 SSE 事件，按 eventId 去重以处理浏览器断线后的事件回放。 */
export function useTask(taskId?: string) {
  const [task, setTask] = useState<AgentState>();
  const [events, setEvents] = useState<WorkflowEvent[]>([]);
  const [error, setError] = useState<string>();
  const seen = useRef(new Set<number>());

  const refresh = useCallback(async () => {
    if (!taskId) return;
    try { setTask(await taskApi.get(taskId)); } catch (reason) { setError(reason instanceof Error ? reason.message : "读取任务失败"); }
  }, [taskId]);

  useEffect(() => {
    if (!taskId) return;
    void refresh();
    return subscribeTask(taskId, setTask, event => {
      if (seen.current.has(event.eventId)) return;
      seen.current.add(event.eventId);
      setEvents(previous => [...previous, event]);
      void refresh();
    }, () => setError("SSE 连接暂时中断，浏览器将自动重连。"));
  }, [taskId, refresh]);

  return { task, events, error, refresh };
}
