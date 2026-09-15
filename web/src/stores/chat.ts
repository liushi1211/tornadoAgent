import { defineStore } from 'pinia'
import { chatApi } from '@/api'
import type { ModelVO } from '@/types'

/**
 * 聊天全局设置 store：Nacos 模型清单缓存 + 本轮对话开关（RAG / Skills / 深度思考）。
 * AbortController 与消息流属会话级瞬时态，由 ChatView 持有（切换会话即清理）。
 */
export const useChatStore = defineStore('chat', {
  state: () => ({
    models: [] as ModelVO[],
    modelsLoaded: false,
    /** 记住上次选择的模型（跨会话默认值；会话自身 modelId 优先） */
    lastModelId: '',
    useRag: false,
    useSkills: true
  }),
  getters: {
    modelById: (s) => (id: string) => s.models.find((m) => m.id === id) || null,
    /** 支持深度思考的模型存在时才展示 enableThinking 开关 */
    hasThinkingModel: (s) => s.models.some((m) => m.supportsThinking)
  },
  actions: {
    async loadModels(force = false) {
      if (this.modelsLoaded && !force) return this.models
      try {
        const raw: any = await chatApi.models()
        this.models = Array.isArray(raw) ? raw : (raw?.models ?? [])
        this.modelsLoaded = true
        if (!this.lastModelId) {
          this.lastModelId = raw?.default || this.models[0]?.id || ''
        }
      } catch {
        /* 错误 toast 已由 axios 拦截器统一处理 */
      }
      return this.models
    },
    setLastModel(id: string) {
      this.lastModelId = id
    }
  }
})
