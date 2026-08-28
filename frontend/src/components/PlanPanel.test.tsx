import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PlanPanel } from "./PlanPanel";
import type { AgentState } from "../types/task";

/** 初始纲要面板只读展示，不再出现修改意见和确认按钮。 */
describe("PlanPanel", () => {
  const task: AgentState = {
    taskId: "task-ui", question: "如何学习 Java？", status: "ANSWER_GENERATING", currentNode: "ANSWER_GENERATE",
    currentPlan: { version: 1, title: "Java 路线", goal: "建立体系", items: [{ id: "JAVA-1", title: "Java 基础", description: "语法", order: 1, required: true, depth: "NORMAL" }] },
    answers: {}, outputFiles: [], createdAt: "2026-01-01", updatedAt: "2026-01-01"
  };

  it("shows the generated outline as read only", () => {
    render(<PlanPanel task={task} />);
    expect(screen.getByText("Java 基础")).toBeInTheDocument();
    expect(screen.queryByText("确认纲要并开始执行")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("输入修改意见")).not.toBeInTheDocument();
  });
});
