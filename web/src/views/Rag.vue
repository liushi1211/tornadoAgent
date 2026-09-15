<script setup lang="ts">
/**
 * RAG 知识库（§7 行 15-19、§4.5/§4.6）
 * - 文档表格：status 标签 + error_msg tooltip；存在处理中行时每 2s 轮询
 * - 上传：拖拽 Upload ≤20MB，类型 pdf/docx/md/txt（POST /api/rag/documents multipart）
 * - 文本粘贴：POST /api/rag/texts
 * - 重试 / 删除：POST /api/rag/documents/{id}/retry · DELETE
 * - 检索调试：POST /api/rag/search {query,topK,rerank} → 命中卡片（分数/来源/片段）
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile, type UploadInstance } from 'element-plus'
import { ragApi } from '@/api'
import type { DocVO, RagSearchHit, RagStatus } from '@/types'

const loading = ref(false)
const rows = ref<DocVO[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 10 })

const MAX_SIZE = 20 * 1024 * 1024
const ALLOW_EXT = ['pdf', 'docx', 'md', 'txt']

const uploadRef = ref<UploadInstance>()
const textDlg = reactive({ visible: false, saving: false, title: '', content: '' })

/* ---------------- 状态机展示（§4.5） ---------------- */
const PROCESSING: RagStatus[] = ['UPLOADED', 'PARSING', 'CHUNKED', 'EMBEDDING']

function statusType(s: RagStatus): 'success' | 'danger' | 'warning' | 'info' {
  switch (s) {
    case 'READY':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PARSING':
    case 'CHUNKED':
    case 'EMBEDDING':
      return 'warning'
    default:
      return 'info'
  }
}

function statusText(s: RagStatus): string {
  switch (s) {
    case 'READY':
      return '就绪'
    case 'FAILED':
      return '失败'
    case 'UPLOADED':
      return '待解析'
    case 'PARSING':
      return '解析中'
    case 'CHUNKED':
      return '已切分'
    case 'EMBEDDING':
      return '向量化中'
    default:
      return s
  }
}

const hasProcessing = computed(() => rows.value.some((r) => PROCESSING.includes(r.status)))

/* ---------------- 列表 + 2s 轮询（仅当存在处理中行） ---------------- */
async function reload(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await ragApi.list({ page: query.page, size: query.size })
    rows.value = res.records
    total.value = res.total
  } catch {
    if (!silent) {
      rows.value = []
      total.value = 0
    }
  } finally {
    loading.value = false
  }
}

let pollTimer: ReturnType<typeof setInterval> | null = null

function startPoll() {
  if (pollTimer) return
  pollTimer = setInterval(() => void reload(true), 2000)
}
function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 存在处理中行 → 每 2s 轮询；全部终态 → 停止轮询
watch(hasProcessing, (proc) => (proc ? startPoll() : stopPoll()), { immediate: true })

/* ---------------- 上传 ---------------- */
function beforeUpload(file: File): boolean {
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!ALLOW_EXT.includes(ext)) {
    ElMessage.error(`仅支持 ${ALLOW_EXT.join(' / ')} 文件`)
    return false
  }
  if (file.size > MAX_SIZE) {
    ElMessage.error('文件大小超过 20MB 限制')
    return false
  }
  return true
}

async function onFileChange(file: UploadFile) {
  const raw = file.raw as File | undefined
  if (!raw) return
  if (!beforeUpload(raw)) {
    uploadRef.value?.clearFiles()
    return
  }
  try {
    const doc = await ragApi.upload(raw)
    ElMessage.success(`已上传「${doc?.title || raw.name}」，解析入库进行中`)
    query.page = 1
    await reload()
  } catch {
    /* 413/400 等错误由拦截器 toast */
  } finally {
    uploadRef.value?.clearFiles()
  }
}

