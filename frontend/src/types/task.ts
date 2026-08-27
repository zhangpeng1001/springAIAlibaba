/** 前后端共享的任务状态和结构化 Plan 类型。 */
export type TaskStatus = "CREATED" | "ANALYZING" | "PLAN_DRAFTING" | "WAITING_USER_PLAN" | "PLAN_REVISING" | "PLAN_LOCKED" | "RESEARCHING" | "RESEARCH_REVIEWING" | "RESEARCH_REPAIRING" | "ANSWER_GENERATING" | "ANSWER_REVIEWING" | "ANSWER_REPAIRING" | "TITLE_GENERATING" | "FILE_GENERATING" | "SUCCESS" | "FAILED" | "CANCELLED";

export interface PlanItem { id: string; title: string; description: string; order: number; required: boolean; depth: string; }
export interface Plan { version: number; title: string; goal: string; items: PlanItem[]; }
export interface PlanFeedback { planVersion: number; message: string; summary: string; createdAt: string; }
export interface ReviewIssue { type: string; severity: string; message: string; }
export interface ReviewResult { passed: boolean; score: number; issues: ReviewIssue[]; }
export interface AgentState {
  taskId: string; question: string; taskType?: string; status: TaskStatus; currentNode?: string;
  currentPlan?: Plan; planVersion: number; planConfirmed: boolean; planLocked: boolean;
  planFeedbackHistory: PlanFeedback[]; researchReviewRound: number; answerReviewRound: number;
  reviewResults: Record<string, ReviewResult>; title?: string; outputDirectory?: string; outputFiles: string[];
  errorCode?: string; errorMessage?: string; createdAt: string; updatedAt: string;
}
/** 首页历史列表的轻量摘要；完整正文仅在进入详情页后读取。 */
export interface TaskSummary { taskId: string; question: string; status: TaskStatus; currentNode?: string; planVersion: number; createdAt: string; updatedAt: string; }
export interface WorkflowEvent { eventId: number; type: string; taskId: string; time: string; stage: string; payload: Record<string, unknown>; }
export interface ApiError { errorCode: string; message: string; retryable: boolean; }
