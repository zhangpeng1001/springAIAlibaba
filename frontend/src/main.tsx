import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";

/** React 浏览器入口。 */
createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
