import { defineStore } from 'pinia'
import { authApi, userApi } from '@/api'
import {
  getAccessToken,
  getRefreshToken,
  setTokens,
  clearTokens,
  normalizeTokenPair
} from '@/utils/auth'
import type { LoginPayload, RegisterPayload, UserVO } from '@/types'

/** 登录态：双 token（localStorage 持久化见 utils/auth.ts）+ 当前用户信息 */
export const useUserStore = defineStore('user', {
  state: () => ({
    access: getAccessToken(),
    refresh: getRefreshToken(),
    user: null as UserVO | null
  }),
  getters: {
    isLoggedIn: (s) => !!s.access,
    displayName: (s) => s.user?.nickname || s.user?.username || '用户'
  },
  actions: {
    applyTokens(raw: unknown) {
      const pair = normalizeTokenPair(raw)
      if (!pair) throw new Error('登录响应异常：未返回有效 token')
      this.access = pair.access
      this.refresh = pair.refresh || this.refresh
      setTokens(pair.access, pair.refresh)
    },
    async login(payload: LoginPayload) {
      const raw = await authApi.login(payload)
      this.applyTokens(raw)
      void this.fetchMe()
    },
    async register(payload: RegisterPayload) {
      const raw = await authApi.register(payload)
      this.applyTokens(raw)
      void this.fetchMe()
    },
    async fetchMe() {
      try {
        this.user = await userApi.me()
      } catch {
        /* 静默：/users/me 失败不阻塞主流程 */
      }
    },
    async logout() {
      await authApi.logout(this.refresh || undefined)
      this.reset()
    },
    reset() {
      this.access = null
      this.refresh = null
      this.user = null
      clearTokens()
    }
  }
})
