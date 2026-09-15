<script setup lang="ts">
/**
 * MCP 服务管理（§7 行 13-14、§5.2）
 * - 表格 CRUD：GET/POST/PATCH/DELETE /api/mcp
 * - 表单：transport(SSE|STREAMABLE_HTTP|STDIO) + url/command + headers 动态 key-value 行
 * - 探活：POST /api/mcp/{id}/test → 健康红绿点 + 工具名 tags
 * - 启停：PATCH /api/mcp/{id}/toggle（仅 enabled && HEALTHY 参与运行时装配）
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mcpApi } from '@/api'
import type { McpHeader, McpTransport, McpVO } from '@/types'

const loading = ref(false)
const rows = ref<McpVO[]>([])

const form = reactive({
  visible: false,
  saving: false,
  id: null as number | null,
  name: '',
  transport: 'SSE' as McpTransport,
  url: '',
  command: '',
  argsText: '',
  headers: [] as McpHeader[]
})

const probing = reactive<Record<number, boolean>>({})

function isWebTransport(t: McpTransport) {
  return t === 'SSE' || t === 'STREAMABLE_HTTP'
}

function transportText(t: McpTransport) {
  return t === 'STREAMABLE_HTTP' ? 'Streamable HTTP' : t === 'STDIO' ? 'STDIO' : 'SSE'
}

async function reload() {
  loading.value = true
  try {
    rows.value = await mcpApi.list()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function headersToRows(h?: Record<string, string> | null): McpHeader[] {
  if (!h) return []
  return Object.entries(h).map(([key, value]) => ({ key, value: String(value) }))
}

function openCreate() {
  Object.assign(form, {
    visible: true,
    id: null,
    name: '',
    transport: 'SSE' as McpTransport,
    url: '',
    command: '',
    argsText: '',
    headers: []
  })
}

function openEdit(r: McpVO) {
  Object.assign(form, {
    visible: true,
    id: r.id,
    name: r.name,
    transport: r.transport,
    url: r.url || '',
    command: r.command || '',
    argsText: (r.args || []).join(' '),
    headers: headersToRows(r.headers)
  })
}

function addHeaderRow() {
  form.headers.push({ key: '', value: '' })
}
function removeHeaderRow(i: number) {
  form.headers.splice(i, 1)
}

function buildHeaders(): Record<string, string> | undefined {
  const obj: Record<string, string> = {}
  for (const h of form.headers) {
    if (h.key.trim()) obj[h.key.trim()] = h.value
  }
  return Object.keys(obj).length ? obj : undefined
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写服务名称')
    return
  }
  if (isWebTransport(form.transport) && !form.url.trim()) {
    ElMessage.warning('SSE / Streamable HTTP 需填写 URL')
    return
  }
  if (form.transport === 'STDIO' && !form.command.trim()) {
    ElMessage.warning('STDIO 需填写启动命令（默认仅管理员白名单放开）')
    return
  }
  const payload: any = {
    name: form.name.trim(),
    transport: form.transport,
    headers: buildHeaders()
  }
  if (isWebTransport(form.transport)) payload.url = form.url.trim()
  else {
    payload.command = form.command.trim()
    const args = form.argsText.trim() ? form.argsText.trim().split(/\s+/) : []
    if (args.length) payload.args = args
  }
  form.saving = true
  try {
    if (form.id == null) {
      await mcpApi.create(payload)
      ElMessage.success('已创建，建议先探活再启用')
    } else {
      await mcpApi.update(form.id, payload)
      ElMessage.success('已保存（配置变更会使旧连接失效）')
    }
    form.visible = false
    void reload()
  } catch {
    /* noop */
  } finally {
    form.saving = false
  }
}

/** 探活：同步返回 {status, tools[]} */
async function probe(r: McpVO) {
  probing[r.id] = true
  try {
    const res = await mcpApi.test(r.id)
    r.healthStatus = res?.status || 'DOWN'
    r.toolNames = res?.tools || []
    ElMessage({
      type: r.healthStatus === 'HEALTHY' ? 'success' : 'error',
      message:
        r.healthStatus === 'HEALTHY'
          ? `连接正常，发现 ${r.toolNames.length} 个工具`
          : '探活失败：握手或 tools/list 未通过'
    })
  } catch {
    r.healthStatus = 'DOWN'
  } finally {
    probing[r.id] = false
    void reload()
  }
}

async function onToggle(r: McpVO) {
  try {
    await mcpApi.toggle(r.id)
    ElMessage.success(r.enabled ? `已启用「${r.name}」` : `已停用「${r.name}」`)
  } catch {
    r.enabled = !r.enabled
  }
}

