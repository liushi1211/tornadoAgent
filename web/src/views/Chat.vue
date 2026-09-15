<script setup lang="ts">
/**
 * 智能对话（详细设计 §4.1/§4.2/§4.3、§7 行 4-9、§10.5）
 * - 左栏：会话列表（新建/删除/归档，GET/POST/PATCH/DELETE /api/sessions）
 * - 顶部：模型下拉（GET /api/chat/models，supportsThinking 标记）
 * - 消息流：GET /api/sessions/{id}/messages；assistant 流式打字追加
 * - SSE：POST /api/chat/stream（streamId=crypto.randomUUID()）；停止 = abort + POST /api/chat/stop/{streamId}
 * - interrupt → HITL 审批卡片 → POST /api/chat/hitl/{threadId}/resume（§4.3 复用 §4.1 流管线，故带 streamId）并继续消费返回的 SSE
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatApi, sessionApi } from '@/api'
import { postSse, uuid } from '@/utils/sse'
import { useChatStore } from '@/stores/chat'
import type {
  FinishReason,
  HitlDecision,
  MsgRole,
  SseToolCallEvent,
  SessionVO
} from '@/types'

/* ------------------------------ 类型 ------------------------------ */

interface HitlState {
  threadId: string
  hitlId: number
  toolName: string
  argsJson: string
  reason: string
  resolved: boolean
  decision?: HitlDecision
  editing: boolean
  editedArgs: string
}

interface UIMessage {
  localId: string
  serverId?: number
  role: MsgRole
  content: string
  thinking: string
  thinkingOpen: boolean
  toolCalls: SseToolCallEvent[]
  finishReason?: FinishReason | null
  error?: string | null
  streaming: boolean
  createdAt: string
  hitl?: HitlState | null
}

/* ------------------------------ 状态 ------------------------------ */

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()

const sessions = ref<SessionVO[]>([])
const sessionsLoading = ref(false)
const currentSessionId = ref<number | null>(null)
const messages = ref<UIMessage[]>([])
const messagesLoading = ref(false)

const modelId = ref('')
const enableThinking = ref(false)
const input = ref('')
const streaming = ref(false)
const sidebarCollapsed = ref(false)

let abortCtrl: AbortController | null = null
let currentStreamId = ''

const listRef = ref<HTMLElement | null>(null)

/* ------------------------------ 计算属性 ------------------------------ */

const sortedSessions = computed(() =>
  [...sessions.value].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
    return String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''))
  })
)

const currentSession = computed(
  () => sessions.value.find((s) => s.id === currentSessionId.value) || null
)

const currentModel = computed(() =>
  chatStore.models.find((m) => m.id === modelId.value) || null
)

const thinkingAvailable = computed(
  () => !!currentModel.value?.supportsThinking && !streaming.value
)

const canSend = computed(
  () => !streaming.value && (input.value.trim().length > 0 || false)
)

/* ------------------------------ 工具函数 ------------------------------ */

function nowStr(): string {
  return new Date().toISOString()
}

function mkMessage(partial: Partial<UIMessage> & { role: MsgRole; content: string }): UIMessage {
  return reactive({
    localId: partial.localId || 'l-' + uuid(),
    role: partial.role,
    content: partial.content,
    thinking: partial.thinking || '',
    thinkingOpen: false,
    toolCalls: partial.toolCalls || [],
    finishReason: partial.finishReason ?? null,
    error: partial.error ?? null,
    streaming: partial.streaming || false,
    createdAt: partial.createdAt || nowStr(),
    hitl: partial.hitl || null
  }) as UIMessage
}

function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function statusTagType(status: string) {
  return status === 'END' ? 'success' : 'warning'
}

function finishType(reason: FinishReason | null | undefined): 'success' | 'warning' | 'danger' | 'info' | '' {
  switch (reason) {
    case 'ABORTED':
      return 'warning'
    case 'ERROR':
      return 'danger'
    case 'LENGTH':
      return 'info'
    case 'STOP':
      return 'success'
    default:
      return ''
  }
}

function finishText(reason: FinishReason | null | undefined): string {
  switch (reason) {
    case 'ABORTED':
      return '已停止（半截回复）'
    case 'ERROR':
      return '生成出错'
    case 'LENGTH':
      return '达到长度上限'
    case 'STOP':
      return '完成'
    default:
      return ''
  }
}

