import type { AgentState } from "../types/task";

/** 仅显示后端真实回传的路径和文件名，不在浏览器端伪造本机文件访问能力。 */
export function TaskResult({ task }: { task: AgentState }) {
  if (task.status !== "SUCCESS") return null;
  return <section className="panel result"><h2>任务完成</h2><p>输出目录：<code>{task.outputDirectory}</code></p><h3>生成文件</h3><ul>{task.outputFiles.map(file => <li key={file}>{file}</li>)}</ul></section>;
}
