<script setup lang="ts">
/**
 * 消息全文检索（ES · tornado_chat_message）
 * - GET /api/search/messages：按当前用户隔离，支持 keyword / role / 时间范围 + 分页
 * - 命中卡片：角色标签 + 时间 + 正文（关键词高亮、超长折叠）+「跳转会话」
 * - 空结果提示历史回填（canal 只推新变更，存量需 POST /api/search/backfill 灌一次）
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { searchApi } from '@/api'
import type { MessageSearchHit } from '@/types'

const router = useRouter()

const loading = ref(false)
const searched = ref(false)
const rows = ref<MessageSearchHit[]>([])
const total = ref(0)

const query = reactive({ keyword: '', role: '', page: 1, size: 20 })
const range = ref<[string, string] | null>(null)

const ROLE_OPTIONS = [
  { label: '全部角色', value: '' },
  { label: '用户', value: 'user' },
  { label: '助手', value: 'assistant' }
]

function roleLabel(r: string): string {
  return r === 'user' ? '用户' : r === 'assistant' ? '助手' : r === 'system' ? '系统' : r || '—'
}
function roleType(r: string): 'primary' | 'success' | 'info' | 'warning' {
  return r === 'user' ? 'primary' : r === 'assistant' ? 'success' : 'info'
}

function escapeHtml(s: string): string {
  return (s || '').replace(/[&<>"']/g, (c) => {
    const map: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }
    return map[c] as string
  })
}

/** 先转义再按关键词包裹 <mark>，避免 XSS 且保证高亮安全 */
function highlight(text: string): string {
  const safe = escapeHtml(text || '')
  const kw = query.keyword.trim()
  if (!kw) return safe
  const pattern = escapeHtml(kw).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  try {
    return safe.replace(new RegExp(pattern, 'gi'), (m) => `<mark>${m}</mark>`)
  } catch {
    return safe
  }
}

async function doSearch(page = 1) {
  if (!query.keyword.trim() && !range.value) {
    ElMessage.warning('请输入关键词或选择时间范围')
    return
  }
  query.page = page
  loading.value = true
  try {
    const res = await searchApi.messages({
      keyword: query.keyword.trim(),
      role: query.role,
      from: range.value?.[0],
      to: range.value?.[1],
      page: query.page,
      size: query.size
    })
    rows.value = res.records
    total.value = res.total
    searched.value = true
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.role = ''
  range.value = null
  query.page = 1
  rows.value = []
  total.value = 0
  searched.value = false
}

function openSession(h: MessageSearchHit) {
  if (!h.sessionId) {
    ElMessage.warning('该命中缺少会话 ID')
    return
  }
  router.push(`/chat/${h.sessionId}`)
}

async function backfill() {
  loading.value = true
  try {
    const n = await searchApi.backfill(500)
    ElMessage.success(`已回填 ${n ?? 0} 条历史消息到 ES`)
    if (query.keyword.trim() || range.value) await doSearch(query.page)
  } catch {
    /* 拦截器已 toast */
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 不自动检索：等待用户输入，避免空查询全表扫描
})
</script>

<template>
  <div class="msg-search-page">
    <!-- 检索条件 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索历史对话内容，回车检索"
          clearable
          class="kw-input"
          @keyup.enter="doSearch(1)"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.role" placeholder="角色" class="role-select">
          <el-option v-for="o in ROLE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-date-picker
          v-model="range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="range-picker"
        />
        <el-button type="primary" :loading="loading" @click="doSearch(1)">
          <el-icon><Search /></el-icon>&nbsp;搜索
        </el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </el-card>

    <!-- 结果 -->
    <el-card shadow="never" class="result-card" v-loading="loading">
      <template #header>
        <div class="result-head">
          <span>
            共 <b>{{ total }}</b> 条命中
            <span v-if="query.keyword" class="muted">· 关键词「{{ query.keyword }}」</span>
          </span>
          <el-button link type="primary" @click="backfill">
            <el-icon><Refresh /></el-icon>&nbsp;回填历史消息
          </el-button>
        </div>
      </template>

      <div class="hit-list">
        <div v-for="h in rows" :key="h.id" class="hit-card">
          <div class="hit-top">
            <el-tag size="small" :type="roleType(h.role)" effect="dark">{{ roleLabel(h.role) }}</el-tag>
            <span class="hit-time">{{ h.createdAt }}</span>
            <el-button class="jump-btn" link type="primary" @click="openSession(h)">
              跳转会话 #{{ h.sessionId }}<el-icon><Right /></el-icon>
            </el-button>
          </div>
          <div class="hit-content" v-html="highlight(h.content)"></div>
        </div>

        <el-empty v-if="!loading && searched && !rows.length" description="没有匹配的消息" :image-size="80">
          <div class="empty-tip">若确定有历史对话却搜不到，点上方「回填历史消息」把存量灌入 ES（canal 只同步新变更）</div>
        </el-empty>
        <div v-else-if="!searched && !loading" class="search-placeholder">
          输入关键词检索你的历史对话，结果可一键跳转到对应会话。
        </div>
      </div>

      <div class="pager" v-if="total > query.size">
        <el-pagination
          :current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          background
          small
          @current-change="doSearch"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.msg-search-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  height: 100%;
  overflow: hidden;
}
.filter-card {
  border-radius: 10px;
  flex-shrink: 0;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.kw-input {
  width: 320px;
}
.role-select {
  width: 120px;
}
.range-picker {
  width: 360px;
}
.result-card {
  border-radius: 10px;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.result-card :deep(.el-card__body) {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.hit-list {
  flex: 1;
  overflow-y: auto;
  min-height: 160px;
}
.hit-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fff;
}
.hit-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.hit-time {
  font-size: 12px;
  color: #909399;
}
.jump-btn {
  margin-left: auto;
}
.hit-content {
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow: hidden;
  transition: max-height 0.2s ease;
}
.hit-content:hover {
  max-height: none;
}
.hit-content :deep(mark) {
  background: #ffe58f;
  color: #b8860b;
  padding: 0 1px;
  border-radius: 2px;
}
.empty-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
}
.search-placeholder {
  color: #a8abb2;
  font-size: 13px;
  text-align: center;
  padding: 60px 10px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
  flex-shrink: 0;
}
</style>