/* ------------------------------ 会话管理 ------------------------------ */

async function loadSessions() {
  sessionsLoading.value = true
  try {
    sessions.value = await sessionApi.list()
  } catch {
    /* 拦截器已 toast */
  } finally {
    sessionsLoading.value = false
  }
}

async function createSession(silent = false): Promise<number | null> {
  if (!modelId.value && chatStore.models.length) modelId.value = chatStore.models[0].id
  if (!modelId.value) {
    ElMessage.warning('模型清单尚未加载，请稍候再新建会话')
    return null
  }
  try {
    const s = await sessionApi.create({ modelId: modelId.value, title: '新会话' })
    sessions.value.unshift(s)
    currentSessionId.value = s.id
    messages.value = []
    if (!silent) router.push(`/chat/${s.id}`)
    return s.id
  } catch {
    return null
  }
}

async function openSession(id: number) {
  if (streaming.value) {
    ElMessage.warning('当前回答生成中，请先点击停止')
    return
  }
  currentSessionId.value = id
  const s = currentSession.value
  if (s?.modelId) modelId.value = s.modelId
  messagesLoading.value = true
  try {
    const list = await sessionApi.messages(id, { size: 50 })
    // 服务端 cursor 倒序返回；展示按时间正序
    messages.value = [...list]
      .reverse()
      .map((m) =>
        mkMessage({
          localId: 'm-' + m.id,
          serverId: m.id,
          role: m.role,
          content: m.content,
          thinking: m.thinking || '',
          toolCalls: (Array.isArray(m.toolCalls) ? m.toolCalls : []) as SseToolCallEvent[],
          finishReason: m.finishReason ?? null,
          createdAt: m.createdAt
        })
      )
    scrollToBottom()
  } catch {
    messages.value = []
  } finally {
    messagesLoading.value = false
  }
}

