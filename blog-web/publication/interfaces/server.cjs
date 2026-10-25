'use strict'
const http = require('node:http')
const path = require('node:path')
const {createHash, timingSafeEqual, randomUUID} = require('node:crypto')
const {loadRuntimeSettings} = require('./runtime-settings.cjs')
const {PublicationCoordinator} = require('../application/PublicationCoordinator.cjs')
const {FilePublicationRepository} = require('../infrastructure/FilePublicationRepository.cjs')
const {PublicContentSource} = require('../infrastructure/PublicContentSource.cjs')
const {HexoPublicationRenderer} = require('../infrastructure/HexoPublicationRenderer.cjs')
const {PublicationServingGate} = require('../infrastructure/PublicationServingGate.cjs')
const {ProtectedPublicationCoordinator} = require('../application/ProtectedPublicationCoordinator.cjs')
const {FilePublicationAttemptRepository} = require('../infrastructure/FilePublicationAttemptRepository.cjs')
const {FrozenPublicationInputs} = require('../infrastructure/FrozenPublicationInputs.cjs')
const {PublicationManifestCollector} = require('../infrastructure/PublicationManifestCollector.cjs')
const {AnzhiyuScriptPolicy} = require('../infrastructure/AnzhiyuScriptPolicy.cjs')
const {PublicationMachineClient} = require('../infrastructure/PublicationMachineClient.cjs')
const {PublicationStaticRequest} = require('../application/PublicationStaticRequest.cjs')
const {openPublicationFile} = require('../infrastructure/FilePublicationReader.cjs')
const types = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.xml': 'application/xml', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp', '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.txt': 'text/plain; charset=utf-8'}

