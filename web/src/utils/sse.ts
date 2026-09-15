/**
 * SSE 客户端（详细设计 §4.1 / §10.5）
 * - EventSource 不支持 POST 与自定义 Header，故用 fetch(POST) + ReadableStream 手工解析；
 * - response.body.getReader() + TextDecoder 增量缓冲，按空行(\n\n)分帧，解析 event: / data: 前缀；
 * - 支持 AbortController（停止 = abort + POST /api/chat/stop/{streamId} 由调用方完成）；
 * - 收到 interrupt 帧后，本轮流即视为结束（等待 resume 续流）；
 * - 401：静默 refresh 一次后重放，仍失败则以 __unauthorized 抛出由上层跳登录。
 */
import { getAccessToken } from './auth'
import { doRefresh } from './request'
import type { SseEventType } from '../types'

export interface SseHandlers {
  /** 每个 SSE 帧回调：type 为事件名，data 为 JSON 解析后的对象（解析失败时为原始字符串） */
  onEvent: (type: SseEventType, data: any) => void
  onOpen?: (response: Response) => void
  /** 流结束（服务端关闭 / interrupt / abort 后统一触发一次） */
  onDone?: (info: { interrupted: boolean; aborted: boolean }) => void
  onError?: (err: Error & { __unauthorized?: boolean }) => void
}

export interface PostSseOptions {
  /** 完整 URL（以 /api 开头，开发态走 vite proxy，生产态走 Nginx 同源） */
  url: string
  body: unknown
  signal: AbortSignal
  handlers: SseHandlers
}

/** crypto.randomUUID，含低版本兜底 */
export function uuid(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
  } catch {
    /* fallthrough */
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/** 把单帧原文（不含结尾空行）解析为 {event, data} */
function parseFrame(raw: string): { event: string; data: any } | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue // 心跳注释帧等
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      let v = line.slice(5)
      if (v.startsWith(' ')) v = v.slice(1)
      dataLines.push(v)
    } else if (line.startsWith('id:') || line.startsWith('retry:')) {
      // 忽略
    }
  }
  if (dataLines.length === 0 && event === 'message') return null
  const dataStr = dataLines.join('\n')
  let data: any = dataStr
  if (dataStr) {
    try {
      data = JSON.parse(dataStr)
    } catch {
      /* 保留原始字符串 */
    }
  }
  return { event, data }
}

async function doFetch(url: string, init: RequestInit): Promise<Response> {
  const token = getAccessToken()
  return fetch(url, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream, application/json;q=0.9',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers as Record<string, string> | undefined)
    }
  })
}

/**
 * 发起一次 SSE 请求并消费到结束。resolve 表示流已终结；业务错误经 handlers.onError 抛出后仍 resolve。
 */
export async function postSse(opts: PostSseOptions): Promise<void> {
  const { url, body, signal, handlers } = opts
  const { onEvent, onOpen, onDone, onError } = handlers

  let interrupted = false
  let aborted = signal.aborted

  const finish = () => {
    onDone?.({ interrupted, aborted })
  }

  try {
    const init: RequestInit = {
      method: 'POST',
      headers: {},
      body: JSON.stringify(body),
      signal
    }
    let resp = await doFetch(url, init)

    // 401 → refresh 一次重放
    if (resp.status === 401) {
      const newToken = await doRefresh()
      if (newToken) {
        resp = await doFetch(url, init)
      } else {
        const err: Error & { __unauthorized?: boolean } = new Error('登录状态已失效，请重新登录')
        err.__unauthorized = true
        onError?.(err)
        finish()
        return
      }
    }

    if (!resp.ok) {
      // 非 2xx：尽量解析 Result{code,message}
      let msg = `请求失败（HTTP ${resp.status}）`
      try {
        const j = await resp.json()
        if (j && typeof j.message === 'string' && j.message) msg = `${j.code ? `[${j.code}] ` : ''}${j.message}`
      } catch {
        /* keep default */
      }
      const err: Error & { __unauthorized?: boolean } = new Error(msg)
      err.__unauthorized = resp.status === 401
      onError?.(err)
      finish()
      return
    }

    if (!resp.body) {
      onError?.(new Error('当前浏览器不支持流式响应（ReadableStream 不可用）'))
      finish()
      return
    }

    onOpen?.(resp)

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    /** 逐帧消费；返回 false 表示需要终止读取（interrupt/abort） */
    const consumeFrame = (raw: string): boolean => {
      const parsed = parseFrame(raw)
      if (!parsed) return true
      onEvent(parsed.event, parsed.data)
      if (parsed.event === 'interrupt') {
        // HITL 挂起：本轮流视为结束（服务端也会随后关流，这里主动收尾更稳）
        interrupted = true
        return false
      }
      return true
    }

    // eslint-disable-next-line no-constant-condition
    while (true) {
      if (signal.aborted) {
        aborted = true
        break
      }
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 帧分隔符：空行。统一 CRLF → LF 后按 \n\n 切分
      buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (!consumeFrame(frame)) {
          // interrupt：停止读取并释放连接
          try {
            await reader.cancel()
          } catch {
            /* ignore */
          }
          finish()
          return
        }
      }
    }

    // 冲刷缓冲区里的最后一帧（服务端未在末尾补空行时兜底）
    const rest = buffer.trim()
    if (rest && !interrupted) consumeFrame(rest)
    finish()
  } catch (e: any) {
    if (e?.name === 'AbortError') {
      aborted = true
      finish()
      return
    }
    onError?.(e instanceof Error ? e : new Error(String(e)))
    finish()
  }
}
