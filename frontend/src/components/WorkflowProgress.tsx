import type { AgentState } from "../types/task";

const steps = [
  ["任务理解", ["ANALYZING", "PLAN_DRAFTING", "WAITING_USER_PLAN", "PLAN_REVISING", "PLAN_LOCKED", "RESEARCHING", "RESEARCH_REVIEWING", "RESEARCH_REPAIRING", "ANSWER_GENERATING", "ANSWER_REVIEWING", "ANSWER_REPAIRING", "TITLE_GENERATING", "FILE_GENERATING", "SUCCESS"]],
  ["纲要生成与确认", ["WAITING_USER_PLAN", "PLAN_REVISING", "PLAN_LOCKED", "RESEARCHING", "RESEARCH_REVIEWING", "RESEARCH_REPAIRING", "ANSWER_GENERATING", "ANSWER_REVIEWING", "ANSWER_REPAIRING", "TITLE_GENERATING", "FILE_GENERATING", "SUCCESS"]],
  ["知识研究", ["RESEARCHING", "RESEARCH_REVIEWING", "RESEARCH_REPAIRING", "ANSWER_GENERATING", "ANSWER_REVIEWING", "ANSWER_REPAIRING", "TITLE_GENERATING", "FILE_GENERATING", "SUCCESS"]],
  ["内容生成与审核", ["ANSWER_GENERATING", "ANSWER_REVIEWING", "ANSWER_REPAIRING", "TITLE_GENERATING", "FILE_GENERATING", "SUCCESS"]],
  ["文件生成", ["FILE_GENERATING", "SUCCESS"]]
] as const;

/** 根据后端终态和当前节点绘制进度，避免前端猜测实际 Workflow 是否已完成。 */
export function WorkflowProgress({ task }: { task: AgentState }) {
  return <section className="panel"><h2>Workflow</h2><ul className="workflow">
    {steps.map(([name, statuses]) => {
      const reached = statuses.includes(task.status as never);
      const active = task.currentNode?.replaceAll("_", " ").includes(name === "知识研究" ? "RESEARCH" : name === "文件生成" ? "FILE" : "___") ?? false;
      return <li key={name} className={reached ? "done" : active ? "active" : ""}>{reached ? "✓" : active ? "⟳" : "○"} {name}</li>;
    })}
  </ul>
  <p>研究审核：第 {task.researchReviewRound} 次</p><p>内容审核：第 {task.answerReviewRound} 次</p>
  </section>;
}
