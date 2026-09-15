/**
 * 全局类型定义 —— 严格对齐《详细设计》§7（REST API）与 §4.1（SSE 事件协议）。
 * 后端尚未就绪，此处按契约的字段名/语义声明，字段以 §2 建表与 §7 响应为准。
 */

/* ------------------------------------------------------------------ */
/* 统一返回包装（§7：Result<T> 包装；分页返回 PageResult）             */
/* ------------------------------------------------------------------ */

export interface Result<T> {
  code: string
  message: string
  data: T
  traceId?: string
}

/** PageResult<T>：兼容 records / list / items 三种常见命名 */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  pages?: number
  records: T[]
}

/* ------------------------------------------------------------------ */
/* 认证 / 用户（§7 行 1-3）                                            */
/* ------------------------------------------------------------------ */

/** TokenPair：双 token（Access 2h + Refresh 7d）。兼容多种字段命名 */
export interface TokenPair {
  accessToken: string
  refreshToken: string
  tokenType?: string
  expiresIn?: number
  // 原始字段兜底（后端命名未知时归一化用）
  access?: string
  refresh?: string
  [k: string]: unknown
}

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload {
  username: string
  password: string
  email?: string
}

export interface UserVO {
  id: number
  username: string
  email?: string | null
  nickname: string
  status?: number
  createdAt?: string
  updatedAt?: string
}

/* ------------------------------------------------------------------ */
/* 聊天模型清单（§7 行 4：GET /api/chat/models）                       */
/* ------------------------------------------------------------------ */

export interface ModelVO {
  id: string
  displayName: string
  supportsThinking: boolean
  online: boolean
  provider?: string
  default?: boolean
}

/* ------------------------------------------------------------------ */
/* 会话 / 消息（§7 行 8-9；§2 chat_session / chat_message）            */
/* ------------------------------------------------------------------ */

export interface SessionVO {
  id: number
  title: string
  modelId: string
  pinned: boolean
  archived: boolean
  createdAt: string
  updatedAt: string
  lastMessageAt?: string
}

export interface CreateSessionPayload {
  modelId: string
  title?: string
}

/** 工具调用快照（消息 tool_calls JSON / SSE tool_call 事件） */
export interface ToolCallSnapshot {
  name: string
  argsJson: string
  resultDigest?: string
  status: 'START' | 'END' | string
}

export type MsgRole = 'user' | 'assistant' | 'tool' | 'system'
export type FinishReason = 'STOP' | 'LENGTH' | 'ABORTED' | 'ERROR' | string

export interface MessageVO {
  id: number
  sessionId: number
  role: MsgRole
  content: string
  thinking?: string | null
  toolCalls?: ToolCallSnapshot[] | null
  hitlId?: number | null
  promptTokens?: number
  completionTokens?: number
  finishReason?: FinishReason | null
  createdAt: string
}

/** GET /api/sessions/{id}/messages 的查询参数（cursor=id&size=50 倒序） */
export interface MessageQuery {
  cursor?: number
  size?: number
}

/* ------------------------------------------------------------------ */
/* HITL resume（§4.3 / §7 行 7）                                       */
/* ------------------------------------------------------------------ */

export type HitlDecision = 'APPROVED' | 'REJECTED' | 'EDITED'

export interface HitlResumePayload {
  decision: HitlDecision
  editedArgsJson?: string
  sessionId: number
  modelId: string
}

/* ------------------------------------------------------------------ */
/* Skill（§7 行 10-12；§5.1）                                          */
/* ------------------------------------------------------------------ */

export type SkillSource = 'UPLOAD' | 'MARKET' | 'AGENT' | 'MANUAL'

export interface SkillVO {
  id: number
  name: string
  description: string
  contentMd: string
  source: SkillSource
  enabled: boolean
  version: string
  createdAt: string
  updatedAt: string
}

export interface SkillQuery {
  keyword?: string
  enabled?: boolean
  page?: number
  size?: number
}

