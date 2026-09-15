import { createRouter, createWebHistory } from 'vue-router'
import { hasAccessToken } from '@/utils/auth'

/** 路由-视图映射（详细设计 §10.5） */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      redirect: '/chat',
      children: [
        {
          path: 'chat/:sessionId?',
          name: 'chat',
          component: () => import('@/views/Chat.vue'),
          meta: { title: '智能对话' }
        },
        {
          path: 'skills',
          name: 'skills',
          component: () => import('@/views/Skills.vue'),
          meta: { title: '技能管理' }
        },
        {
          path: 'mcp',
          name: 'mcp',
          component: () => import('@/views/Mcp.vue'),
          meta: { title: 'MCP 服务' }
        },
        {
          path: 'rag',
          name: 'rag',
          component: () => import('@/views/Rag.vue'),
          meta: { title: '知识库 RAG' }
        },
        {
          path: 'memories',
          name: 'memories',
          component: () => import('@/views/Memories.vue'),
          meta: { title: '长期记忆' }
        }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
})

/** 全局前置守卫：无 token 一律去 /login（§10.5） */
router.beforeEach((to) => {
  if (to.meta.public) {
    if (to.path === '/login' && hasAccessToken()) return '/chat'
    return true
  }
  if (!hasAccessToken()) {
    return { path: '/login', query: to.fullPath !== '/chat' ? { redirect: to.fullPath } : {} }
  }
  return true
})

router.afterEach((to) => {
  const t = (to.meta.title as string) || '智能工作台'
  document.title = `${t} · SAA 智能体工作台`
})

export default router
