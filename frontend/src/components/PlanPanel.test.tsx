import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PlanPanel } from "./PlanPanel";
import type { AgentState } from "../types/task";

/** Plan 界面回归测试：用户能看见纲要、发送意见并且只通过按钮确认。 */
describe("PlanPanel", () => {
  const task: AgentState = {
    taskId: "task-ui", question: "如何学习 Java？", status: "WAITING_USER_PLAN", currentNode: "WAITING_USER_PLAN",
    currentPlan: { version: 1, title: "Java 路线", goal: "建立体系", items: [{ id: "JAVA-1", title: "Java 基础", description: "语法", order: 1, required: true, depth: "NORMAL" }] },
    planVersion: 1, planConfirmed: false, planLocked: false, planFeedbackHistory: [], researchReviewRound: 0, answerReviewRound: 0, reviewResults: {}, outputFiles: [], createdAt: "2026-01-01", updatedAt: "2026-01-01"
  };

  it("shows plan and sends feedback and explicit confirmation", async () => {
    const onMessage = vi.fn().mockResolvedValue(undefined);
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(<PlanPanel task={task} onMessage={onMessage} onConfirm={onConfirm} />);
    expect(screen.getByText("Java 基础")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("输入修改意见"), { target: { value: "增加 Docker" } });
    fireEvent.click(screen.getByText("发送修改意见"));
    expect(onMessage).toHaveBeenCalledWith("增加 Docker");
    fireEvent.click(screen.getByText("确认纲要并开始执行"));
    expect(onConfirm).toHaveBeenCalledOnce();
  });
});
