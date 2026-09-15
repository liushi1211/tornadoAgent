<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 刷新页面后 Pinia 态丢失：有 token 但未拉过 /users/me 时补一次
if (userStore.isLoggedIn && !userStore.user) void userStore.fetchMe()

const activeMenu = computed(() => {
  const seg = route.path.split('/')[1] || 'chat'
  return '/' + seg
})

const menus = [
  { path: '/chat', title: '智能对话', icon: 'ChatDotRound' },
  { path: '/skills', title: '技能管理', icon: 'MagicStick' },
  { path: '/mcp', title: 'MCP 服务', icon: 'Connection' },
  { path: '/rag', title: '知识库 RAG', icon: 'Document' },
  { path: '/memories', title: '长期记忆', icon: 'Coin' }
]

async function onCommand(cmd: string) {
  if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录？', '提示', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return
    }
    await userStore.logout()
    router.push('/login')
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="layout-aside">
      <div class="brand">
        <el-icon :size="22"><Cpu /></el-icon>
        <span class="brand-text">SAA 智能体</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        class="layout-menu"
        background-color="#1f2d3d"
        text-color="#c0ccda"
        active-text-color="#ffffff"
      >
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-title">{{ (route.meta.title as string) || '智能工作台' }}</div>
        <el-dropdown trigger="click" @command="onCommand">
          <span class="user-chip">
            <el-avatar :size="28">{{ userStore.displayName.charAt(0) }}</el-avatar>
            <span class="user-name">{{ userStore.displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">
                <el-icon><SwitchButton /></el-icon> 退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="layout-main">
        <router-view v-slot="{ Component }">
          <keep-alive :include="[]">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}
.layout-aside {
  background: var(--saa-sidebar-bg);
  display: flex;
  flex-direction: column;
}
.brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  color: #fff;
  font-weight: 600;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.brand-text {
  font-size: 16px;
  letter-spacing: 0.5px;
}
.layout-menu {
  border-right: none;
  flex: 1;
}
.layout-header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
.user-name {
  font-size: 14px;
  color: #303133;
}
.layout-main {
  padding: 0;
  background: var(--saa-bg);
  height: calc(100vh - 56px);
}
</style>
