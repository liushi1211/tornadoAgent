/**
 * §7 REST API 全量封装（按模块分组），基址统一 '/api'（见 utils/request.ts）。
 * 说明：/chat/stream、/chat/hitl/{threadId}/resume 为 SSE，不走 axios，由 utils/sse.ts 承载，
 * 此处仅导出其请求体类型辅助函数（URL 组装）。
 */
import { http } from '@/utils/request'
import type {
  TokenPair,
  LoginPayload,
  RegisterPayload,
  UserVO,
  ModelVO,
  SessionVO,
  CreateSessionPayload,
  MessageVO,
  SkillVO,
  SkillQuery,
  SkillManualPayload,
  PageResult,
  McpVO,
  McpUpsertPayload,
  McpTestResult,
  DocVO,
  RagDocQuery,
  RagTextPayload,
  RagSearchHit,
  RagSearchPayload,
  MemoryVO,
  MemoryCategory
} from '@/types'

/** 分页形态归一：兼容 records/list/items/rows 或直接数组 */
export function toPage<T>(raw: any): PageResult<T> {
  if (Array.isArray(raw)) {
    return { total: raw.length, page: 1, size: raw.length, records: raw as T[] }
  }
  const records: T[] = raw?.records ?? raw?.list ?? raw?.items ?? raw?.rows ?? []
  return {
    total: Number(raw?.total ?? records.length),
    page: Number(raw?.page ?? raw?.pageNum ?? 1),
    size: Number(raw?.size ?? raw?.pageSize ?? records.length),
    pages: raw?.pages != null ? Number(raw.pages) : undefined,
    records
  }
}

const asArray = <T>(raw: any): T[] => (Array.isArray(raw) ? raw : raw?.records ?? raw?.list ?? [])

/* ==================== 1-2 认证 ==================== */
export const authApi = {
  /** POST /api/auth/register → TokenPair */
  register: (payload: RegisterPayload) =>
    http.post<TokenPair>('/auth/register', payload),
  /** POST /api/auth/login → TokenPair */
  login: (payload: LoginPayload) =>
    http.post<TokenPair>('/auth/login', payload),
  /** POST /api/auth/logout → void（refresh 拉黑名单；access 缺失也可安全调用） */
  logout: (refreshToken?: string) =>
    http.post<void>('/auth/logout', { refreshToken }).catch(() => undefined)
}

/* ==================== 3 用户 ==================== */
export const userApi = {
  /** GET /api/users/me → UserVO */
  me: () => http.get<UserVO>('/users/me'),
  /** PATCH /api/users/me（nickname）→ UserVO */
  updateMe: (payload: { nickname: string }) =>
    http.patch<UserVO>('/users/me', payload)
}

/* ==================== 4-7 聊天 ==================== */
export const chatApi = {
  /** GET /api/chat/models → 原始 {models:[{id,displayName,supportsThinking}],default}（store 内解包） */
  models: () => http.get<any>('/chat/models'),
  /** POST /api/chat/stop/{streamId} → void（幂等，可重试） */
  stop: (streamId: string) => http.post<void>(`/chat/stop/${encodeURIComponent(streamId)}`),
  /** POST /api/chat/stream 的 URL（SSE 由 utils/sse.ts fetch 发起） */
  streamUrl: () => '/api/chat/stream',
  /** POST /api/chat/hitl/{threadId}/resume 的 URL（SSE 续流） */
  hitlResumeUrl: (threadId: string) =>
    `/api/chat/hitl/${encodeURIComponent(threadId)}/resume`
}

/* ==================== 8-9 会话与消息 ==================== */
export const sessionApi = {
  /** GET /api/sessions → SessionVO[] */
  list: () => http.get<SessionVO[]>('/sessions').then((r) => asArray<SessionVO>(r)),
  /** POST /api/sessions {modelId,title?} → SessionVO */
  create: (payload: CreateSessionPayload) => http.post<SessionVO>('/sessions', payload),
  /** PATCH /api/sessions/{id}：归档 archived / 置顶 pinned / 改名 title */
  patch: (id: number, payload: Partial<{ title: string; pinned: boolean; archived: boolean }>) =>
    http.patch<SessionVO>(`/sessions/${id}`, payload),
  /** DELETE /api/sessions/{id}：软删 */
  remove: (id: number) => http.del<void>(`/sessions/${id}`),
  /** GET /api/sessions/{id}/messages?cursor&size → MessageVO[]（服务端倒序，前端倒置展示） */
  messages: (id: number, params?: { cursor?: number; size?: number }) =>
    http
      .get<MessageVO[]>(`/sessions/${id}/messages`, { cursor: params?.cursor, size: params?.size ?? 50 })
      .then((r) => asArray<MessageVO>(r))
}

