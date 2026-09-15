import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 手工搭建的 Vite + Vue3 工程配置（不使用 npm create 脚手架）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      // 开发态：/api 反代到后端 8080，前端与生产同构，不写死后端域名
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 流式：关闭代理聚合缓冲，保证增量实时下发
        ws: false,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const ct = proxyRes.headers['content-type'] || ''
            if (ct.includes('text/event-stream')) {
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        }
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1600
  }
})
