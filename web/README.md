# SAA Web · Spring AI Alibaba 智能体 Web 应用（前端）

Vue 3 + Vite + TypeScript + Pinia + Vue Router + Element Plus 构建的智能体工作台前端。
对接后端 `saa-server`（Spring Boot 3.5.x + Spring AI 1.1.2 + Spring AI Alibaba），
聊天走 SSE 流式（`fetch` + `ReadableStream`），支持停止、HITL 人工审批续流、
Skill / MCP / RAG / 长期记忆管理，多用户按后端 UserContext 隔离。

## 环境要求

- Node.js ≥ 20（本仓库在 Node v24 / npm 11 验证通过）
- npm（依赖安装统一走 npmmirror 镜像，已固化在 `.npmrc`）

## 本地开发

```bash
# 1. 安装依赖（镜像源已写入 .npmrc：registry=https://registry.npmmirror.com）
npm install --registry=https://registry.npmmirror.com

# 2. 启动开发服务器（端口 5173，/api 代理到 http://localhost:8080）
npm run dev
```

开发态通过 Vite `server.proxy` 把 `/api` 反代到后端 `http://localhost:8080`（`changeOrigin: true`），
前端代码与生产同构，接口基址统一为 `/api`，不写死后端域名。

> 后端未就绪时前端可独立启动，登录页可正常渲染；调用接口会因无后端返回而报错，属预期。

## 构建 / 预览

```bash
# 生产构建，产物输出到 dist/
npm run build

# 本地预览构建产物
npm run preview

# 类型检查（可选，不作为构建门禁；当前 0 error）
npm run type-check
```

> 注意：`package.json` 里的 `overrides.rollup = @rollup/wasm-node` 是因为某些 Windows 机器
> （Smart App Control / WDAC「应用控制策略」）会以 `ERR_DLOPEN_FAILED` 拦截 rollup 原生
> `.node` 二进制导致 `vite build` 崩溃。纯 WASM 版为 rollup 官方发布、API 一致。
> 在无此策略的机器（如 Docker 的 Linux 构建环境）上可删除该 overrides 块使用原生加速。

## Docker 构建

多阶段镜像：`node:22-alpine` 执行 `npm ci && npm run build` → `nginx:1.27-alpine` 托管 `dist`，
站点配置见 `deploy/nginx.conf`（含 `/api/chat/stream` 的 SSE 专用 location：
`proxy_http_version 1.1` + `proxy_buffering off` + 长 `read_timeout`，防止流式被反向代理攒缓冲）。

```bash
docker build -t saa/web:latest .
docker run --rm -p 8085:80 saa/web:latest
```

生产形态下 Nginx 将 `/api/` 同源反代到后端容器（`upstream saa_app { server app:8080; }`），天然免 CORS。

## 目录结构

```
web/
├─ index.html
├─ vite.config.ts          # server.port=5173 + /api proxy
├─ tsconfig.json / tsconfig.node.json
├─ Dockerfile              # 多阶段 node:22-alpine -> nginx:1.27-alpine
├─ deploy/nginx.conf       # SSE location（§10.2）
└─ src/
   ├─ main.ts              # Element Plus / 中文 locale / Pinia / Router 装配
   ├─ App.vue
   ├─ types.ts             # §7 契约的 TS 类型定义
   ├─ api/index.ts         # 按模块封装全部 §7 REST 接口
   ├─ utils/
   │  ├─ request.ts        # axios 实例 + Result 解包 + Bearer + 401→refresh 重放
   │  ├─ auth.ts           # 双 token 存储（localStorage）
   │  └─ sse.ts            # fetch POST + ReadableStream 解析 SSE 帧（§4.1）
   ├─ stores/              # user / chat
   ├─ router/index.ts      # 全局前置守卫：无 token 跳 /login
   ├─ layouts/MainLayout.vue  # 左侧全局导航
   └─ views/               # Login / Chat / Skills / Mcp / Rag / Memories
```

## SSE 协议要点（对齐详细设计 §4.1）

`POST /api/chat/stream`，请求头 `Authorization: Bearer <access>` + `Accept: text/event-stream`。
逐帧解析 `event:` / `data:`，事件类型：`meta` `{messageId,threadId}`、`delta` `{text}`、
`thinking` `{text}`、`tool_call` `{name,argsJson,status,resultDigest?}`、
`interrupt` `{threadId,hitlId,toolName,argsJson,reason}`（收到即本轮流结束，等待 resume）、
`done` `{finishReason,usage}`、`error` `{code,message}`。
`interrupt` 后经 `POST /api/chat/hitl/{threadId}/resume` 以同协议续流，前端继续消费返回的 SSE。

> 已知：生产 Nginx 的 SSE 专用 location 仅覆盖 `/api/chat/stream`；`/api/chat/hitl/*/resume`
> 亦为流式，若在高缓冲代理下出现攒段，可在 `deploy/nginx.conf` 追加同规则（保持 §10.2 原样，故未改动）。