async function remove(r: McpVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除 MCP 服务「${r.name}」？其运行时连接将被关闭并移出工具集。`,
      '删除服务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await mcpApi.remove(r.id)
    ElMessage.success('已删除')
    void reload()
  } catch {
    /* noop */
  }
}

function healthDot(s: McpVO['healthStatus']) {
  if (s === 'HEALTHY') return '#67c23a'
  if (s === 'DOWN') return '#f56c6c'
  return '#909399'
}
function healthText(s: McpVO['healthStatus']) {
  return s === 'HEALTHY' ? '健康' : s === 'DOWN' ? '不可用' : '未探活'
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <div class="tip">
        仅「启用 + 健康」的 MCP 工具会装配进智能体；headers 中的鉴权密钥由后端 AES-GCM 加密存储。
      </div>
      <div class="actions">
        <el-button @click="reload"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>&nbsp;新增 MCP 服务
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="name" label="名称" min-width="140" fixed>
          <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
        </el-table-column>
        <el-table-column prop="transport" label="传输" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ transportText(row.transport) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="端点 / 命令" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.transport === 'STDIO' ? (row.command || '—') : (row.url || '—') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="健康" width="100">
          <template #default="{ row }">
            <el-tooltip :content="row.lastProbeAt ? '最近探活: ' + String(row.lastProbeAt).replace('T',' ').slice(0,19) : '尚未探活'" placement="top">
              <span class="health">
                <span class="dot" :style="{ background: healthDot(row.healthStatus) }" />
                {{ healthText(row.healthStatus) }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="工具清单" min-width="260">
          <template #default="{ row }">
            <template v-if="row.toolNames && row.toolNames.length">
              <el-tag v-for="t in row.toolNames.slice(0, 6)" :key="t" size="small" class="tool-tag" effect="info">
                {{ t }}
              </el-tag>
              <el-tag v-if="row.toolNames.length > 6" size="small" type="info">+{{ row.toolNames.length - 6 }}</el-tag>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="onToggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" :loading="probing[row.id]" @click="probe(row)">探活</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无 MCP 服务，点击「新增」接入外部工具" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="form.visible" :title="form.id == null ? '新增 MCP 服务' : '编辑 MCP 服务'" width="600px">
      <el-form label-width="110px">
        <el-form-item label="服务名称" required>
          <el-input v-model="form.name" placeholder="如 amap-maps" maxlength="64" />
        </el-form-item>
        <el-form-item label="传输方式" required>
          <el-select v-model="form.transport" class="w-full">
            <el-option label="SSE" value="SSE" />
            <el-option label="Streamable HTTP" value="STREAMABLE_HTTP" />
            <el-option label="STDIO（需管理员白名单）" value="STDIO" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isWebTransport(form.transport)" label="URL" required>
          <el-input v-model="form.url" placeholder="https://mcp.example.com/sse" />
          <div class="hint">生产环境禁指内网段（SSRF 检查），白名单例外。</div>
        </el-form-item>
        <template v-else>
          <el-form-item label="命令" required>
            <el-input v-model="form.command" placeholder="npx" />
          </el-form-item>
          <el-form-item label="参数">
            <el-input v-model="form.argsText" placeholder="以空格分隔，如 -y @amap/amap-maps-mcp-server" />
          </el-form-item>
        </template>
        <el-form-item label="Headers">
          <div class="kv-wrap">
            <div v-for="(h, i) in form.headers" :key="i" class="kv-row">
              <el-input v-model="h.key" placeholder="如 Authorization" class="kv-k" />
              <el-input v-model="h.value" placeholder="如 Bearer sk-***" class="kv-v" show-password />
              <el-button circle size="small" @click="removeHeaderRow(i)">
                <el-icon><Minus /></el-icon>
              </el-button>
            </div>
            <el-button size="small" plain @click="addHeaderRow">
              <el-icon><Plus /></el-icon>&nbsp;添加 Header
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" :loading="form.saving" @click="save">保存</el-button>
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
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}
.tip {
  font-size: 13px;
  color: #909399;
}
.actions {
  display: flex;
  gap: 8px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.health {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.tool-tag {
  margin: 2px 4px 2px 0;
}
.muted {
  color: #c0c4cc;
}
.w-full {
  width: 100%;
}
.hint {
  font-size: 12px;
  color: #a8abb2;
  line-height: 1.5;
  margin-top: 4px;
}
.kv-wrap {
  width: 100%;
}
.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.kv-k {
  width: 180px;
}
.kv-v {
  flex: 1;
}
</style>
