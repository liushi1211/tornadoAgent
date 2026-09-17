# tornadoAgent — 基于 Spring AI Alibaba 的智能体 Web 应用

> 设计文档：概要《设计方案 v0.3》+《详细设计 v1.0》（位于 D:\workspace\saa-agent-design\，本仓库外）
> 技术栈：JDK17 · Spring Boot 3.5.8 · Spring AI 1.1.2 · Spring AI Alibaba 1.1.2.0 · Vue 3 + Vite + Element Plus · MySQL 5.7(cloud_ai) · Redis Stack · Nacos(可选)

## 目录

```
tornadoAgent/
├─ server/                  # 后端（COLA 分层，重构已完成 M1–M6，六模块）
│  ├─ tornado-client/        # 对外契约：Result/PageResult/错误码/各域 DTO/Cmd/UserContext
│  ├─ tornado-domain/        # 领域层（零框架）：聚合根/值对象/状态机/仓储与网关接口
│  ├─ tornado-infrastructure/# 基础设施：Repository(DO+Mapper)/Gateway 实现(Redis/Redisson/Nacos/LLM/MCP/沙箱/Crypto)
│  ├─ tornado-app/           # 应用层：CmdExe/Service 编排、AgentAssembler、拦截器、xxl-job Handler
│  ├─ tornado-adapter/       # 适配层：Controller/SSE/鉴权过滤器/GlobalExceptionHandler
│  └─ tornado-start/         # 可执行入口：TornadoAgentApplication + application.yml + 全局 config + ArchUnit
│                            #（tornado-common/tornado-boot 为迁移期旧模块，M6 已退役删除）
├─ web/                      # 前端 Vue3（Login/Chat/Skills/Mcp/Rag/Memories + sse.ts）；deploy/nginx.conf 反代
├─ docker-compose.yml        # 仅 web + app；Redis/Nacos/xxl-job/MySQL 全部复用宿主机（经 host.docker.internal）
└─ .env.example              # 复制为 .env 填密钥
```
> 分层依赖铁律：`adapter → app → domain ← infrastructure`，`client` 全局可见，`domain` 不依赖任何技术框架（务实例外：chat 的运行时编排类在 app 允许 import Spring AI/SAA，见技术方案 §5.2）。可执行入口在 `tornado-start`。

## 本地开发

前置：宿主机 MySQL 5.7 已建库 `cloud_ai` 并执行详细设计 §2 DDL；Redis Stack（`docker run -d -p 6379:6379 -p 8001:8001 redis/redis-stack:latest`，普通 redis:7 建不了向量索引；8001 是 RedisInsight）；环境变量 `SAA_KEY_DASHSCOPE=sk-***`。

```bash
# 后端（Maven 在 D:\soft\apache-maven-3.9.16，仓库 D:\repository）
cd server
/d/soft/apache-maven-3.9.16/bin/mvn -s /d/soft/apache-maven-3.9.16/conf/settings.xml \
  -Dmaven.repo.local=/d/repository -DskipTests package
java -jar tornado-start/target/tornado-start.jar          # :8080（主类 com.tornado.start.TornadoAgentApplication）

# 前端
cd web && npm install --registry=https://registry.npmmirror.com && npm run dev   # :5173，/api 代理到 8080
```

## Docker 发布（前后端分离）

**拓扑**：`docker compose` 只跑 **web(Nginx) + app** 两个容器；MySQL / Redis-Stack / Nacos / xxl-job **复用宿主机**已有实例，容器内经 `host.docker.internal`（Docker Desktop 的 `extra_hosts: host-gateway`）访问。浏览器入口 **http://localhost:8085** → Nginx 托管前端 dist + `/api` 同源反代到 `app:8080`。

**前置**（宿主机）：MySQL 5.7 建好 `cloud_ai`；一个 `redis/redis-stack` 跑在宿主 `6379`（RAG 需 RediSearch 模块）；按需 Nacos `8848`、xxl-job `12982`。

**步骤**：
```bash
cp .env.example .env      # 填下列
docker compose up -d --build
```
`.env` 关键项：`AI_DASHSCOPE_API_KEY`（或 `SAA_KEY_DASHSCOPE`）、`DB_USER/DB_PASS`、`XXL_JOB_ADMIN_ADDR=http://host.docker.internal:12982`、`NACOS_ADDR=http://host.docker.internal:8848`；`SAA_JWT_SECRET/SAA_MASTER_KEY` 可留空用应用内默认（注意：一旦设置 `SAA_MASTER_KEY`，此前用默认密钥加密入库的 MCP headers 密文将解不开）。