/* ---------------- 文本粘贴 ---------------- */
async function submitText() {
  if (!textDlg.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!textDlg.content.trim()) {
    ElMessage.warning('请填写正文')
    return
  }
  if (textDlg.content.length > 100 * 1024) {
    ElMessage.warning('文本超过 100KB 上限')
    return
  }
  textDlg.saving = true
  try {
    await ragApi.addText({ title: textDlg.title.trim(), content: textDlg.content })
    ElMessage.success('文本已入库')
    textDlg.visible = false
    textDlg.title = ''
    textDlg.content = ''
    query.page = 1
    await reload()
  } catch {
    /* noop */
  } finally {
    textDlg.saving = false
  }
}

/* ---------------- 重试 / 删除 ---------------- */
async function retry(r: DocVO) {
  try {
    await ragApi.retry(r.id)
    ElMessage.success('已重新入队')
    await reload()
  } catch {
    /* noop */
  }
}

async function remove(r: DocVO) {
  if (PROCESSING.includes(r.status)) {
    ElMessage.warning('文档处理中（A-RAG-0002），暂不可删除')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除「${r.title}」？将级联清理其向量数据，kb_search 不再命中。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await ragApi.remove(r.id)
    ElMessage.success('已删除')
    void reload()
  } catch {
    /* noop */
  }
}

/* ---------------- 检索调试 ---------------- */
const searchForm = reactive({ query: '', topK: 5, rerank: true })
const searching = ref(false)
const hits = ref<RagSearchHit[]>([])
const searched = ref(false)

async function doSearch() {
  if (!searchForm.query.trim()) {
    ElMessage.warning('请输入检索问题')
    return
  }
  searching.value = true
  try {
    hits.value = await ragApi.search({
      query: searchForm.query.trim(),
      topK: searchForm.topK,
      rerank: searchForm.rerank
    })
    searched.value = true
  } catch {
    hits.value = []
  } finally {
    searching.value = false
  }
}

