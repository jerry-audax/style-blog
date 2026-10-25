import {defineConfig, loadEnv} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath} from 'node:url'

export default defineConfig(({mode}) => {
    const apiTarget = loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), 'BLOG_API_URL').BLOG_API_URL || 'http://localhost:8080'
    return {
        plugins: [vue()],
        server: {
            port: 5174,
            proxy: {
                '/api/admin/media/uploads/': {target: apiTarget, timeout: 1800000, proxyTimeout: 1800000},
                '/api': apiTarget
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
    }
})
