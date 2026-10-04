'use strict'
const http = require('node:http')
const https = require('node:https')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '../dist')
const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript',
    '.css': 'text/css',
    '.json': 'application/json',
    '.xml': 'application/xml',
    '.webp': 'image/webp',
    '.jpg': 'image/jpeg',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf',
}
http
    .createServer((req, res) => {
        const url = new URL(req.url, 'http://127.0.0.1')
        if (url.pathname === '/') {
            res.writeHead(302, {Location: '/blog/' + url.search})
            res.end()
            return
        }
        if (url.pathname.startsWith('/uploads/')) {
            res.writeHead(404)
            res.end('File storage disabled')
            return
        }
        if (url.pathname.startsWith('/api/')) {
            const backend = new URL(process.env.BLOG_API_URL || 'http://127.0.0.1:8080')
            const client = backend.protocol === 'https:' ? https : http
            const proxy = client.request(
                {
                    hostname: backend.hostname,
                    port: backend.port || undefined,
                    path: req.url,
                    method: req.method,
                    headers: {...req.headers, host: backend.host},
                },
                (upstream) => {
                    res.writeHead(upstream.statusCode, upstream.headers)
                    upstream.pipe(res)
                },
            )
            proxy.on('error', () => {
                res.writeHead(502)
                res.end('Backend unavailable')
            })
            req.pipe(proxy)
            return
        }
        if (url.pathname === '/blog') {
            res.writeHead(301, {Location: '/blog/'})
            res.end()
            return
        }
        let filename
        try {
            filename = path.resolve(root, '.' + decodeURIComponent(url.pathname))
        } catch {
            res.writeHead(400)
            res.end()
            return
        }
        if (filename !== root && !filename.startsWith(root + path.sep)) {
            res.writeHead(403)
            res.end()
            return
        }
        if (fs.existsSync(filename) && fs.statSync(filename).isDirectory())
            filename = path.join(filename, 'index.html')
        if (!fs.existsSync(filename)) {
            if (url.pathname.startsWith('/blog/') || path.extname(url.pathname)) {
                res.writeHead(404)
                res.end('Not found')
                return
            }
            filename = path.join(root, 'index.html')
        }
        res.writeHead(200, {
            'Content-Type': types[path.extname(filename)] || 'application/octet-stream',
        })
        fs.createReadStream(filename).pipe(res)
    })
    .listen(5173, '127.0.0.1', () => console.log('预览 http://127.0.0.1:5173（博客入口 → /blog/）'))
