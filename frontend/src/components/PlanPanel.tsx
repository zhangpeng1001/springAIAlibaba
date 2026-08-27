import { useState } from "react";
import type { AgentState } from "../types/task";

/** 展示锁定前的当前 Plan，并提供唯一可进入自动阶段的显式确认操作。 */
export function PlanPanel({ task, onMessage, onConfirm }: { task: AgentState; onMessage(message: string): Promise<void>; onConfirm(): Promise<void> }) {
  const [message, setMessage] = useState("");
  const editable = task.status === "WAITING_USER_PLAN" && !task.planConfirmed;
  const plan = task.currentPlan;
  if (!plan) return <section className="panel"><h2>当前纲要</h2><p>Agent 正在生成纲要…</p></section>;
  return <section className="panel">
    <h2>当前纲要 V{plan.version}</h2><p>{plan.goal}</p>
    <ol>{plan.items.map(item => <li key={item.id}><strong>{item.title}</strong><span>{item.description}（{item.depth}）</span></li>)}</ol>
    {task.planFeedbackHistory.map((feedback, index) => <div className="chat agent" key={`${feedback.createdAt}-${index}`}><b>你：</b>{feedback.message}<br /><b>Agent：</b>{feedback.summary}</div>)}
    {editable && <>
      <textarea aria-label="输入修改意见" value={message} onChange={event => setMessage(event.target.value)} placeholder="例如：增加 Docker，强化并发和 JVM。" />
      <div className="actions"><button onClick={() => message.trim() && void onMessage(message.trim()).then(() => setMessage(""))}>发送修改意见</button><button className="primary" onClick={() => void onConfirm()}>确认纲要并开始执行</button></div>
    </>}
    {task.planConfirmed && <p className="notice">纲要已确认，正在锁定并开始研究。</p>}
  </section>;
}
