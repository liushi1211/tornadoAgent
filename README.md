# tornadoAgent — 基于 Spring AI Alibaba 的智能体 Web 应用

> 设计文档：概要《设计方案 v0.3》+《详细设计 v1.0》（位于 D:\workspace\saa-agent-design\，本仓库外）
> 技术栈：JDK17 · Spring Boot 3.5.8 · Spring AI 1.1.2 · Spring AI Alibaba 1.1.2.0 · Vue 3 + Vite + Element Plus · MySQL 5.7(cloud_ai) · Redis Stack · Nacos(可选)

## 目录

```
tornadoAgent/
├─ server/                  # 后端（Maven 多模块）
│  ├─ tornado-common/       # Result/异常/UserContext/JWT 过滤器/实体+Mapper/加解密
│  └─ tornado-boot/         # auth·chat(SSE/停止/HITL)·skill·mcp·rag·memory + 启动类
├─ web/                     # 前端 Vue3（Login/Chat/Skills/Mcp/Rag/Memories 六视图 + sse.ts）
├─ docker-compose.yml       # web + app + redis-stack（MySQL/Nacos 复用宿主机）
└─ .env.example             # 复制为 .env 填密钥
```

## 本地开发

前置：宿主机 MySQL 5.7 已建库 `cloud_ai` 并执行详细设计 §2 DDL；Redis Stack（`docker run -d -p 6379:6379 redis/redis-stack-server:latest`，普通 redis:7 建不了向量索引）；环境变量 `SAA_KEY_DASHSCOPE=sk-***`。

```bash
# 后端（Maven 在 D:\soft\apache-maven-3.9.16，仓库 D:\repository）
cd server
/d/soft/apache-maven-3.9.16/bin/mvn -s /d/soft/apache-maven-3.9.16/conf/settings.xml \
  -Dmaven.repo.local=/d/repository -DskipTests package
java -jar tornado-boot/target/tornado-boot.jar            # :8080

# 前端
cd web && npm install --registry=https://registry.npmmirror.com && npm run dev   # :5173，/api 代理到 8080
```

## Docker 发布（前后端分离）

```bash
cp .env.example .env   # 填 SAA_KEY_DASHSCOPE / DB_PASS / SAA_JWT_SECRET / SAA_MASTER_KEY
docker compose up -d --build     # web 镜像内置 nginx：dist 托管 + /api 同源反代 + SSE 关 buffering
# 浏览器访问 http://localhost:8085
```

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
