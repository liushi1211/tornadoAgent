<script setup lang="ts">
/**
 * 长期记忆管理（§7 行 20、§4.7 / §6）
 * - 分类 Tab：preference（偏好）/ fact（事实）/ summary（摘要）
 * - 列表：GET /api/memories/long-term?category
 * - 批量删除：DELETE /api/memories/long-term（ids）
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { memoryApi } from '@/api'
import type { MemoryCategory, MemoryVO } from '@/types'

const loading = ref(false)
const rows = ref<MemoryVO[]>([])
const selection = ref<MemoryVO[]>([])

const categories: { key: MemoryCategory; label: string; icon: string }[] = [
  { key: 'preference', label: '用户偏好', icon: 'Star' },
  { key: 'fact', label: '客观事实', icon: 'Checked' },
  { key: 'summary', label: '会话摘要', icon: 'Tickets' }
]

const active = ref<MemoryCategory>('preference')

async function reload() {
  loading.value = true
  try {
    rows.value = await memoryApi.list(active.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function switchTab(k: MemoryCategory) {
  active.value = k
  selection.value = []
  void reload()
}

function onSelectionChange(v: MemoryVO[]) {
  selection.value = v
}

async function removeBatch() {
  if (!selection.value.length) {
    ElMessage.warning('请先勾选要删除的记忆')
    return
  }
  const ids = selection.value.map((m) => m.id)
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${ids.length} 条记忆？删除后不会再注入到对话上下文（合规要求）。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await memoryApi.removeBatch(ids)
    ElMessage.success(`已删除 ${ids.length} 条记忆`)
    selection.value = []
    void reload()
  } catch {
    /* noop */
  }
}

async function removeOne(m: MemoryVO) {
  try {
    await memoryApi.removeBatch([m.id])
    ElMessage.success('已删除')
    void reload()
  } catch {
    /* noop */
  }
}

function catColor(c: MemoryCategory) {
  return c === 'preference' ? '#e6a23c' : c === 'fact' ? '#409eff' : '#67c23a'
}

onMounted(reload)
</script>

<template>
  <div class="page">
    <div class="head">
      <el-tabs v-model="active" class="tabs" @tab-change="switchTab(active)">
        <el-tab-pane v-for="c in categories" :key="c.key" :name="c.key">
          <template #label>
            <span class="tab-label">
              <el-icon :color="catColor(c.key)"><component :is="c.icon" /></el-icon>
              {{ c.label }}
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>
      <div class="head-actions">
        <span class="count">共 {{ rows.length }} 条</span>
        <el-button type="danger" plain :disabled="!selection.length" @click="removeBatch">
          <el-icon><Delete /></el-icon>&nbsp;批量删除{{ selection.length ? `（${selection.length}）` : '' }}
        </el-button>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="rows" stripe @selection-change="onSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column label="内容" min-width="360">
          <template #default="{ row }">
            <div class="mem-content">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类别" width="110">
          <template #default="{ row }">
            <el-tag size="small" :color="catColor(row.category)" effect="dark" style="color:#fff;border:none">
              {{ row.category }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="命中次数" width="100" prop="hitCount" />
        <el-table-column label="来源会话" width="110">
          <template #default="{ row }">
            <span v-if="row.sourceSessionId" class="mono">#{{ row.sourceSessionId }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">
            {{ String(row.updatedAt || '').replace('T', ' ').slice(0, 19) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeOne(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该分类暂无长期记忆（归档会话后由后台摘要任务沉淀）" />
        </template>
      </el-table>
    </el-card>

    <p class="foot-note">
      长期记忆由会话归档（或过期扫描）后异步抽取，按 preference &gt; fact &gt; summary 权重注入对话上下文；单条上限 512 字。
    </p>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
  height: 100%;
  overflow: auto;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  gap: 12px;
  flex-wrap: wrap;
}
.tabs {
  flex: 1;
  min-width: 240px;
}
.tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.count {
  font-size: 13px;
  color: #909399;
}
.mem-content {
  line-height: 1.6;
  white-space: pre-wrap;
}
.mono {
  font-family: Consolas, monospace;
}
.muted {
  color: #c0c4cc;
}
.foot-note {
  margin-top: 12px;
  font-size: 12px;
  color: #a8abb2;
}
</style>
