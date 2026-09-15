/**
 * axios 实例 + 统一 Result 解包 + Bearer 注入 + 401→refresh 重放一次→失败跳登录。
 * 契约依据《详细设计》§7（统一 Result 包装、全量 Bearer）与 §9（401 静默刷新）。
 */
import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import {
  getAccessToken,
  getRefreshToken,
  setTokens,
  clearTokens,
  normalizeTokenPair
} from './auth'

/** API 基址：开发态由 vite proxy 转发，生产态由 Nginx 同源反代 */
export const API_BASE = '/api'

/** 裸实例（无拦截器）：用于 refresh，避免与主实例拦截器递归 */
const bare = axios.create({ baseURL: API_BASE, timeout: 30000 })

const service: AxiosInstance = axios.create({ baseURL: API_BASE, timeout: 30000 })

/* -------------------- 请求拦截：注入 Bearer -------------------- */
service.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers = config.headers || {}
    ;(config.headers as any).Authorization = `Bearer ${token}`
  }
  return config
})

function isAuthUrl(url?: string): boolean {
  return !!url && /\/auth\/(login|register|refresh|logout)/.test(url)
}

function extractMessage(error: AxiosError): string {
  const anyErr = error as any
  const body: any = error.response?.data
  if (body && typeof body === 'object') {
    if (typeof body.message === 'string' && body.message) return body.message
    if (typeof body.msg === 'string' && body.msg) return body.msg
  }
  if (anyErr?.message === 'Network Error') return '网络异常，无法连接服务'
  return anyErr?.message || '请求失败'
}

/* -------------------- 401 单飞刷新 -------------------- */
let refreshPromise: Promise<string | null> | null = null

/** 供 SSE / 主实例复用：刷新 access token，成功返回新 token，失败返回 null */
export function doRefresh(): Promise<string | null> {
  if (refreshPromise) return refreshPromise
  const rt = getRefreshToken()
  if (!rt) return Promise.resolve(null)
  refreshPromise = (async () => {
    try {
      const resp = await bare.post('/auth/refresh', { refreshToken: rt })
      const body: any = resp.data
      const pair = normalizeTokenPair(body && 'data' in body ? body.data : body)
      if (pair) {
        setTokens(pair.access, pair.refresh)
        return pair.access
      }
      return null
    } catch {
      return null
    } finally {
      // 微任务后释放，允许下一轮重新刷新
      setTimeout(() => {
        refreshPromise = null
      }, 0)
    }
  })()
  return refreshPromise
}

function toLogin(): void {
  clearTokens()
  if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

/* -------------------- 响应拦截：解包 + 401 处理 -------------------- */
service.interceptors.response.use(
  (response) => {
    const body: any = response.data
    // 统一 Result{code,message,data}：HTTP 2xx 下按 code==="0" 解包
    if (body && typeof body === 'object' && 'code' in body && 'data' in body) {
      const code = body.code
      if (code === '0' || code === 0) {
        return body.data
      }
      const msg = body.message || '操作失败'
      ElMessage.error(msg)
      const bizErr: any = new Error(msg)
      bizErr.code = code
      bizErr.biz = true
      return Promise.reject(bizErr)
    }
    // 非 Result 形态：原样返回
    return body
  },
  async (error: AxiosError) => {
    const original: any = error.config
    const status = error.response?.status

    // 401：非认证接口，重放一次
    if (status === 401 && original && !original._retried && !isAuthUrl(original.url)) {
      original._retried = true
      const newToken = await doRefresh()
      if (newToken) {
        original.headers = original.headers || {}
        original.headers.Authorization = `Bearer ${newToken}`
        return service(original)
      }
      toLogin()
      return Promise.reject(error)
    }

    if (status === 401 && !isAuthUrl(original?.url)) {
      toLogin()
    } else if (error.response) {
      // HTTP 非 2xx（含 401 认证接口本身）视为错误，透出后端 message
      ElMessage.error(extractMessage(error))
    } else if (!axios.isCancel(error)) {
      ElMessage.error(extractMessage(error))
    }
    return Promise.reject(error)
  }
)

/* -------------------- 类型友好的 http 包装 -------------------- */
export const http = {
  get: <T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> =>
    service.get(url, { params, ...config }) as unknown as Promise<T>,
  post: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> =>
    service.post(url, data, config) as unknown as Promise<T>,
  patch: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> =>
    service.patch(url, data, config) as unknown as Promise<T>,
  put: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> =>
    service.put(url, data, config) as unknown as Promise<T>,
  del: <T = any>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    service.delete(url, config) as unknown as Promise<T>
}

export default service
