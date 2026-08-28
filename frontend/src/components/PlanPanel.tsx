import type { AgentState } from "../types/task";

/** 只读展示自动生成的初始纲要，避免页面再提供已删除的确认和修改操作。 */
export function PlanPanel({ task }: { task: AgentState }) {
  const plan = task.currentPlan;
  if (!plan) return <section className="panel"><h2>初始纲要</h2><p>Agent 正在生成初始纲要…</p></section>;
  return <section className="panel">
    <h2>初始纲要</h2><p>{plan.goal}</p>
    <ol>{plan.items.map(item => <li key={item.id}><strong>{item.title}</strong><span>{item.description}（{item.depth}）</span></li>)}</ol>
  </section>;
}