function createPublicationServer({repository, coordinator, directory, token, instanceId, coverage, gate = new PublicationServingGate()}) {
    if (typeof token !== 'string' || token.length < 32) throw new Error('A publication credential is required')
    const hash = value => createHash('sha256').update(value).digest()
    const credential = hash(token)
    const staticRequests = new PublicationStaticRequest({repository, coordinator, directory, gate, openFile: openPublicationFile})
    const json = (res, code, value) => {res.writeHead(code, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'}); res.end(JSON.stringify(value))}
    return http.createServer(async (req, res) => {
        let finishPreparation = () => {}
        let fileHandle
        try {
            const url = new URL(req.url, 'http://publication.local')
            if (url.pathname.startsWith('/internal/')) {
                if (Object.hasOwn(req.headers, 'origin')) return json(res, 403, {error: 'Browser access forbidden'})
                const keys = req.rawHeaders.filter((value, index) => index % 2 === 0 && value.toLowerCase() === 'x-publication-key')
                if (keys.length !== 1) return json(res, 401, {error: 'Unauthorized'})
                if (!timingSafeEqual(hash(String(req.headers['x-publication-key'] || '')), credential)) return json(res, 401, {error: 'Unauthorized'})
                if (req.method === 'GET' && url.pathname === '/internal/status') return json(res, 200, await coordinator.status())
                if (req.method === 'POST' && url.pathname === '/internal/retry') {req.resume(); return json(res, 202, await coordinator.retry())}
                return json(res, 404, {error: 'Not found'})
            }
            if (req.method !== 'GET' && req.method !== 'HEAD') return json(res, 405, {error: 'Method not allowed'})
            if (url.pathname === '/health') return json(res, 200, {status: 'UP', ...(instanceId ? {instanceId} : {})})
            if (url.pathname === '/') {res.writeHead(302, {Location: '/blog/' + url.search}); res.end(); return}
            if (url.pathname === '/blog') {res.writeHead(301, {Location: '/blog/' + url.search}); res.end(); return}
            if (res.destroyed) return
            // 用例持有指针读取前的租约；接口负责等响应与文件句柄均结束再归还。
            let release = () => {}
            const cancellation = new AbortController()
            let responseDone = false, streamDone = true, preparationDone = false, stream
            const finishLease = () => {if (preparationDone && responseDone && streamDone) release()}
            finishPreparation = () => {preparationDone = true; finishLease()}
            res.once('finish', () => {responseDone = true; finishLease()})
            res.once('close', () => {responseDone = true; cancellation.abort(); stream?.destroy(); finishLease()})
            const resolved = await staticRequests.resolve(req.url.split('?')[0], {signal: cancellation.signal})
            if (!resolved) return json(res, 404, {error: 'Not found'})
            const {file} = resolved
            release = resolved.releaseLease
            fileHandle = file.handle
            if (res.destroyed) {responseDone = true; return}
            res.writeHead(200, {'Content-Type': types[path.extname(file.filename)] || 'application/octet-stream', 'Content-Length': file.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})
            if (req.method === 'HEAD') res.end()
            else {
                streamDone = false
                stream = fileHandle.createReadStream({start: 0})
                fileHandle = null
                stream.once('close', () => {streamDone = true; finishLease()})
                stream.once('error', () => res.destroy())
                if (res.destroyed) stream.destroy()
                else stream.pipe(res)
            }
        } catch (error) {
            if (res.destroyed) return
            if (res.headersSent) {res.destroy(); return}
            const message = error.code === 'ENOENT' ? 'Not found'
                : error.code === 'PUBLICATION_NOT_READY' ? '页面正在首次生成，请稍后刷新' : 'Publication service unavailable'
            json(res, error.code === 'ENOENT' ? 404 : 503, {error: message})
        } finally {
            try {if (fileHandle) await fileHandle.close()} finally {finishPreparation()}
        }
    })
}

function createPublicationRuntime({root, directory, dev = false, token, env = process.env}) {
    const setting = env.PUBLICATION_MEDIA_ENABLED ?? 'false'
    if (!['false', 'true'].includes(setting)) throw new Error('Invalid publication protection setting')
    const repository = new FilePublicationRepository(directory)
    const gate = new PublicationServingGate()
    const source = new PublicContentSource(env.BLOG_API_URL || 'http://127.0.0.1:8080')
    let coordinator
    if (setting === 'true') {
        if (typeof token !== 'string' || token.length < 32) throw new Error('A publication credential is required')
        const client = new PublicationMachineClient({baseUrl: env.PUBLICATION_MEDIA_BACKEND_URL,
            environmentId: env.PUBLICATION_MEDIA_ENVIRONMENT_ID, publisherId: env.PUBLICATION_MEDIA_PUBLISHER_ID,
            trustedHttpOrigin: env.PUBLICATION_MEDIA_TRUSTED_HTTP_ORIGIN, tokenSupplier: () => token})
        // 保护发布始终使用已构建的 Vue 输入，不能用开发服务器绕过清单校验。
        const inputs = new FrozenPublicationInputs({root, directory, dev: false})
        coordinator = new ProtectedPublicationCoordinator({repository, source, gate, client, inputs,
            attempts: new FilePublicationAttemptRepository(path.join(directory, 'attempts')),
            collector: new PublicationManifestCollector({directory, inputs, scriptPolicy: new AnzhiyuScriptPolicy(), layoutVersion: 2}),
            renderer: new HexoPublicationRenderer({root, directory, inputs, dev: false, layoutVersion: 2})})
    } else {
        coordinator = new PublicationCoordinator({repository, source, renderer: new HexoPublicationRenderer({root, directory, dev})})
    }
    return {repository, coordinator, gate, directory, instanceId: randomUUID()}
}

async function startPublicationServer({runtime, host, port, token, coverage, interval = 5000}) {
    const {repository, coordinator, gate, directory, instanceId} = runtime
    const initial = await repository.load()
    if (initial.protection && !(coordinator instanceof ProtectedPublicationCoordinator))
        throw new Error('Protected publication requires protection to remain enabled')
    const server = createPublicationServer({repository, coordinator, directory, token, gate, instanceId, coverage})
    const halt = () => {
        if (coordinator.stop) coordinator.stop()
        else {coordinator.source?.stop?.(); coordinator.renderer?.stop?.()}
    }
    try {
        await new Promise((resolve, reject) => {
            const error = cause => {server.off('listening', listening); reject(cause)}
            const listening = () => {server.off('error', error); resolve()}
            server.once('error', error); server.once('listening', listening); server.listen(port, host)
        })
    } catch (error) {halt(); throw error}
    let stopped = false, pending = null, stopping = null
    const tick = () => {
        if (stopped || pending) return pending
        pending = Promise.resolve().then(() => coordinator.tick())
            .catch(() => console.error('发布状态写入失败，请检查持久化目录'))
            .finally(() => {pending = null})
        return pending
    }
    // Keep the retry and first tick in the same owned promise as later polls.
    pending = Promise.resolve().then(() => coordinator.retry()).then(() => coordinator.tick())
        .catch(() => console.error('发布初始化状态写入失败')).finally(() => {pending = null})
    const timer = setInterval(tick, interval)
    const stop = () => {
        if (!stopping) {
            stopped = true; clearInterval(timer); halt()
            const closing = new Promise(resolve => server.close(resolve))
            server.closeAllConnections()
            stopping = Promise.all([closing, pending]).then(() => {})
        }
        return stopping
    }
    return {server, stop}
}

async function main() {
    const settings = await loadRuntimeSettings({root: path.resolve(__dirname, '../..')})
    const runtime = createPublicationRuntime(settings)
    const owned = await startPublicationServer({runtime, ...settings, host: settings.dev ? '127.0.0.1' : '0.0.0.0'})
    console.log(`自动发布服务已启动（端口 ${settings.port}）；只读取公开文章，不读取管理端凭证。`)
    const stop = () => owned.stop().catch(() => {process.exitCode = 1})
    process.once('SIGTERM', stop); process.once('SIGINT', stop)
}
if (require.main === module) main().catch(() => {console.error('发布服务启动失败，请检查配置和持久化目录'); process.exitCode = 1})
module.exports = {createPublicationServer, createPublicationRuntime, startPublicationServer}
