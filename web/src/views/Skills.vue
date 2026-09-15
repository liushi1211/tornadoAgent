<script setup lang="ts">
/**
 * 技能管理（§7 行 10-12、§5.1）
 * - 列表：分页 + 关键字搜索 + 启停开关（PATCH /api/skills/{id}/toggle?enabled=）
 * - 安装：上传 .skill/.zip（SKILL.md + 脚本资源包）或 .md 单文件，或手工 JSON 表单
 * - 详情：抽屉展示 SKILL.md 原文 + 资源文件清单（脚本可点击预览源码）
 * - 删除：确认后 DELETE /api/skills/{id}
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile, type UploadInstance } from 'element-plus'
import { skillApi } from '@/api'
import type { SkillVO } from '@/types'

interface SkillFileMeta {
  relPath: string
  fileType: string
  sizeBytes: number
}

const loading = ref(false)
const rows = ref<SkillVO[]>([])
const total = ref(0)
const query = reactive({ keyword: '', enabled: undefined as boolean | undefined, page: 1, size: 10 })

const drawer = reactive({
  visible: false,
  skill: null as SkillVO | null,
  files: [] as SkillFileMeta[],
  loadingFiles: false,
  previewPath: '',
  previewContent: '',
  loadingPreview: false
})
const manual = reactive({ visible: false, saving: false, name: '', description: '', contentMd: '' })
const uploading = ref(false)
const uploadRef = ref<UploadInstance>()

async function reload() {
  loading.value = true
  try {
    const res = await skillApi.list(query)
    rows.value = res.records
    total.value = res.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  void reload()
}

function resetKeyword() {
  query.keyword = ''
  query.enabled = undefined
  search()
}

/* -------- 启停开关（含 Agent 提议待确认：enabled=0 → toggle 即确认启用） -------- */
async function onToggle(s: SkillVO) {
  // v-model 已翻转显示态；仅调用 API，失败时回滚
  try {
    await skillApi.toggle(s.id, s.enabled)
    ElMessage.success(s.enabled ? `已启用技能「${s.name}」` : `已停用技能「${s.name}」`)
  } catch {
    /* 失败时还原开关（拦截器已 toast） */
    s.enabled = !s.enabled
  }
}

/* -------- 上传安装：accept .skill/.zip/.md，manual 触发 -------- */
async function onFileChange(file: UploadFile) {
  const raw = file.raw as File | undefined
  if (!raw) return
  const name = raw.name.toLowerCase()
  if (!name.endsWith('.skill') && !name.endsWith('.zip') && !name.endsWith('.md')) {
    ElMessage.warning('支持 .skill/.zip（SKILL.md + 脚本资源包）或 .md 单文件')
    uploadRef.value?.clearFiles()
    return
  }
  uploading.value = true
  try {
    const created = await skillApi.installFile(raw)
    ElMessage.success(`技能「${created?.name || raw.name}」安装成功`)
    uploadRef.value?.clearFiles()
    query.page = 1
    void reload()
  } catch {
    uploadRef.value?.clearFiles()
  } finally {
    uploading.value = false
  }
}

/* -------- 手工 JSON 表单安装 -------- */
async function submitManual() {
  if (!/^[a-z0-9-]{1,64}$/.test(manual.name)) {
    ElMessage.warning('name 需匹配 ^[a-z0-9-]{1,64}$（小写字母/数字/连字符）')
    return
  }
  if (!manual.contentMd.trim()) {
    ElMessage.warning('请填写 SKILL.md 正文')
    return
  }
  manual.saving = true
  try {
    await skillApi.installManual({
      name: manual.name,
      description: manual.description,
      contentMd: manual.contentMd,
      source: 'MANUAL'
    })
    ElMessage.success('手工录入成功')
    manual.visible = false
    manual.name = ''
    manual.description = ''
    manual.contentMd = ''
    query.page = 1
    void reload()
  } catch {
    /* noop */
  } finally {
    manual.saving = false
  }
}

async function openDetail(s: SkillVO) {
  drawer.skill = s
  drawer.visible = true
  drawer.files = []
  drawer.previewPath = ''
  drawer.previewContent = ''
  drawer.loadingFiles = true
  try {
    const detail = await skillApi.detail(s.id)
    drawer.files = detail?.files ?? []
  } catch {
    /* 拦截器已 toast */
  } finally {
    drawer.loadingFiles = false
  }
}

async function openFile(f: SkillFileMeta) {
  if (!drawer.skill) return
  drawer.previewPath = f.relPath
  drawer.previewContent = ''
  drawer.loadingPreview = true
  try {
    const res = await skillApi.file(drawer.skill.id, f.relPath)
    drawer.previewContent = res?.content ?? ''
  } catch {
    drawer.previewContent = '(加载失败)'
  } finally {
    drawer.loadingPreview = false
  }
}

