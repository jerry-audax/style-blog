import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
    base: '/',
    plugins: [
        vue(),
        {
            name: 'blog-entry-redirect',
            configureServer(server) {
                server.middlewares.use((req, res, next) => {
                    const url = new URL(req.url || '/', 'http://localhost')
                    if (url.pathname.startsWith('/uploads/')) {
                        res.writeHead(404)
                        res.end('File storage disabled')
                        return
                    }
                    if (url.pathname !== '/') return next()
                    res.writeHead(302, {Location: '/blog/' + url.search})
                    res.end()
                })
            },
        },
    ],
    server: {
        port: 5173,
        proxy: {
            '/blog': 'http://127.0.0.1:5176',
            '/api': 'http://localhost:8080',
        },
    },
    build: {
        outDir: '../.build/app',
        emptyOutDir: true,
        manifest: true,
        rollupOptions: {
            input: {
                app: 'index.html',
                'interaction-widget': 'src/interaction-widget.js',
                'site-background': 'src/site-background.js',
            },
            output: {
                manualChunks(id) {
                    if (!id.includes('node_modules')) return
                    // Vue ecosystem core
                    if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia'))
                        return 'vue-core'
                    // Markdown parser
                    if (id.includes('markdown-it')) return 'markdown'
                    // HTTP client + small utilities
                    if (id.includes('axios') || id.includes('dompurify')) return 'vendor'
                },
            },
        },
    },
    test: {
        environment: 'jsdom',
    },
})
