/** 前后端共享的任务状态和结构化 Plan 类型。 */
export type TaskStatus = "CREATED" | "ANALYZING" | "PLAN_DRAFTING" | "ANSWER_GENERATING" | "TITLE_GENERATING" | "FILE_GENERATING" | "SUCCESS" | "FAILED" | "CANCELLED";

export interface PlanItem { id: string; title: string; description: string; order: number; required: boolean; depth: string; }
export interface Plan { version: number; title: string; goal: string; items: PlanItem[]; }
export interface AgentState {
  taskId: string; question: string; taskType?: string; status: TaskStatus; currentNode?: string;
  currentPlan?: Plan; answers: Record<string, Answer>; title?: string; outputDirectory?: string; outputFiles: string[];
  errorCode?: string; errorMessage?: string; createdAt: string; updatedAt: string;
}
export interface AnswerSection { title: string; content: string; }
export interface Answer { topicId: string; title: string; summary: string; sections: AnswerSection[]; }
/** 首页历史列表的轻量摘要；完整正文仅在进入详情页后读取。 */
export interface TaskSummary { taskId: string; question: string; status: TaskStatus; currentNode?: string; createdAt: string; updatedAt: string; }
export interface WorkflowEvent { eventId: number; type: string; taskId: string; time: string; stage: string; payload: Record<string, unknown>; }
export interface ApiError { errorCode: string; message: string; retryable: boolean; }