async function removeSession(s: SessionVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除会话「${s.title}」？删除后消息记录不可恢复。`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await sessionApi.remove(s.id)
    sessions.value = sessions.value.filter((x) => x.id !== s.id)
    if (currentSessionId.value === s.id) {
      if (streaming.value) stop()
      currentSessionId.value = null
      messages.value = []
      router.replace('/chat')
    }
    ElMessage.success('会话已删除')
  } catch {
    /* noop */
  }
}

async function archiveSession(s: SessionVO) {
  try {
    await sessionApi.patch(s.id, { archived: !s.archived })
    s.archived = !s.archived
    ElMessage.success(s.archived ? '已归档（将触发长期记忆沉淀）' : '已取消归档')
  } catch {
    /* noop */
  }
}

async function onModelChange(id: string) {
  chatStore.setLastModel(id)
  const m = chatStore.models.find((x) => x.id === id)
  if (!m?.supportsThinking) enableThinking.value = false
  if (currentSessionId.value != null) {
    // 会话粘性模型：尽力同步到会话（后端以 session.modelId 为准）
    try {
      await sessionApi.patch(currentSessionId.value, { modelId: id } as any)
      const s = currentSession.value
      if (s) s.modelId = id
    } catch {
      /* 后端不支持该字段时忽略 */
    }
  }
}

/* ------------------------------ SSE 事件处理 ------------------------------ */

function handleSseEvent(type: string, data: any, asst: UIMessage) {
  switch (type) {
    case 'meta':
      asst.serverId = data?.messageId
      break
    case 'delta':
      if (data?.text) {
        asst.content += data.text
        scrollToBottom()
      }
      break
    case 'thinking':
      if (data?.text) asst.thinking += data.text
      break
    case 'tool_call': {
      const ev = data as SseToolCallEvent
      const last = [...asst.toolCalls].reverse().find((t) => t.name === ev?.name)
      if (ev?.status === 'END' && last && last.status === 'START') {
        last.status = 'END'
        last.resultDigest = ev.resultDigest
      } else {
        asst.toolCalls.push({
          name: ev?.name || 'tool',
          argsJson: ev?.argsJson || '',
          status: ev?.status || 'START',
          resultDigest: ev?.resultDigest
        })
      }
      scrollToBottom()
      break
    }
    case 'interrupt': {
      // HITL 挂起：渲染审批卡片；本轮流视为结束（sse.ts 已主动收尾）
      asst.hitl = reactive({
        threadId: String(data?.threadId ?? ''),
        hitlId: data?.hitlId,
        toolName: data?.toolName || '未知工具',
        argsJson: typeof data?.argsJson === 'string' ? data.argsJson : JSON.stringify(data?.argsJson ?? {}),
        reason: data?.reason || '该工具需要人工批准',
        resolved: false,
        editing: false,
        editedArgs: typeof data?.argsJson === 'string' ? data.argsJson : JSON.stringify(data?.argsJson ?? {}, null, 2)
      }) as HitlState
      scrollToBottom()
      break
    }
    case 'done': {
      asst.finishReason = data?.finishReason || 'STOP'
      if (data?.usage) {
        ;(asst as any).usage = data.usage
      }
      asst.streaming = false
      break
    }
    case 'error':
      asst.error = `${data?.code || ''} ${data?.message || '生成失败'}`.trim()
      ElMessage.error(asst.error)
      break
    case 'ping':
      /* 心跳帧，忽略 */
      break
    default:
      break
  }
}

function makeHandlers(asst: UIMessage) {
  return {
    onEvent: (type: string, data: any) => handleSseEvent(type, data, asst),
    onError: (err: Error & { __unauthorized?: boolean }) => {
      asst.error = err.message || '连接异常'
      if (!err.__unauthorized) ElMessage.error(asst.error)
      else router.push('/login')
    },
    onDone: () => {
      asst.streaming = false
      streaming.value = false
      abortCtrl = null
      currentStreamId = ''
      // 服务端可能已自动更新会话标题，静默刷新列表
      void loadSessions()
      scrollToBottom()
    }
  }
}

/* ------------------------------ 发送 / 停止 / HITL ------------------------------ */

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  if (!modelId.value) {
    await chatStore.loadModels()
    if (!modelId.value && chatStore.models.length) modelId.value = chatStore.models[0].id
    if (!modelId.value) {
      ElMessage.warning('暂无可用模型，请确认后端配置')
      return
    }
  }
  let sid = currentSessionId.value
  if (sid == null) {
    sid = await createSession(true)
    if (sid == null) return
  }

  messages.value.push(mkMessage({ role: 'user', content: text }))
  const asst = mkMessage({ role: 'assistant', content: '', streaming: true })
  messages.value.push(asst)
  input.value = ''
  streaming.value = true
  currentStreamId = uuid()
  abortCtrl = new AbortController()
  scrollToBottom()

  await postSse({
    url: chatApi.streamUrl(),
    body: {
      sessionId: sid,
      content: text,
      modelId: modelId.value,
      streamId: currentStreamId,
      useRag: chatStore.useRag,
      useSkills: chatStore.useSkills,
      enableThinking: enableThinking.value
    },
    signal: abortCtrl.signal,
    handlers: makeHandlers(asst)
  })
}

/** 停止（§4.2）：abort + POST /api/chat/stop/{streamId}（幂等，可重试） */
async function stop() {
  if (!streaming.value) return
  const sid = currentStreamId
  abortCtrl?.abort()
  if (sid) {
    try {
      await chatApi.stop(sid)
    } catch {
      /* 幂等重试由服务端兜底，前端不再打扰 */
    }
  }
}

/** HITL resume（§4.3）：同 threadId 重新走 §4.1 流管线，继续消费返回 SSE */
async function resume(asst: UIMessage, decision: HitlDecision) {
  if (!asst.hitl || asst.hitl.resolved || streaming.value) return
  const hitl = asst.hitl
  let editedArgsJson: string | undefined
  if (decision === 'EDITED') {
    try {
      JSON.parse(hitl.editedArgs || '{}')
    } catch {
      ElMessage.warning('编辑的参数不是合法 JSON')
      return
    }
    editedArgsJson = hitl.editedArgs
  }
  hitl.resolved = true
  hitl.decision = decision
  hitl.editing = false

  asst.streaming = true
  streaming.value = true
  currentStreamId = uuid()
  abortCtrl = new AbortController()
  scrollToBottom()

  await postSse({
    url: chatApi.hitlResumeUrl(hitl.threadId),
    body: {
      decision,
      editedArgsJson,
      sessionId: currentSessionId.value,
      modelId: modelId.value,
      // §4.3：resume 复用 §4.1 的 stream 管线，因此同样携带 streamId 以支持停止
      streamId: currentStreamId
    },
    signal: abortCtrl.signal,
    handlers: makeHandlers(asst)
  })
}

function toggleEdit(asst: UIMessage) {
  if (!asst.hitl || asst.hitl.resolved) return
  asst.hitl.editing = !asst.hitl.editing
}

/* ------------------------------ 生命周期 ------------------------------ */

function onKeydownEnter(e: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行
  if (e.key === 'Enter' && !e.shiftKey && !streaming.value) {
    e.preventDefault()
    void send()
  }
}

watch(
  () => route.params.sessionId,
  (val) => {
    const id = Number(val)
    if (!Number.isNaN(id) && id > 0 && id !== currentSessionId.value) {
      void openSession(id)
    }
  }
)

onMounted(async () => {
  await Promise.all([chatStore.loadModels(), loadSessions()])
  if (!modelId.value) {
    const fromSession = currentSession.value?.modelId
    modelId.value = fromSession || chatStore.lastModelId || chatStore.models[0]?.id || ''
  }
  const routeId = Number(route.params.sessionId)
  if (!Number.isNaN(routeId) && routeId > 0) {
    void openSession(routeId)
  } else if (sessions.value.length) {
    // 进入最近一次活跃会话
    const latest = sortedSessions.value.find((s) => !s.archived) || sortedSessions.value[0]
    if (latest) router.replace(`/chat/${latest.id}`)
  }
})
</script>

<template>
  <div class="chat-page">
    <!-- ===================== 会话侧栏（ChatView 内） ===================== -->
    <aside class="session-side" :class="{ collapsed: sidebarCollapsed }">
      <div class="side-head">
        <el-button type="primary" class="new-btn" @click="createSession()">
          <el-icon><Plus /></el-icon>&nbsp;新建对话
        </el-button>
        <el-button text class="fold-btn" @click="sidebarCollapsed = !sidebarCollapsed">
          <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
        </el-button>
      </div>
      <div class="session-list" v-loading="sessionsLoading">
        <div
          v-for="s in sortedSessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === currentSessionId }"
          @click="router.push(`/chat/${s.id}`)"
        >
          <div class="session-title">
            <el-icon v-if="s.pinned" color="#e6a23c" :size="12"><Paperclip /></el-icon>
            <span class="ellipsis">{{ s.title || '新会话' }}</span>
            <el-tag v-if="s.archived" size="small" type="info">已归档</el-tag>
          </div>
          <div class="session-meta ellipsis">
            {{ s.modelId }} · {{ String(s.updatedAt || '').replace('T', ' ').slice(0, 16) }}
          </div>
          <div class="session-actions" @click.stop>
            <el-tooltip content="归档/取消归档" placement="top">
              <el-button link size="small" @click="archiveSession(s)">
                <el-icon><Box /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="删除会话" placement="top">
              <el-button link size="small" class="danger-link" @click="removeSession(s)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>
        <el-empty v-if="!sessionsLoading && !sessions.length" description="暂无会话" :image-size="60" />
      </div>
    </aside>

    <!-- ===================== 主聊天区 ===================== -->
    <section class="chat-main">
      <header class="chat-head">
        <div class="head-left">
          <span class="sess-name ellipsis">{{ currentSession?.title || '新对话' }}</span>
        </div>
        <div class="head-right">
          <el-tooltip content="本轮对话启用知识库检索（kb_search）" placement="bottom">
            <el-switch v-model="chatStore.useRag" active-text="RAG" size="small" />
          </el-tooltip>
          <el-tooltip content="本轮对话装配用户技能" placement="bottom">
            <el-switch v-model="chatStore.useSkills" active-text="技能" size="small" />
          </el-tooltip>
          <el-tooltip
            :content="thinkingAvailable ? '展示模型思考过程（reasoningContent）' : '当前模型不支持深度思考'"
            placement="bottom"
          >
            <el-switch
              v-model="enableThinking"
              :disabled="!thinkingAvailable"
              active-text="深度思考"
              size="small"
            />
          </el-tooltip>
          <el-select
            v-model="modelId"
            class="model-select"
            placeholder="选择模型"
            @change="onModelChange"
          >
            <el-option v-for="m in chatStore.models" :key="m.id" :value="m.id" :label="m.displayName">
              <span class="opt-line">
                <span>{{ m.displayName }}</span>
                <span class="opt-tags">
                  <el-tag v-if="m.supportsThinking" size="small" effect="plain">思考</el-tag>
                  <el-tag :type="m.online ? 'success' : 'danger'" size="small" effect="plain">
                    {{ m.online ? '在线' : '离线' }}
                  </el-tag>
                </span>
              </span>
            </el-option>
          </el-select>
        </div>
      </header>

      <div ref="listRef" class="msg-list" v-loading="messagesLoading">
        <template v-if="messages.length">
          <div v-for="m in messages" :key="m.localId" class="msg-row" :class="m.role">
            <div class="bubble-wrap">
              <!-- 工具调用时间线 -->
              <div v-if="m.toolCalls.length" class="tool-timeline">
                <div v-for="(t, i) in m.toolCalls" :key="i" class="tool-line">
                  <el-tag size="small" :type="statusTagType(t.status)" effect="plain">
                    {{ t.status === 'END' ? '✓' : '▸' }} {{ t.name }}
                  </el-tag>
                  <el-tooltip v-if="t.resultDigest" :content="t.resultDigest" placement="top">
                    <span class="tool-digest ellipsis">{{ t.resultDigest }}</span>
                  </el-tooltip>
                  <el-tooltip v-else :content="t.argsJson" placement="top">
                    <span class="tool-digest ellipsis">{{ t.argsJson }}</span>
                  </el-tooltip>
                </div>
              </div>

              <!-- thinking 折叠面板 -->
              <el-collapse v-if="m.thinking" v-model="m.thinkingOpen" class="thinking-panel">
                <el-collapse-item name="thinking" title="思考过程">
                  <pre class="thinking-text">{{ m.thinking }}</pre>
                </el-collapse-item>
              </el-collapse>

              <!-- 消息气泡 -->
              <div class="bubble">
                <span class="msg-content">{{ m.content }}</span>
                <span v-if="m.streaming" class="cursor-blink">▍</span>
              </div>

              <!-- 错误 / 终止标记 -->
              <div v-if="m.error" class="error-line">
                <el-icon color="#f56c6c"><WarningFilled /></el-icon>&nbsp;{{ m.error }}
              </div>
              <div v-if="m.finishReason && finishText(m.finishReason)" class="finish-line">
                <el-tag size="small" :type="finishType(m.finishReason)" effect="plain">
                  {{ finishText(m.finishReason) }}
                </el-tag>
              </div>

              <!-- HITL 审批卡片 -->
              <div v-if="m.hitl" class="hitl-card">
                <div class="hitl-head">
                  <el-icon color="#e6a23c"><Stamp /></el-icon>
                  <b>工具执行审批</b>
                  <el-tag size="small" type="warning">{{ m.hitl.toolName }}</el-tag>
                  <el-tag v-if="m.hitl.resolved" size="small" :type="m.hitl.decision === 'REJECTED' ? 'danger' : 'success'">
                    {{ m.hitl.decision === 'APPROVED' ? '已批准' : m.hitl.decision === 'REJECTED' ? '已拒绝' : '已编辑并批准' }}
                  </el-tag>
                </div>
                <div class="hitl-reason">{{ m.hitl.reason }}</div>
                <pre v-if="!m.hitl.editing" class="hitl-args">{{ m.hitl.argsJson }}</pre>
                <el-input
                  v-else
                  v-model="m.hitl.editedArgs"
                  type="textarea"
                  :rows="4"
                  placeholder="编辑工具参数（JSON）"
                />
                <div v-if="!m.hitl.resolved" class="hitl-actions">
                  <el-button type="success" size="small" @click="resume(m, 'APPROVED')">批准</el-button>
                  <el-button type="danger" size="small" @click="resume(m, 'REJECTED')">拒绝</el-button>
                  <el-button v-if="!m.hitl.editing" size="small" @click="toggleEdit(m)">编辑参数</el-button>
                  <template v-else>
                    <el-button size="small" @click="toggleEdit(m)">取消编辑</el-button>
                    <el-button type="primary" size="small" @click="resume(m, 'EDITED')">编辑并批准</el-button>
                  </template>
                </div>
              </div>
            </div>
          </div>
        </template>

        <div v-else-if="!messagesLoading" class="welcome">
          <el-icon :size="46" color="#409eff"><ChatDotRound /></el-icon>
          <h3>开始一段新的对话</h3>
          <p>支持流式输出、工具调用可视化、深度思考与 HITL 人工审批。</p>
          <el-button type="primary" @click="createSession()">
            <el-icon><Plus /></el-icon>&nbsp;新建对话
          </el-button>
        </div>
      </div>

      <!-- 工具栏 -->
      <footer class="chat-foot">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          @keydown="onKeydownEnter"
        />
        <div class="foot-bar">
          <span class="foot-hint">
            模型：{{ currentModel?.displayName || '未选择' }}
            <template v-if="enableThinking && thinkingAvailable">· 深度思考已开启</template>
          </span>
          <el-button v-if="!streaming" type="primary" :disabled="!canSend" @click="send">
            <el-icon><Promotion /></el-icon>&nbsp;发送
          </el-button>
          <el-button v-else type="danger" @click="stop">
            <el-icon><VideoPause /></el-icon>&nbsp;停止
          </el-button>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  height: 100%;
}
/* ---------------- 侧栏 ---------------- */
.session-side {
  width: 260px;
  min-width: 260px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  transition: all 0.2s;
}
.session-side.collapsed {
  width: 0;
  min-width: 0;
  overflow: hidden;
  border-right: none;
}
.side-head {
  padding: 10px;
  display: flex;
  gap: 4px;
  border-bottom: 1px solid #f0f2f5;
}
.new-btn {
  flex: 1;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.session-item {
  position: relative;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 2px;
}
.session-item:hover {
  background: #f5f7fa;
}
.session-item.active {
  background: #ecf5ff;
}
.session-title {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}
.session-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 3px;
}
.session-actions {
  position: absolute;
  right: 6px;
  top: 8px;
  display: none;
}
.session-item:hover .session-actions {
  display: block;
}
.danger-link {
  color: #f56c6c;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* ---------------- 主区 ---------------- */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.chat-head {
  height: 52px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  gap: 12px;
}
.head-left {
  min-width: 0;
}
.sess-name {
  font-weight: 600;
  max-width: 240px;
  display: inline-block;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.model-select {
  width: 220px;
}
.opt-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}
.opt-tags {
  display: flex;
  gap: 4px;
}
/* ---------------- 消息流 ---------------- */
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 8%;
  background: var(--saa-bg);
}
.msg-row {
  display: flex;
  margin-bottom: 18px;
}
.msg-row.user {
  justify-content: flex-end;
}
.msg-row.user .bubble {
  background: #409eff;
  color: #fff;
}
.bubble-wrap {
  max-width: 78%;
  min-width: 120px;
}
.bubble {
  background: #fff;
  border-radius: 10px;
  padding: 12px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  line-height: 1.7;
  word-break: break-word;
  white-space: pre-wrap;
}
.msg-content {
  /* 纯文本展示（后端 markdown 渲染留待组件库方案） */
}
.cursor-blink {
  animation: blink 1s steps(2) infinite;
  color: #409eff;
}
@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
.tool-timeline {
  margin-bottom: 6px;
}
.tool-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.tool-digest {
  font-size: 12px;
  color: #909399;
  max-width: 320px;
}
.thinking-panel {
  margin-bottom: 6px;
  border-radius: 8px;
  background: #fafafa;
  padding: 0 10px;
}
.thinking-text {
  white-space: pre-wrap;
  font-size: 12px;
  color: #606266;
  margin: 0;
  font-family: inherit;
}
.error-line {
  margin-top: 6px;
  color: #f56c6c;
  font-size: 13px;
  display: flex;
  align-items: center;
}
.finish-line {
  margin-top: 6px;
}
/* ---------------- HITL ---------------- */
.hitl-card {
  margin-top: 10px;
  border: 1px solid #f3d19e;
  background: #fdf6ec;
  border-radius: 10px;
  padding: 12px 14px;
}
.hitl-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.hitl-reason {
  font-size: 13px;
  color: #b88230;
  margin-bottom: 8px;
}
.hitl-args {
  background: #fff;
  border: 1px dashed #e4e7ed;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  max-height: 160px;
  overflow: auto;
  margin: 0 0 10px;
}
.hitl-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
/* ---------------- 底部工具栏 ---------------- */
.welcome {
  text-align: center;
  color: #909399;
  margin-top: 15vh;
}
.welcome h3 {
  color: #303133;
}
.chat-foot {
  border-top: 1px solid #e4e7ed;
  background: #fff;
  padding: 12px 16px 10px;
}
.foot-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.foot-hint {
  font-size: 12px;
  color: #909399;
}
</style>
