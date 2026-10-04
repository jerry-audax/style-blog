'use strict'
const http = require('node:http')
const fs = require('node:fs/promises')
const path = require('node:path')
const {createReadStream} = require('node:fs')
const {createHash, timingSafeEqual, randomBytes} = require('node:crypto')
const {PublicationCoordinator} = require('../application/PublicationCoordinator.cjs')
const {FilePublicationRepository} = require('../infrastructure/FilePublicationRepository.cjs')
const {PublicContentSource} = require('../infrastructure/PublicContentSource.cjs')
const {HexoPublicationRenderer} = require('../infrastructure/HexoPublicationRenderer.cjs')
const types = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.xml': 'application/xml', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp', '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.txt': 'text/plain; charset=utf-8'}

function createPublicationServer({repository, coordinator, directory, token}) {
    if (typeof token !== 'string' || token.length < 32) throw new Error('A publication credential is required')
    const hash = value => createHash('sha256').update(value).digest()
    const credential = hash(token)
    const json = (res, code, value) => {res.writeHead(code, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'}); res.end(JSON.stringify(value))}
    return http.createServer(async (req, res) => {
        try {
            const url = new URL(req.url, 'http://publication.local')
            if (url.pathname.startsWith('/internal/')) {
                if (!timingSafeEqual(hash(String(req.headers['x-publication-key'] || '')), credential)) return json(res, 401, {error: 'Unauthorized'})
                if (req.headers.origin) return json(res, 403, {error: 'Browser access forbidden'})
                if (req.method === 'GET' && url.pathname === '/internal/status') return json(res, 200, await coordinator.status())
                if (req.method === 'POST' && url.pathname === '/internal/retry') {req.resume(); return json(res, 202, await coordinator.retry())}
                return json(res, 404, {error: 'Not found'})
            }
            if (req.method !== 'GET' && req.method !== 'HEAD') return json(res, 405, {error: 'Method not allowed'})
            if (url.pathname === '/health') return json(res, 200, {status: 'UP'})
            if (url.pathname === '/blog') {res.writeHead(301, {Location: '/blog/'}); res.end(); return}
            if (!url.pathname.startsWith('/blog/')) return json(res, 404, {error: 'Not found'})
            const state = await repository.load()
            if (!state.activeRelease) return json(res, 503, {error: '页面正在首次生成，请稍后刷新'})
            const root = path.resolve(directory, 'releases', state.activeRelease)
            let filename = path.resolve(root, '.' + decodeURIComponent(url.pathname.slice(5)))
            if (filename !== root && !filename.startsWith(root + path.sep)) return json(res, 404, {error: 'Not found'})
            if ((await fs.stat(filename)).isDirectory()) filename = path.join(filename, 'index.html')
            const info = await fs.stat(filename)
            if (!info.isFile()) return json(res, 404, {error: 'Not found'})
            res.writeHead(200, {'Content-Type': types[path.extname(filename)] || 'application/octet-stream', 'Content-Length': info.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})
            if (req.method === 'HEAD') res.end()
            else createReadStream(filename).on('error', () => res.destroy()).pipe(res)
        } catch (error) {
            if (res.headersSent) {res.destroy(); return}
            json(res, error.code === 'ENOENT' ? 404 : 503, {error: error.code === 'ENOENT' ? 'Not found' : 'Publication service unavailable'})
        }
    })
}

async function main() {
    const root = path.resolve(__dirname, '../..')
    const dev = process.env.BLOG_DEV === '1'
    const directory = path.resolve(process.env.PUBLICATION_STATE_DIRECTORY || path.join(root, '.runtime/publication'))
    await fs.mkdir(directory, {recursive: true})
    let token = process.env.PUBLICATION_API_TOKEN
    if (!token && dev) {
        const filename = path.join(root, '.runtime/publication.key')
        try {token = await fs.readFile(filename, 'utf8')} catch (error) {
            if (error.code !== 'ENOENT') throw error
            token = randomBytes(32).toString('hex')
            await fs.writeFile(filename, token, {flag: 'wx', mode: 0o600})
        }
    }
    const repository = new FilePublicationRepository(directory)
    const coordinator = new PublicationCoordinator({repository,
        source: new PublicContentSource(process.env.BLOG_API_URL || 'http://127.0.0.1:8080'),
        renderer: new HexoPublicationRenderer({root, directory, dev})})
    const server = createPublicationServer({repository, coordinator, directory, token})
    const port = Number(process.env.PUBLICATION_PORT || (dev ? 5176 : 8081))
    const interval = Number(process.env.PUBLICATION_POLL_MS || 5000)
    if (!Number.isSafeInteger(port) || port < 1 || port > 65535 || !Number.isSafeInteger(interval) || interval < 1000)
        throw new Error('Invalid publication server settings')
    server.on('error', () => {console.error('发布服务端口不可用'); process.exitCode = 1})
    server.listen(port, dev ? '127.0.0.1' : '0.0.0.0', () => {
        console.log(`自动发布服务已启动（端口 ${port}）；只读取公开文章，不读取管理端凭证。`)
        const tick = () => coordinator.tick().catch(() => console.error('发布状态写入失败，请检查持久化目录'))
        // A deployment/restart may change theme or Vue build resources even
        // when the database content digest did not change.
        void coordinator.retry().then(tick).catch(() => console.error('发布初始化状态写入失败'))
        const timer = setInterval(tick, interval)
        const stop = () => {clearInterval(timer); server.close(); process.exitCode = 0}
        process.on('SIGTERM', stop); process.on('SIGINT', stop)
    })
}
if (require.main === module) main().catch(() => {console.error('发布服务启动失败，请检查配置和持久化目录'); process.exitCode = 1})
module.exports = {createPublicationServer}