/** 手工录入表单（POST /api/skills/install 的 JSON 形态） */
export interface SkillManualPayload {
  name: string
  description: string
  contentMd: string
  source: 'MANUAL'
}

/* ------------------------------------------------------------------ */
/* MCP（§7 行 13-14；§5.2）                                            */
/* ------------------------------------------------------------------ */

export type McpTransport = 'SSE' | 'STREAMABLE_HTTP' | 'STDIO'
export type HealthStatus = 'UNKNOWN' | 'HEALTHY' | 'DOWN'

export interface McpHeader {
  key: string
  value: string
}

export interface McpVO {
  id: number
  name: string
  transport: McpTransport
  url?: string | null
  command?: string | null
  args?: string[] | null
  headers?: Record<string, string> | null
  enabled: boolean
  healthStatus: HealthStatus
  toolNames: string[]
  lastProbeAt?: string | null
  createdAt?: string
  updatedAt?: string
}

/** POST /api/mcp 表单载荷 */
export interface McpUpsertPayload {
  name: string
  transport: McpTransport
  url?: string
  command?: string
  args?: string[]
  headers?: Record<string, string>
}

/** POST /api/mcp/{id}/test 同步返回 */
export interface McpTestResult {
  status: HealthStatus
  tools: string[]
}

/* ------------------------------------------------------------------ */
/* RAG（§7 行 15-19；§2 rag_document；§4.5/§4.6）                     */
/* ------------------------------------------------------------------ */

export type DocType = 'FILE' | 'TEXT'
export type RagStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'CHUNKED'
  | 'EMBEDDING'
  | 'READY'
  | 'FAILED'

export interface DocVO {
  id: number
  title: string
  docType: DocType
  fileType?: string | null
  sizeBytes: number
  status: RagStatus
  errorMsg?: string | null
  chunkCount: number
  tokenCount: number
  retryCount: number
  createdAt: string
  updatedAt: string
}

export interface RagDocQuery {
  status?: RagStatus
  page?: number
  size?: number
}

export interface RagTextPayload {
  title: string
  content: string
}

/** POST /api/rag/search 命中项 */
export interface RagSearchHit {
  docId: number
  seq: number
  score: number
  textSnippet: string
  title?: string
}

export interface RagSearchPayload {
  query: string
  topK: number
  rerank?: boolean
}

/* ------------------------------------------------------------------ */
/* 长期记忆（§7 行 20；§2 long_term_memory）                           */
/* ------------------------------------------------------------------ */

export type MemoryCategory = 'preference' | 'fact' | 'summary'

export interface MemoryVO {
  id: number
  category: MemoryCategory
  content: string
  sourceSessionId?: number | null
  hitCount: number
  status?: number
  createdAt: string
  updatedAt: string
}

/* ------------------------------------------------------------------ */
/* SSE 事件协议（§4.1）：event -> data 负载                            */
/* ------------------------------------------------------------------ */

export interface SseMetaEvent {
  messageId: number
  threadId: string
}
export interface SseDeltaEvent {
  text: string
}
export interface SseThinkingEvent {
  text: string
}
export interface SseToolCallEvent {
  name: string
  argsJson: string
  status: 'START' | 'END'
  resultDigest?: string
}
export interface SseInterruptEvent {
  threadId: string
  hitlId: number
  toolName: string
  argsJson: string
  reason: string
}
export interface SseDoneEvent {
  finishReason: FinishReason
  usage?: { p: number; c: number }
}
export interface SseErrorEvent {
  code: string
  message: string
}

/** SSE 事件类型联合 */
export type SseEventType =
  | 'meta'
  | 'delta'
  | 'thinking'
  | 'tool_call'
  | 'interrupt'
  | 'done'
  | 'error'
  | 'ping'
  | string

/** POST /api/chat/stream 请求体（§4.1） */
export interface ChatStreamRequest {
  sessionId: number
  modelId: string
  content: string
  /** 前端 crypto.randomUUID()，取消幂等键 */
  streamId: string
  useRag: boolean
  useSkills: boolean
  enableThinking?: boolean
}