**改代码后重建**（两个镜像都是镜像内多阶段构建，非热更新）：
```bash
docker compose up -d --build app    # 改了后端 Java / application.yml / pom（容器内重跑 mvn package）
docker compose up -d --build web    # 改了前端或 web/deploy/nginx.conf
docker compose up -d               # 只改了 .env（重载环境变量，不用重建）
docker compose build --no-cache app web && docker compose up -d   # 缓存导致没重编时
```

**验证/查看**：
```bash
docker compose ps                     # 期望 app Up(healthy)、web Up
docker compose logs -f app
docker exec redis-stack redis-cli KEYS 'u:*'   # 发一条聊天后，短期记忆/断点应出现在宿主 redis
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8085/api/chat/models   # 期望 401（链路通、被 JWT 拦）
```

**几个踩过的坑**：
- **SSE**：Nginx 必须给 `/api/chat/stream` 和 `/api/chat/hitl/{id}/resume` **各**一个 `proxy_buffering off; gzip off` 专用 location（否则流式/审批续流被缓冲、看不到逐字增量）；`client_max_body_size ≥ 25m`，否则 RAG 20MB 上传 413。
- **登录 403**：Spring CORS 只按请求的 `Origin` 头比对 `allowedOrigins`（与是否同源无关），Docker 入口 `http://localhost:8085` 未列入就会被判非法跨域 → 403。已在 `AuthWebConfig` 改用 `allowedOriginPatterns`（放行 `localhost:[*]` 等）；上生产用固定域名时记得追加对应 origin。
- **两个 Redis 实例**：早期 compose 自带一个 redis，与宿主机那个是**独立实例**，导致「本地有数据、Docker 看不到」。现已让 `app` 直连宿主 `host.docker.internal:6379`、移除 compose 的 redis 服务，二者读写同一实例。部署到无宿主 Redis 的服务器时，把 compose 里注释的 `redis-stack` 服务恢复、`REDIS_HOST` 改回 `redis-stack`。

## 模型配置（Nacos）

`saa.nacos.enabled=true` 时后端用 nacos-client（gRPC 长连接）注册监听：启动拉全量 + 配置变更**秒级实时推送**覆盖本地 `chat.models` 兜底；另有 5 分钟一次的对账轮询兜底（防连接抖动漏推送）。改配置无需重启，格式见详细设计 §6。服务端开启鉴权时配 `saa.nacos.username/password`。关闭时仅用 application.yml 中的模型清单。

## 数据源（Druid）

连接池用 **Druid**（`druid-spring-boot-3-starter`，替换默认 Hikari）：`spring.datasource.type=DruidDataSource` 强制生效，池参数（初始5/最小5/最大20、空闲回收保活）在 `application.yml`。开启 `stat + slf4j` 过滤器：慢 SQL（>1s）打日志、可执行 SQL 追踪。

- 监控台：启动后访问 `http://localhost:8080/druid`，账号 `DRUID_USER/DRUID_PASS`（默认 admin/admin123，生产务必改并加白名单）。
- **关于 Zebra**：点评 Zebra 是 javax/老 Spring 时代组件（内部 c3p0/老 Druid），与 Spring Boot 3.5 的 Jakarta 体系不兼容、本地仓库也无该构件；且其价值在读写分离/分库分表，当前单实例 MySQL 用不上。将来需要分片时接 **ShardingSphere-JDBC**（Boot 3 兼容），不要引 Zebra。

## 已知简化（多实例/生产前需处理，对应详细设计章节）

1. checkpoint 已用 TtlRedisSaver（跨实例/重启可恢复，TTL 默认 120min）；但 **InterruptionStore（HITL 中断元数据）仍在进程内**，多实例部署前需 Redis 化（§4.3）。
2. MCP 使用自研极简 JSON-RPC client（streamable-http + SSE），未接 STDIO 型。
3. rerank（gte-rerank）留了开关占位默认关，检索为纯向量 topK。
4. 短期记忆历史以文本注入 System Prompt；分页为手写 LIMIT/OFFSET。
5. 登出未做 refresh token 黑名单；消息渲染为纯文本（未接 Markdown 渲染库）。
6. RAG 首次上传前需确保 Redis Stack 可连通；索引 `saa_rag_idx` 由应用启动时自建（1024 维，text-embedding-v4 兼容模式）。

## 冒烟顺序建议

注册登录 → 聊天发一句/换模型/点停止 → 让模型"删除文档 xxx"触发 HITL 审批卡 → 装一个 SSE 型 MCP 探活 → 上传 PDF 后在 RAG 页检索调试 → 开 useRag 提问验证引用 → Skills 页上传一个 .md 并开关。
