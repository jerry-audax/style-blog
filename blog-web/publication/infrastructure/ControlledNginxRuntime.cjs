'use strict'
const fs = require('node:fs/promises')
const syncFs = require('node:fs')
const path = require('node:path')
const {spawn} = require('node:child_process')
const {randomUUID, createHash} = require('node:crypto')
const {createUnifiedIngressPolicy} = require('../domain/UnifiedIngressPolicy.cjs')
const bad = code => Object.assign(new Error(code), {category: code})
const digest = value => createHash('sha256').update(value).digest('hex')
const samePath = (left, right) => process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right
async function realDirectory(directory) {
    const info = await fs.lstat(directory, {bigint: true})
    if (!info.isDirectory() || info.isSymbolicLink() || !samePath(await fs.realpath(directory), directory)) throw bad('NGINX_UNSAFE_DIRECTORY')
    return info
}
async function ensureDirectory(directory) {
    const missing = []; let cursor = directory
    while (true) {
        try {await realDirectory(cursor); break} catch (error) {
            if (error.code !== 'ENOENT' || missing.length >= 64 || path.dirname(cursor) === cursor) throw error
            missing.push(cursor); cursor = path.dirname(cursor)
        }
    }
    for (const filename of missing.reverse()) {try {await fs.mkdir(filename)} catch (error) {if (error.code !== 'EEXIST') throw error}; await realDirectory(filename)}
    return new Set(missing)
}
const quoted = filename => {
    if (/[\u0000-\u001f\u007f"]/.test(filename)) throw bad('NGINX_UNSAFE_DIRECTORY')
    return '"' + filename.split(path.sep).join('/') + '"'
}
function renderConfig(policy, directory) {
    const locations = policy.routes.map(route => {
        const match = route.match === 'exact' ? '=' : '^~'
        if (route.action === 'deny') return `location ${match} ${route.path} { return 404; }`
        const upstream = route.upstream === 'publication' ? policy.publicationOrigin : policy.backendOrigin
        const tls = upstream.startsWith('https:') ? '\nproxy_ssl_server_name on;\nproxy_ssl_verify on;\nproxy_ssl_trusted_certificate /etc/ssl/cert.pem;' : ''
        return `location ${match} ${route.path} {\nproxy_pass ${upstream};\nproxy_http_version 1.1;\nproxy_set_header Host $host;\nproxy_set_header X-Real-IP $remote_addr;\nproxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\nproxy_set_header X-Forwarded-Proto $scheme;\nproxy_set_header Connection "";\nproxy_buffering off;\nproxy_cache off;\nproxy_intercept_errors off;\nproxy_read_timeout ${route.timeout}s;${tls}\n}`
    }).join('\n')
    return `pid ${quoted(path.join(directory, 'nginx.pid'))};\nerror_log stderr crit;\nevents { worker_connections 1024; }\nhttp {\naccess_log off;\nclient_body_temp_path ${quoted(path.join(directory, 'body'))};\nproxy_temp_path ${quoted(path.join(directory, 'proxy'))};\nserver {\nlisten ${policy.listenPort};\nserver_name _;\nabsolute_redirect off;\nclient_max_body_size 205m;\n${locations}\n}\n}\n`
}
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function probe(url, {signal}) {
    const response = await fetch(url, {signal, redirect: 'error', credentials: 'omit'})
    if (response.status !== 200) {await response.body?.cancel(); throw bad('NGINX_NOT_READY')}
    const reader = response.body?.getReader(); if (!reader) throw bad('NGINX_NOT_READY')
    try {
        const chunks = []; let total = 0
        while (true) {const part = await reader.read(); if (part.done) break; if ((total += part.value.length) > 1024) throw bad('NGINX_NOT_READY'); chunks.push(part.value)}
        return JSON.parse(Buffer.concat(chunks).toString('utf8'))
    } finally {await reader.cancel().catch(() => {})}
}
class ControlledNginxRuntime {
    #children = new Map()
    #child = null
    #ready = false
    #stopped = false
    #startPromise = null
    #setupPromise = null
    #stopPromise = null
    #abort = new AbortController()
    #owned = null
    #configStamp = null
    #pidStamp = null
    #configHash = null
    #createdParent = false
    #closed
    constructor({policy, directory, publicationInstanceId, spawnProcess = spawn, probe: readyProbe = probe, startupTimeoutMs = 10000}) {
        const canonical = createUnifiedIngressPolicy({listenPort: policy?.listenPort, publicationOrigin: policy?.publicationOrigin, backendOrigin: policy?.backendOrigin})
        if (JSON.stringify(policy) !== JSON.stringify(canonical) || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(publicationInstanceId)
                || !Number.isSafeInteger(startupTimeoutMs) || startupTimeoutMs < 1 || startupTimeoutMs > 10000) throw bad('NGINX_INVALID_CONFIGURATION')
        Object.assign(this, {policy: canonical, directory: path.resolve(directory), publicationInstanceId, spawnProcess, probe: readyProbe, startupTimeoutMs})
        this.instanceId = randomUUID()
        this.workDirectory = path.join(this.directory, 'ingress', 'instance-' + this.instanceId)
        this.closed = new Promise(resolve => {this.#closed = resolve})
        for (const name of ['policy', 'directory', 'publicationInstanceId', 'instanceId', 'workDirectory', 'closed'])
            Object.defineProperty(this, name, {value: this[name], writable: false, configurable: false, enumerable: true})
    }
    #spawn(args) {
        const child = this.spawnProcess('/usr/sbin/nginx', args, {windowsHide: true, stdio: ['ignore', 'ignore', 'ignore'],
            env: {PATH: process.env.PATH || '', ...(process.env.SystemRoot ? {SystemRoot: process.env.SystemRoot} : {})}})
        let closed
        const done = new Promise(resolve => {closed = resolve})
        this.#children.set(child, done)
        child.once('error', () => {this.#ready = false; if (!child.pid) {this.#children.delete(child); closed()}})
        child.once('close', () => {this.#children.delete(child); if (child === this.#child) {this.#ready = false; this.#abort.abort(); this.#closed()}; closed()})
        return child
    }
    async #bounded(promise, deadline) {
        let timer
        try {return await Promise.race([promise, new Promise((_, reject) => {timer = setTimeout(() => reject(bad('NGINX_START_TIMEOUT')), Math.max(1, deadline - Date.now()))})])}
        finally {clearTimeout(timer)}
    }
    start() {
        if (this.#stopped) return Promise.reject(bad('NGINX_STOPPED'))
        if (!this.#startPromise) this.#startPromise = this.#start()
        return this.#startPromise
    }
    async #start() {
        const deadline = Date.now() + this.startupTimeoutMs
        try {
            const filename = path.join(this.workDirectory, 'nginx.conf')
            this.#setupPromise = (async () => {
                const parent = path.dirname(this.workDirectory), created = await ensureDirectory(parent)
                this.#createdParent = created.has(parent)
                await fs.mkdir(this.workDirectory); this.#owned = await realDirectory(this.workDirectory)
                const content = renderConfig(this.policy, this.workDirectory)
                await fs.writeFile(filename, content, {flag: 'wx', mode: 0o600}); await fs.chmod(filename, 0o400)
                this.#configStamp = await fs.lstat(filename, {bigint: true}); this.#configHash = digest(content)
            })()
            await this.#setupPromise
            if (this.#abort.signal.aborted) throw bad('NGINX_STOPPED')
            const validation = this.#spawn(['-t', '-p', this.workDirectory + path.sep, '-c', filename])
            await this.#bounded(new Promise((resolve, reject) => {
                validation.once('error', () => reject(bad('NGINX_VALIDATION_FAILED')))
                validation.once('close', code => code === 0 ? resolve() : reject(bad('NGINX_VALIDATION_FAILED')))
            }), deadline)
            if (this.#abort.signal.aborted) throw bad('NGINX_STOPPED')
            this.#child = this.#spawn(['-p', this.workDirectory + path.sep, '-c', filename, '-g', 'daemon off;'])
            await this.#bounded((async () => {
                while (!this.#abort.signal.aborted) {
                    try {
                        const pidFile = path.join(this.workDirectory, 'nginx.pid')
                        if (Number(await fs.readFile(pidFile, 'utf8')) === this.#child.pid) {
                            const response = await this.probe(`http://127.0.0.1:${this.policy.listenPort}/health`, {signal: this.#abort.signal})
                            if (response?.status === 'UP' && response.instanceId === this.publicationInstanceId) {
                                this.#pidStamp = await fs.lstat(pidFile, {bigint: true}); this.#ready = true
                                if (this.evidence()) return
                            }
                        }
                    } catch {}
                    await delay(20)
                }
                throw bad('NGINX_NOT_READY')
            })(), deadline)
        } catch (error) {await this.stop(); throw error}
    }
    evidence() {
        if (!this.#ready || this.#stopped || !this.#child || this.#child.exitCode !== null || this.#child.signalCode !== null || this.#child.killed) return null
        try {
            const filename = path.join(this.workDirectory, 'nginx.conf'), pidFile = path.join(this.workDirectory, 'nginx.pid')
            const root = syncFs.lstatSync(this.workDirectory, {bigint: true})
            if (root.isSymbolicLink() || !root.isDirectory() || root.dev !== this.#owned.dev || root.ino !== this.#owned.ino
                    || !samePath(syncFs.realpathSync(this.workDirectory), this.workDirectory)) throw bad('NGINX_EVIDENCE_CHANGED')
            const config = syncFs.lstatSync(filename, {bigint: true}), pid = syncFs.lstatSync(pidFile, {bigint: true})
            if (!config.isFile() || config.isSymbolicLink() || !pid.isFile() || pid.isSymbolicLink()
                    || config.size > 131072n || pid.size > 32n || config.dev !== this.#configStamp.dev || config.ino !== this.#configStamp.ino
                    || pid.dev !== this.#pidStamp.dev || pid.ino !== this.#pidStamp.ino
                    || digest(syncFs.readFileSync(filename)) !== this.#configHash || Number(syncFs.readFileSync(pidFile, 'utf8')) !== this.#child.pid) throw bad('NGINX_EVIDENCE_CHANGED')
            return Object.freeze({instanceId: this.instanceId, policyDigest: this.policy.digest, ready: true})
        } catch {this.#ready = false; return null}
    }
    stop() {
        this.#stopped = true; this.#ready = false
        this.#closed()
        if (!this.#stopPromise) this.#stopPromise = Promise.resolve().then(() => this.#stop())
        return this.#stopPromise
    }
    async #stop() {
        this.#stopped = true; this.#ready = false; this.#abort.abort()
        // Wait only for directory preparation, never for start() (whose error
        // path awaits this stop). This closes startup/cleanup without a cycle.
        await this.#setupPromise?.catch(() => {})
        for (const child of this.#children.keys()) if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM')
        const waitChildren = async timeout => {
            let timer
            try {await Promise.race([Promise.all(this.#children.values()), new Promise(resolve => {timer = setTimeout(resolve, timeout)})])}
            finally {clearTimeout(timer)}
        }
        await waitChildren(3000)
        let forced = false
        for (const child of this.#children.keys()) if (child.exitCode === null && child.signalCode === null) {forced = true; child.kill('SIGKILL')}
        await waitChildren(1000)
        if (forced || this.#children.size) return // 强制退出后不猜测孙进程已退出，保留私有工作目录。
        if (this.#owned) {
            const info = await realDirectory(this.workDirectory)
            if (info.dev !== this.#owned.dev || info.ino !== this.#owned.ino || path.dirname(this.workDirectory) !== path.join(this.directory, 'ingress')) throw bad('NGINX_UNSAFE_CLEANUP')
            let count = 0
            const inspect = async filename => {
                if (++count > 10000 || !samePath(await fs.realpath(filename), filename)) throw bad('NGINX_UNSAFE_CLEANUP')
                const entry = await fs.lstat(filename)
                if (entry.isSymbolicLink()) throw bad('NGINX_UNSAFE_CLEANUP')
                if (entry.isDirectory()) for (const name of await fs.readdir(filename)) await inspect(path.join(filename, name))
            }
            await inspect(this.workDirectory)
            await fs.rm(this.workDirectory, {recursive: true, force: false})
        }
        if (this.#createdParent) {
            try {await realDirectory(path.dirname(this.workDirectory)); await fs.rmdir(path.dirname(this.workDirectory))}
            catch (error) {if (!['ENOTEMPTY', 'ENOENT'].includes(error.code)) throw error}
        }
    }
}
module.exports = {ControlledNginxRuntime}
