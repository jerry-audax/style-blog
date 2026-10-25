const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const {once} = require('node:events')
const {createPublicationServer} = require('../publication/interfaces/server.cjs')
const {FilePublicationRepository} = require('../publication/infrastructure/FilePublicationRepository.cjs')
const {PublicationCoordinator} = require('../publication/application/PublicationCoordinator.cjs')
const {PublicationServingGate} = require('../publication/infrastructure/PublicationServingGate.cjs')
const http = require('node:http')

test('reader lease starts before pointer lookup and ends after the response', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-http-test-'))
    const gate = new PublicationServingGate()
    let leaseEnded
    const ended = new Promise(resolve => {leaseEnded = resolve})
    const acquire = gate.acquireRead.bind(gate)
    gate.acquireRead = async () => {
        const release = await acquire()
        return () => {release(); leaseEnded()}
    }
    let lookupStarted, resumeLookup
    const started = new Promise(resolve => {lookupStarted = resolve})
    const resume = new Promise(resolve => {resumeLookup = resolve})
    const repository = {async load() {lookupStarted(); await resume; return {activeRelease: 'release-old'}}}
    const server = createPublicationServer({repository, coordinator: {}, directory,
        token: 'fixture-secret-not-a-real-credential', gate})
    try {
        await fs.mkdir(path.join(directory, 'releases/release-old'), {recursive: true})
        await fs.writeFile(path.join(directory, 'releases/release-old/index.html'), 'leased page')
        server.listen(0, '127.0.0.1'); await once(server, 'listening')
        const pending = fetch(`http://127.0.0.1:${server.address().port}/blog/`)
        await started
        let retired = false
        assert.equal(await gate.tryExclusive(async () => {retired = true}), false)
        assert.equal(retired, false)
        resumeLookup()
        assert.equal(await (await pending).text(), 'leased page')
        await ended
        assert.equal(await gate.tryExclusive(async () => {retired = true}), true)
        assert.equal(retired, true)
    } finally {
        resumeLookup()
        if (server.listening) await new Promise(resolve => server.close(resolve))
        await fs.rm(directory, {recursive: true, force: true})
    }
})

test('internal controls reject empty Origin before auth and duplicate credentials', async () => {
    const token = 'fixture-secret-not-a-real-credential'
    const server = createPublicationServer({repository: {}, coordinator: {status: async () => ({})}, directory: os.tmpdir(), token})
    server.listen(0, '127.0.0.1'); await once(server, 'listening')
    const request = headers => new Promise((resolve, reject) => {
        const req = http.get({host: '127.0.0.1', port: server.address().port, path: '/internal/status', headers}, res => {
            res.resume(); res.on('end', () => resolve(res.statusCode))
        }); req.on('error', reject)
    })
    try {
        assert.equal(await request({Origin: ''}), 403)
        assert.equal(await request({'X-Publication-Key': [token, token]}), 401)
        assert.equal(await request({'X-Publication-Key': token}), 200)
    } finally {await new Promise(resolve => server.close(resolve))}
})

test('aborting during pointer lookup retains the lease until pending preparation exits', async () => {
    const gate = new PublicationServingGate()
    let beginLookup, endLookup, released
    const started = new Promise(resolve => {beginLookup = resolve})
    const resume = new Promise(resolve => {endLookup = resolve})
    const ended = new Promise(resolve => {released = resolve})
    const acquire = gate.acquireRead.bind(gate)
    gate.acquireRead = async () => {const release = await acquire(); return () => {release(); released()}}
    const repository = {async load() {beginLookup(); await resume; return {activeRelease: null}}}
    const server = createPublicationServer({repository, coordinator: {}, directory: os.tmpdir(),
        token: 'fixture-secret-not-a-real-credential', gate})
    let serverClosed
    const closed = new Promise(resolve => {serverClosed = resolve})
    server.on('request', (_req, res) => res.once('close', serverClosed))
    server.listen(0, '127.0.0.1'); await once(server, 'listening')
    const request = http.get({host: '127.0.0.1', port: server.address().port, path: '/blog/'})
    request.on('error', () => {})
    try {
        await started; request.destroy(); await closed
        assert.equal(await gate.tryExclusive(async () => {}), false)
        endLookup(); await ended
        assert.equal(await gate.tryExclusive(async () => {}), true)
    } finally {endLookup(); request.destroy(); await new Promise(resolve => server.close(resolve))}
})

test('public serving rejects directory links outside the committed release for GET and HEAD', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-http-test-'))
    const repository = new FilePublicationRepository(directory)
    const server = createPublicationServer({repository, coordinator: {}, directory, token: 'fixture-secret-not-a-real-credential'})
    try {
        const release = path.join(directory, 'releases/release-old'), outside = path.join(directory, 'private')
        await fs.mkdir(release, {recursive: true}); await fs.mkdir(outside)
        await fs.writeFile(path.join(outside, 'index.html'), 'private-data-must-not-be-served')
        await fs.symlink(outside, path.join(release, 'leak'), process.platform === 'win32' ? 'junction' : 'dir')
        await repository.save({...await repository.load(), activeRelease: 'release-old'})
        server.listen(0, '127.0.0.1'); await once(server, 'listening')
        for (const method of ['GET', 'HEAD']) {
            const response = await fetch(`http://127.0.0.1:${server.address().port}/blog/leak/`, {method})
            assert.equal(response.status, 503)
            assert.doesNotMatch(await response.text(), /private-data-must-not-be-served/)
        }
    } finally {
        if (server.listening) await new Promise(resolve => server.close(resolve))
        await fs.unlink(path.join(directory, 'releases/release-old/leak')).catch(error => {if (error.code !== 'ENOENT') throw error})
        await fs.rm(directory, {recursive: true, force: true})
    }
})