/* ==================== 10-12 Skill ==================== */
export const skillApi = {
  /** GET /api/skills?keyword&enabled&page&size → PageResult<SkillVO> */
  list: async (query: SkillQuery) => {
    const page = toPage<SkillVO>(
      await http.get<PageResult<SkillVO> | SkillVO[]>('/skills', {
        keyword: query.keyword || undefined,
        enabled: query.enabled,
        page: query.page ?? 1,
        size: query.size ?? 10
      })
    )
    // 后端 enabled 是 tinyint 0/1；el-switch 比较布尔，不归一会在挂载时误发 change 事件（进页面即触发 toggle 的 bug）
    page.records = page.records.map((r) => ({ ...r, enabled: Boolean(r.enabled) }))
    return page
  },
  /** POST /api/skills/install（multipart：.skill/.zip/.md 文件）→ SkillVO */
  installFile: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post<SkillVO>('/skills/install', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  /** POST /api/skills/install（JSON：{name,description,contentMd,source:MANUAL}）→ SkillVO */
  installManual: (payload: SkillManualPayload) =>
    http.post<SkillVO>('/skills/install', payload),
  /** GET /api/skills/{id} → { skill, files:[{relPath,fileType,sizeBytes}] } */
  detail: (id: number) => http.get<any>(`/skills/${id}`),
  /** GET /api/skills/{id}/file?path= → { relPath, fileType, content } */
  file: (id: number, path: string) =>
    http.get<{ relPath: string; fileType: string; content: string }>(`/skills/${id}/file`, { path }),
  /** PATCH /api/skills/{id}/toggle?enabled= → void（启停 / 确认 Agent 提议；后端取 query 参数） */
  toggle: (id: number, enabled: boolean) =>
    http.patch<void>(`/skills/${id}/toggle?enabled=${enabled}`),
  /** DELETE /api/skills/{id} → void */
  remove: (id: number) => http.del<void>(`/skills/${id}`)
}

/* ==================== 13-14 MCP ==================== */
export const mcpApi = {
  /** GET /api/mcp → McpVO[]（含 toolNames[]） */
  list: () =>
    http
      .get<McpVO[]>('/mcp')
      .then((r) => asArray<McpVO>(r).map((m) => ({ ...m, enabled: Boolean(m.enabled) }))),
  /** POST /api/mcp {name,transport,url,headers?,command?} → McpVO */
  create: (payload: McpUpsertPayload) => http.post<McpVO>('/mcp', payload),
  /** PATCH /api/mcp/{id} → McpVO */
  update: (id: number, payload: Partial<McpUpsertPayload>) =>
    http.patch<McpVO>(`/mcp/${id}`, payload),
  /** DELETE /api/mcp/{id} → void */
  remove: (id: number) => http.del<void>(`/mcp/${id}`),
  /** POST /api/mcp/{id}/test → {status,tools[]}（探活同步返回） */
  test: (id: number) => http.post<McpTestResult>(`/mcp/${id}/test`),
  /** PATCH /api/mcp/{id}/toggle?enabled= → void（启停；后端取 query 参数） */
  toggle: (id: number, enabled: boolean) =>
    http.patch<void>(`/mcp/${id}/toggle?enabled=${enabled}`)
}

/* ==================== 15-19 RAG ==================== */
export const ragApi = {
  /** GET /api/rag/documents?status&page&size → PageResult<DocVO> */
  list: async (query: RagDocQuery) =>
    toPage<DocVO>(
      await http.get<PageResult<DocVO> | DocVO[]>('/rag/documents', {
        status: query.status || undefined,
        page: query.page ?? 1,
        size: query.size ?? 10
      })
    ),
  /** POST /api/rag/documents（multipart file ≤20MB）→ DocVO(UPLOADED) */
  upload: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post<DocVO>('/rag/documents', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  /** POST /api/rag/texts {title,content} → DocVO */
  addText: (payload: RagTextPayload) => http.post<DocVO>('/rag/texts', payload),
  /** POST /api/rag/documents/{id}/retry → void */
  retry: (id: number) => http.post<void>(`/rag/documents/${id}/retry`),
  /** DELETE /api/rag/documents/{id} → void（级联删向量） */
  remove: (id: number) => http.del<void>(`/rag/documents/${id}`),
  /** POST /api/rag/search {query,topK,rerank} → [{docId,seq,score,textSnippet}] */
  search: (payload: RagSearchPayload) =>
    http
      .post<RagSearchHit[]>('/rag/search', {
        query: payload.query,
        topK: payload.topK,
        rerank: payload.rerank ?? true
      })
      .then((r) => asArray<RagSearchHit>(r))
}

/* ==================== 20 长期记忆 ==================== */
export const memoryApi = {
  /** GET /api/memories/long-term?category → MemoryVO[] */
  list: (category?: MemoryCategory) =>
    http.get<MemoryVO[]>('/memories/long-term', { category: category || undefined }).then((r) => asArray<MemoryVO>(r)),
  /** DELETE /api/memories/long-term（批量 ids）→ void */
  removeBatch: (ids: number[]) =>
    http.del<void>('/memories/long-term', { data: { ids }, params: { ids: ids.join(',') } } as any)
}
