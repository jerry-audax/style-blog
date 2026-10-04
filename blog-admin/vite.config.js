import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
    plugins: [vue()],
    server: {
        port: 5174,
        proxy: {
            '/api': 'http://localhost:8080'
        }
    },
    build: {
        rollupOptions: {
            output: {
                manualChunks(id) {
                    if (!id.includes('node_modules')) return
                    // Keep Vue and Element Plus in the same chunk to avoid circular chunk init errors.
                    if (
                        id.includes('/vue/') ||
                        id.includes('vue-router') ||
                        id.includes('pinia') ||
                        id.includes('element-plus')
                    ) return 'app-ui'
                    // Tiptap editor (heavy: ~300KB+)
                    if (id.includes('@tiptap'))
                        return 'editor'
                    // Small utilities: axios + anything else
                    if (id.includes('axios'))
                        return 'vendor'
                }
            }
        }
    },
    test: {
        environment: 'jsdom'
    }
})