test('protected serving checks the recorded root identity before opening a page', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-http-test-'))
    const repository = {load: async () => ({activeRelease: 'release-old', protection: {}})}
    let checked = 0
    const coordinator = {servingProof: async () => {checked++; return {device: '-1', inode: '-1'}}}
    const server = createPublicationServer({repository, coordinator, directory, token: 'fixture-secret-not-a-real-credential'})
    try {
        await fs.mkdir(path.join(directory, 'releases/release-old'), {recursive: true})
        await fs.writeFile(path.join(directory, 'releases/release-old/index.html'), 'unverified-replacement')
        server.listen(0, '127.0.0.1'); await once(server, 'listening')
        const response = await fetch(`http://127.0.0.1:${server.address().port}/blog/`)
        assert.equal(response.status, 503)
        assert.doesNotMatch(await response.text(), /unverified-replacement/)
        assert.equal(checked, 1)
    } finally {
        if (server.listening) await new Promise(resolve => server.close(resolve))
        await fs.rm(directory, {recursive: true, force: true})
    }
})

test('HEAD and an interrupted backpressured GET both close their file and release the serving lease', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-http-test-'))
    const gate = new PublicationServingGate()
    let leaseEnded
    const acquire = gate.acquireRead.bind(gate)
    gate.acquireRead = async () => {const release = await acquire(); return () => {release(); leaseEnded?.()}}
    const repository = {load: async () => ({activeRelease: 'release-old'})}
    const server = createPublicationServer({repository, coordinator: {}, directory,
        token: 'fixture-secret-not-a-real-credential', gate})
    let request, response
    try {
        await fs.mkdir(path.join(directory, 'releases/release-old'), {recursive: true})
        // 大于传输窗口的真实文件使暂停的客户端形成背压，不用计时猜测响应是否结束。
        const filename = path.join(directory, 'releases/release-old/large.txt')
        const handle = await fs.open(filename, 'wx')
        try {await handle.truncate(32 * 1024 * 1024)} finally {await handle.close()}
        server.listen(0, '127.0.0.1'); await once(server, 'listening')
        const base = `http://127.0.0.1:${server.address().port}`
        let ended = new Promise(resolve => {leaseEnded = resolve})
        const head = await fetch(base + '/blog/large.txt', {method: 'HEAD'})
        assert.equal(head.status, 200); assert.equal(await head.text(), '')
        await ended
        assert.equal(await gate.tryExclusive(async () => {}), true)
        ended = new Promise(resolve => {leaseEnded = resolve})
        response = await new Promise((resolve, reject) => {
            request = http.get(base + '/blog/large.txt', res => {res.pause(); resolve(res)})
            request.on('error', reject)
        })
        assert.equal(response.statusCode, 200)
        assert.equal(await gate.tryExclusive(async () => {}), false)
        response.destroy(); request.destroy(); await ended
        assert.equal(await gate.tryExclusive(async () => {}), true)
    } finally {
        response?.destroy(); request?.destroy()
        if (server.listening) await new Promise(resolve => server.close(resolve))
        await fs.rm(directory, {recursive: true, force: true})
    }
})

test('private publication controls require credentials; public pages read only the committed release', async () => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-http-test-'))
    const repository = new FilePublicationRepository(directory)
    const coordinator = new PublicationCoordinator({repository, source: {}, renderer: {}})
    const token = 'fixture-secret-not-a-real-credential'
    const server = createPublicationServer({repository, coordinator, directory, token})
    try {
        await fs.mkdir(path.join(directory, 'releases/release-old/article/11'), {recursive: true})
        await fs.writeFile(path.join(directory, 'releases/release-old/article/11/index.html'), '<!doctype html><body>已发布页面</body>')
        await repository.save({...await repository.load(), phase: 'FAILED', activeRelease: 'release-old'})
        server.listen(0, '127.0.0.1'); await once(server, 'listening')
        const base = `http://127.0.0.1:${server.address().port}`
        assert.equal((await fetch(base + '/internal/status')).status, 401)
        assert.equal((await fetch(base + '/internal/retry', {method: 'POST'})).status, 401)
        const status = await fetch(base + '/internal/status', {headers: {'X-Publication-Key': token}})
        assert.equal(status.status, 200)
        assert.equal((await status.json()).phase, 'FAILED')
        const page = await fetch(base + '/blog/article/11/')
        assert.equal(page.status, 200)
        assert.match(await page.text(), /已发布页面/)
        assert.equal(page.headers.get('cache-control'), 'no-store')
        for (const url of ['/publication.json', '/blog/%2e%2e/publication.json', '/blog/article/12/'])
            assert.equal((await fetch(base + url)).status, 404)
        assert.equal((await fetch(base + '/internal/retry', {method: 'POST', headers: {'X-Publication-Key': token, Origin: 'https://untrusted.example'}})).status, 403)
        const retry = await fetch(base + '/internal/retry', {method: 'POST', headers: {'X-Publication-Key': token}})
        assert.equal(retry.status, 202)
        assert.equal((await retry.json()).phase, 'WAITING')
    } finally {
        if (server.listening) await new Promise(resolve => server.close(resolve))
        assert.equal(path.dirname(directory), os.tmpdir())
        assert.match(path.basename(directory), /^publication-http-test-/)
        await fs.rm(directory, {recursive: true, force: true})
    }
})
