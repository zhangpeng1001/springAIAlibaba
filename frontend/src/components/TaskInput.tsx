import { useState } from "react";

/** 主页问题输入框；提交时禁用按钮以避免重复创建任务。 */
export function TaskInput({ onSubmit }: { onSubmit(question: string): Promise<void> }) {
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!question.trim()) return;
    setBusy(true);
    try { await onSubmit(question.trim()); setQuestion(""); } finally { setBusy(false); }
  }
  return <form className="task-input" onSubmit={submit}>
    <label htmlFor="question">请输入你的问题</label>
    <textarea id="question" value={question} onChange={event => setQuestion(event.target.value)} placeholder="例如：如何学习 Java 后端？" maxLength={2000} />
    <button disabled={busy}>{busy ? "正在创建…" : "开始任务"}</button>
  </form>;
}