function fmtSize(n: number) {
  if (!n) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/* ---------------- 生命周期 ---------------- */
function onPage(p: number) {
  query.page = p
  void reload()
}

onMounted(async () => {
  await reload()
})
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="rag-page">
    <!-- ================= 左：文档管理 ================= -->
    <section class="doc-side">
      <div class="toolbar">
        <el-button type="primary" @click="textDlg.visible = true">
          <el-icon><DocumentCopy /></el-icon>&nbsp;粘贴文本
        </el-button>
        <el-button @click="reload()"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
        <el-tag v-if="hasProcessing" type="warning" effect="plain" size="small">
          有文档处理中，每 2s 自动刷新
        </el-tag>
      </div>

      <el-upload
        ref="uploadRef"
        class="drop-upload"
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".pdf,.docx,.md,.txt"
        :on-change="onFileChange"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处或<em>点击上传</em></div>
        <template #tip>
          <div class="upload-tip">支持 pdf / docx / md / txt，单文件 ≤ 20MB（A-RAG-0001 校验）</div>
        </template>
      </el-upload>

      <el-card shadow="never" class="doc-card">
        <el-table v-loading="loading" :data="rows" stripe>
          <el-table-column label="文档" min-width="220">
            <template #default="{ row }">
              <div class="doc-title">
                <span class="ellipsis">{{ row.title }}</span>
                <el-tooltip v-if="row.errorMsg" :content="row.errorMsg" placement="top">
                  <el-icon color="#f56c6c"><WarningFilled /></el-icon>
                </el-tooltip>
              </div>
              <div class="doc-sub">
                {{ row.docType === 'TEXT' ? '文本' : (row.fileType || 'file') }} · {{ fmtSize(row.sizeBytes) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">
                {{ statusText(row.status) }}
              </el-tag>
              <div v-if="row.status === 'FAILED' && row.retryCount" class="doc-sub">已重试 {{ row.retryCount }} 次</div>
            </template>
          </el-table-column>
          <el-table-column label="分块/Token" width="110">
            <template #default="{ row }">
              <div>{{ row.chunkCount }} 块</div>
              <div class="doc-sub">{{ row.tokenCount }} tok</div>
            </template>
          </el-table-column>
          <el-table-column label="上传时间" width="160">
            <template #default="{ row }">
              {{ String(row.createdAt || '').replace('T', ' ').slice(0, 19) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'FAILED'" link type="primary" @click="retry(row)">重试</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无文档，上传文件或粘贴文本开始构建知识库" :image-size="70" />
          </template>
        </el-table>
        <div class="pager">
          <el-pagination
            :current-page="query.page"
            :page-size="query.size"
            :total="total"
            layout="total, prev, pager, next"
            background
            small
            @current-change="onPage"
          />
        </div>
      </el-card>
    </section>

    <!-- ================= 右：检索调试 ================= -->
    <aside class="search-side">
      <el-card shadow="never" class="search-card">
        <template #header>
          <div class="search-head">
            <el-icon><Search /></el-icon>
            <b>检索调试</b>
            <span class="muted">向量召回 topK {{ searchForm.rerank ? '+ 重排' : '' }}</span>
          </div>
        </template>

        <el-input
          v-model="searchForm.query"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入问题，验证 kb_search 召回与引用"
          @keyup.enter.ctrl="doSearch"
        />
        <div class="search-opts">
          <span>TopK</span>
          <el-input-number v-model="searchForm.topK" :min="1" :max="50" size="small" />
          <el-switch v-model="searchForm.rerank" size="small" active-text="重排" />
          <el-button type="primary" size="small" :loading="searching" @click="doSearch">检索</el-button>
        </div>

        <div class="hit-list" v-loading="searching">
          <template v-if="hits.length">
            <div v-for="(h, i) in hits" :key="i" class="hit-card">
              <div class="hit-top">
                <el-tag size="small" type="primary" effect="plain">#{{ i + 1 }} doc{{ h.docId }}·{{ h.seq }}</el-tag>
                <span class="score">score {{ Number(h.score ?? 0).toFixed(4) }}</span>
              </div>
              <div class="hit-text">{{ h.textSnippet }}</div>
            </div>
          </template>
          <el-empty
            v-else-if="searched"
            description="无命中：确认文档已 READY、非本人文档被过滤属正常"
            :image-size="70"
          />
          <div v-else class="search-placeholder">
            执行检索后在此查看命中片段、来源与分数。
          </div>
        </div>
      </el-card>
    </aside>

    <!-- 文本粘贴对话框 -->
    <el-dialog v-model="textDlg.visible" title="粘贴文本入库" width="620px">
      <el-form label-width="60px">
        <el-form-item label="标题">
          <el-input v-model="textDlg.title" placeholder="如：产品FAQ整理" maxlength="256" show-word-limit />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="textDlg.content"
            type="textarea"
            :rows="12"
            placeholder="直接粘贴知识文本（doc_type=TEXT 跳过解析，走切分→向量化）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="textDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="textDlg.saving" @click="submitText">入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rag-page {
  display: flex;
  gap: 14px;
  padding: 16px;
  height: 100%;
  overflow: hidden;
}
.doc-side {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.drop-upload :deep(.el-upload-dragger) {
  padding: 18px;
}
.upload-tip {
  font-size: 12px;
  color: #a8abb2;
  margin-top: 4px;
}
.doc-card {
  flex: 1;
  border-radius: 10px;
}
.doc-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  min-width: 0;
}
.doc-sub {
  font-size: 12px;
  color: #909399;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}
.search-side {
  width: 380px;
  min-width: 340px;
  display: flex;
  flex-direction: column;
}
.search-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 10px;
}
.search-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.muted {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}
.search-opts {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0;
  font-size: 13px;
  color: #606266;
}
.hit-list {
  flex: 1;
  overflow-y: auto;
  min-height: 200px;
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
  justify-content: space-between;
  margin-bottom: 6px;
}
.score {
  font-size: 12px;
  color: #67c23a;
  font-family: Consolas, monospace;
}
.hit-text {
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
  max-height: 120px;
  overflow: auto;
  white-space: pre-wrap;
}
.search-placeholder {
  color: #a8abb2;
  font-size: 13px;
  text-align: center;
  padding: 40px 10px;
}
</style>
