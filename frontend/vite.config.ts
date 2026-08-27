import { defineConfig } from "vitest/config";
import { loadEnv as loadViteEnv } from "vite";
import react from "@vitejs/plugin-react";

/** 开发服务器将 API 代理到 Spring Boot，避免开发环境出现跨域差异。 */
export default defineConfig(({ mode }) => {
  const apiProxyTarget = loadViteEnv(mode, ".", "").VITE_API_PROXY_TARGET ?? "http://localhost:8080";
  return {
    plugins: [react()],
    server: { proxy: { "/api": apiProxyTarget } },
    test: { environment: "jsdom", globals: true, setupFiles: ["./src/testSetup.ts"] }
  };
});
