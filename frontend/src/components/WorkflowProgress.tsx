import type { AgentState, TaskStatus } from "../types/task";

/** 按后端线性状态绘制五个阶段，不再显示研究/审核/修复等已删除节点。 */
const phases: Array<[string, TaskStatus[]]> = [
  ["任务理解", ["CREATED", "ANALYZING"]],
  ["生成初始纲要", ["PLAN_DRAFTING"]],
  ["逐项生成详细解答", ["ANSWER_GENERATING"]],
  ["生成标题", ["TITLE_GENERATING"]],
  ["生成 Markdown 文件", ["FILE_GENERATING"]]
];

/** 根据当前状态计算已完成、进行中和未开始阶段，终态 SUCCESS 会全部完成。 */
export function WorkflowProgress({ task }: { task: AgentState }) {
  const current = phases.findIndex(([, statuses]) => statuses.includes(task.status));
  const success = task.status === "SUCCESS";
  return <section className="panel"><h2>处理进度</h2><ul className="workflow">
    {phases.map(([name], index) => {
      const done = success || (!current || current < 0 ? false : index < current);
      const active = !success && index === current;
      return <li key={name} className={done ? "done" : active ? "active" : ""}>{done ? "✓" : active ? "⟳" : "○"} {name}</li>;
    })}
  </ul></section>;
}
