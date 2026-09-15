/**
 * 认证 token 存储。
 * 说明：登录 token 持久化到 localStorage 是全栈 Web 应用的真实浏览器存储需求，
 * 与沙箱 artifact 限制无关（本工程为可运行的浏览器前端）。仅此处允许直接读写 localStorage。
 */

const ACCESS_KEY = 'saa_access_token'
const REFRESH_KEY = 'saa_refresh_token'
const USER_KEY = 'saa_current_user'

export function getAccessToken(): string | null {
  try {
    return localStorage.getItem(ACCESS_KEY)
  } catch {
    return null
  }
}

export function getRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_KEY)
  } catch {
    return null
  }
}

export function setTokens(access: string, refresh: string): void {
  try {
    if (access) localStorage.setItem(ACCESS_KEY, access)
    if (refresh) localStorage.setItem(REFRESH_KEY, refresh)
  } catch {
    /* ignore quota / privacy mode */
  }
}

export function clearTokens(): void {
  try {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  } catch {
    /* ignore */
  }
}

export function hasAccessToken(): boolean {
  return !!getAccessToken()
}

/**
 * 归一化 TokenPair：后端字段命名未知时，从多个候选键里挑出 access / refresh。
 */
export function normalizeTokenPair(raw: any): { access: string; refresh: string } | null {
  if (!raw || typeof raw !== 'object') return null
  const access =
    raw.accessToken ?? raw.access ?? raw.token ?? raw.access_token ?? raw.authToken ?? ''
  const refresh = raw.refreshToken ?? raw.refresh ?? raw.refresh_token ?? raw.renewToken ?? ''
  if (!access) return null
  return { access: String(access), refresh: String(refresh || '') }
}
