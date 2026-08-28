import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TaskResult } from "./TaskResult";
import type { AgentState } from "../types/task";

/** 成功页只使用后端回传文件列表，防止前端生成虚假结果。 */
describe("TaskResult", () => {
  it("renders backend supplied directory and files", () => {
    const task = { taskId: "t", question: "q", status: "SUCCESS", answers: {},
      outputDirectory: "answer/Java", outputFiles: ["README.md", "01-Java.md"], createdAt: "x", updatedAt: "x" } as AgentState;
    render(<TaskResult task={task} />);
    expect(screen.getByText("answer/Java")).toBeInTheDocument();
    expect(screen.getByText("01-Java.md")).toBeInTheDocument();
  });
});
