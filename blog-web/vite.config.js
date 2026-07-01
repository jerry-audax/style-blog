import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/uploads': 'http://localhost:8080'
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          // Vue ecosystem core
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia'))
            return 'vue-core'
          // Markdown parser
          if (id.includes('markdown-it'))
            return 'markdown'
          // HTTP client + small utilities
          if (id.includes('axios') || id.includes('dompurify'))
            return 'vendor'
        }
      }
    }
  },
  test: {
    environment: 'jsdom'
  }
})