async function remove(s: SkillVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除技能「${s.name}」？Agent 运行时将不再注入该技能。`,
      '删除技能',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await skillApi.remove(s.id)
    ElMessage.success('已删除')
    void reload()
  } catch {
    /* noop */
  }
}

function sourceText(s: SkillVO) {
  switch (s.source) {
    case 'AGENT':
      return 'Agent 提议'
    case 'MARKET':
      return '市场'
    case 'UPLOAD':
      return '上传'
    default:
      return '手工'
  }
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索技能名称 / 描述"
          clearable
          class="kw"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="query.enabled" placeholder="全部状态" clearable class="status-sel" @change="search">
          <el-option label="已启用" :value="true" />
          <el-option label="已停用" :value="false" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetKeyword">重置</el-button>
      </div>
      <div class="toolbar-right">
        <el-upload
          ref="uploadRef"
          :auto-upload="false"
          :show-file-list="false"
          accept=".skill,.zip,.md"
          :on-change="onFileChange"
        >
          <el-button :loading="uploading">
            <el-icon><Upload /></el-icon>&nbsp;上传安装（.skill/.zip/.md）
          </el-button>
        </el-upload>
        <el-button type="primary" plain @click="manual.visible = true">
          <el-icon><EditPen /></el-icon>&nbsp;手工录入
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="name" label="名称" min-width="160" fixed>
          <template #default="{ row }">
            <span class="mono">{{ row.name }}</span>
            <el-tooltip v-if="row.source === 'AGENT' && !row.enabled" content="Agent 提议沉淀，开关切换即确认启用" placement="top">
              <el-tag size="small" type="warning" class="ml6">待确认</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <el-table-column prop="source" label="来源" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ sourceText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="onToggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            {{ String(row.updatedAt || '').replace('T', ' ').slice(0, 19) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无技能，可上传 SKILL.md 或手工录入" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="reload"
          @size-change="search"
        />
      </div>
    </el-card>

    <!-- 详情抽屉：SKILL.md 原文 -->
    <el-drawer v-model="drawer.visible" :title="drawer.skill?.name || '技能详情'" size="46%">
      <template v-if="drawer.skill">
        <el-descriptions :column="2" border class="mb16">
          <el-descriptions-item label="描述" :span="2">{{ drawer.skill.description || '—' }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ sourceText(drawer.skill) }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ drawer.skill.version }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ drawer.skill.enabled ? '启用' : '停用' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ String(drawer.skill.updatedAt || '').replace('T', ' ').slice(0, 19) }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="md-title">SKILL.md 原文</div>
        <pre class="md-raw">{{ drawer.skill.contentMd }}</pre>

        <div class="md-title" style="margin-top: 16px">
          资源文件
          <span v-if="!drawer.loadingFiles && !drawer.files.length" class="hint">（无，单文件 .md 安装不含资源）</span>
        </div>
        <div v-if="drawer.loadingFiles" class="hint">加载中…</div>
        <div v-else-if="drawer.files.length" class="file-list">
          <div
            v-for="f in drawer.files"
            :key="f.relPath"
            class="file-item"
            :class="{ active: drawer.previewPath === f.relPath }"
            @click="openFile(f)"
          >
            <el-tag size="small" :type="f.fileType === 'SCRIPT' ? 'warning' : f.fileType === 'DOC' ? 'success' : 'info'" effect="plain">
              {{ f.fileType }}
            </el-tag>
            <span class="mono file-path">{{ f.relPath }}</span>
            <span class="hint">{{ f.sizeBytes }} B</span>
          </div>
          <el-input
            v-if="drawer.previewPath"
            v-model="drawer.previewContent"
            type="textarea"
            :rows="12"
            readonly
            class="mt8"
          />
        </div>
      </template>
    </el-drawer>

    <!-- 手工录入对话框 -->
    <el-dialog v-model="manual.visible" title="手工录入技能" width="640px">
      <el-form label-width="90px">
        <el-form-item label="name" required>
          <el-input v-model="manual.name" placeholder="小写字母/数字/连字符，如 weather-crawler" />
        </el-form-item>
        <el-form-item label="description">
          <el-input v-model="manual.description" placeholder="一句话描述用途（注入 Prompt 的摘要）" maxlength="512" show-word-limit />
        </el-form-item>
        <el-form-item label="SKILL.md" required>
          <el-input
            v-model="manual.contentMd"
            type="textarea"
            :rows="12"
            placeholder="粘贴完整 SKILL.md（含 frontmatter），上限 64KB"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manual.visible = false">取消</el-button>
        <el-button type="primary" :loading="manual.saving" @click="submitManual">安装</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
  height: 100%;
  overflow: auto;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.toolbar-left {
  display: flex;
  gap: 8px;
  align-items: center;
}
.toolbar-right {
  display: flex;
  gap: 8px;
  align-items: center;
}
.kw {
  width: 240px;
}
.status-sel {
  width: 120px;
}
.table-card {
  border-radius: 10px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-weight: 600;
}
.ml6 {
  margin-left: 6px;
}
.mb16 {
  margin-bottom: 16px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.md-title {
  font-weight: 600;
  margin-bottom: 8px;
}
.hint {
  color: #909399;
  font-size: 12px;
  font-weight: 400;
}
.file-list {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 6px 10px;
}
.file-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  cursor: pointer;
  border-radius: 6px;
}
.file-item:hover,
.file-item.active {
  background: #f0f6ff;
}
.file-path {
  flex: 1;
  font-size: 13px;
}
.mt8 {
  margin-top: 8px;
}
.md-raw {
  background: #f8f8f8;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 14px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 60vh;
  overflow: auto;
}
</style>
