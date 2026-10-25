const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const {randomUUID, createHash} = require('node:crypto')
const {once} = require('node:events')
const http = require('node:http')
const {createPublicationServer} = require('../publication/interfaces/server.cjs')
const {FilePublicationRepository} = require('../publication/infrastructure/FilePublicationRepository.cjs')
const {PublicationServingGate} = require('../publication/infrastructure/PublicationServingGate.cjs')
const {publicationOutputDigest} = require('../publication/infrastructure/PublicationManifestCollector.cjs')
const sha = value => createHash('sha256').update(value).digest('hex')

async function fixture(run, gateOptions = {}) {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-unified-http-test-'))
    const repository = new FilePublicationRepository(directory), gate = new PublicationServingGate(gateOptions), versions = new Map()
    async function version(label, hash) {
        const id = 'release-' + randomUUID(), root = path.join(directory, 'releases', id)
        const asset = `assets/app-${hash}.js`
        const files = new Map([['index.html', `<!doctype html><body>Vue ${label}<script type="module" src="/${asset}"></script></body>`],
            [asset, `export const version = '${label}';`], ['blog/index.html', `<!doctype html><body>Hexo ${label}</body>`]])
        const registered = new Map()
        for (const [name, text] of files) {
            const filename = path.join(root, name)
            await fs.mkdir(path.dirname(filename), {recursive: true}); await fs.writeFile(filename, text)
            if (!name.startsWith('blog/')) registered.set(name, {path: '.build/app/' + name, sha256: sha(text), size: Buffer.byteLength(text)})
        }
        const info = await fs.lstat(root, {bigint: true})
        const output = {releaseId: id, stageName: 'stage-' + randomUUID(), layoutVersion: 2,
            outputDigest: await publicationOutputDigest(root), device: String(info.dev), inode: String(info.ino)}
        const record = {id, root, asset, registered, output, sourceDigest: sha('source ' + label)}
        versions.set(id, record); return record
    }
    async function publish(active, fallback = null) {
        await repository.save({...await repository.load(), schemaVersion: 3, layoutVersion: 2, phase: 'CURRENT',
            activeRelease: active.id, fallbackRelease: fallback?.id ?? null,
            publishedDigest: sha('intent ' + active.id), desiredDigest: sha('intent ' + active.id),
            protection: {protocolVersion: 1, environmentId: 'test', publisherId: 'web',
                releases: [active, ...(fallback ? [fallback] : [])].map(record => ({releaseId: record.id, layoutVersion: 2,
                    sourceDigest: record.sourceDigest, outputDigest: record.output.outputDigest}))}})
    }
    const coordinator = {
        async servingProof(state, id = state.activeRelease) {
            assert.ok([state.activeRelease, state.fallbackRelease].includes(id))
            assert.ok(state.protection.releases.some(item => item.releaseId === id))
            return versions.get(id).output
        },
        async servingEntry(state, id, relative) {
            return {proof: await this.servingProof(state, id), sourceFile: versions.get(id).registered.get(relative) ?? null}
        },
    }
    const server = createPublicationServer({repository, coordinator, directory, gate, token: 'synthetic-unified-http-key-not-real'})
    try {
        const first = await version('first', 'aaaaaaaa'), second = await version('second', 'bbbbbbbb')
        await publish(second, first)
        server.listen(0, '127.0.0.1'); await once(server, 'listening')
        await run({directory, repository, coordinator, gate, versions, first, second, publish, version, server,
            base: `http://127.0.0.1:${server.address().port}`})
    } finally {
        server.closeAllConnections()
        if (server.listening) await new Promise(resolve => server.close(resolve))
        assert.equal(path.dirname(directory), os.tmpdir()); assert.match(path.basename(directory), /^publication-unified-http-test-/)
        await fs.rm(directory, {recursive: true, force: true})
    }
}

test('unified HTTP serves Vue routes and Hexo from their own namespace', async () => {
    await fixture(async ({base, second}) => {
        const root = await fetch(base + '/?from=test', {redirect: 'manual'})
        assert.equal(root.status, 302); assert.equal(root.headers.get('location'), '/blog/?from=test')
        const blog = await fetch(base + '/blog/')
        assert.equal(blog.status, 200); assert.match(await blog.text(), /Hexo second/)
        for (const route of ['/index.html', '/article/19', '/login', '/register', '/profile', '/ai']) {
            const response = await fetch(base + route)
            assert.equal(response.status, 200); assert.match(await response.text(), /Vue second/)
        }
        const script = await fetch(base + '/' + second.asset)
        assert.equal(script.status, 200); assert.match(await script.text(), /second/)
        assert.match(script.headers.get('content-type'), /javascript/)
        assert.equal(script.headers.get('cache-control'), 'no-store')
    })
})

test('registered fallback chunks remain available across a switch but unknown older directories do not', async () => {
    await fixture(async ({base, first, second, publish, version}) => {
        await publish(first)
        const original = await fetch(base + '/index.html')
        assert.equal(original.status, 200)
        assert.match(await original.text(), /app-aaaaaaaa.js/)
        await publish(second, first)
        const fallback = await fetch(base + '/' + first.asset)
        assert.equal(fallback.status, 200); assert.match(await fallback.text(), /first/)
        const third = await version('third', 'cccccccc')
        await publish(third, second)
        assert.equal((await fetch(base + '/' + first.asset)).status, 404)
        await fs.access(path.join(first.root, first.asset))
    })
})

test('missing and unregistered resources cannot fall back to Vue HTML or loose disk files', async () => {
    await fixture(async ({base, second}) => {
        await fs.writeFile(path.join(second.root, 'assets/unregistered-dddddddd.js'), 'unregistered')
        await fs.writeFile(path.join(second.root, 'publication.json'), 'private journal imitation')
        for (const uri of ['/assets/missing-eeeeeeee.js', '/assets/missing.css', '/assets/missing.png',
            '/assets/unregistered-dddddddd.js', '/publication.json', '/.vite/manifest.json', '/api/articles', '/uploads/x',
            '/assets/%252e%252e/publication.json']) {
            const response = await fetch(base + uri)
            assert.equal(response.status, 404)
            assert.doesNotMatch(await response.text(), /Vue second|private journal imitation|unregistered/)
        }
    })
})

test('registered Vue resources are checked against their frozen bytes before sending headers', async () => {
    await fixture(async ({base, first}) => {
        await fs.writeFile(path.join(first.root, first.asset), "export const version = 'tampered';")
        const response = await fetch(base + '/' + first.asset)
        assert.equal(response.status, 503)
        assert.doesNotMatch(await response.text(), /tampered/)
    })
})

test('a fallback filename that looks hashed still requires frozen resource registration', async () => {
    await fixture(async ({base, first}) => {
        first.registered.delete(first.asset)
        await fs.access(path.join(first.root, first.asset))
        assert.equal((await fetch(base + '/' + first.asset)).status, 404)
    })
})

test('a replaced or wrongly classified unified root is rejected before serving Vue bytes', async () => {
    for (const mode of ['inode', 'layout']) await fixture(async ({base, coordinator}) => {
        const entry = coordinator.servingEntry.bind(coordinator)
        coordinator.servingEntry = async (...args) => {
            const result = await entry(...args)
            return {...result, proof: {...result.proof, ...(mode === 'inode'
                ? {inode: String(BigInt(result.proof.inode) + 1n)} : {layoutVersion: 1})}}
        }
        const response = await fetch(base + '/index.html')
        assert.equal(response.status, 503)
        assert.doesNotMatch(await response.text(), /Vue second/)
    })
})

test('a missing registered current file fails integrity instead of masquerading as fallback or HTML', async () => {
    await fixture(async ({base, second}) => {
        await fs.unlink(path.join(second.root, second.asset))
        const response = await fetch(base + '/' + second.asset)
        assert.equal(response.status, 503)
        assert.doesNotMatch(await response.text(), /Vue|export const/)
    })
})

test('a unified request paused after pointer lookup blocks retirement across a publication switch', async () => {
    await fixture(async ({base, gate, coordinator, second, version, publish}) => {
        let lookupStarted, resumeLookup, leaseEnded
        const started = new Promise(resolve => {lookupStarted = resolve})
        const resume = new Promise(resolve => {resumeLookup = resolve})
        const ended = new Promise(resolve => {leaseEnded = resolve})
        const acquire = gate.acquireRead.bind(gate)
        gate.acquireRead = async () => {const release = await acquire(); return () => {release(); leaseEnded()}}
        const entry = coordinator.servingEntry.bind(coordinator)
        coordinator.servingEntry = async (...args) => {
            const result = await entry(...args)
            lookupStarted(); await resume; return result
        }
        const pending = fetch(base + '/' + second.asset)
        try {
            await started
            assert.equal(await gate.tryExclusive(async () => {}), false)
            const third = await version('third', 'cccccccc')
            await publish(third, second)
            assert.equal(await gate.tryExclusive(async () => {}), false)
            resumeLookup()
            const response = await pending
            assert.equal(response.status, 200); assert.match(await response.text(), /second/)
            await ended
            assert.equal(await gate.tryExclusive(async () => {}), true)
        } finally {resumeLookup(); await pending.catch(() => {})}
    })
})

test('unified HEAD and interrupted backpressured resource streams release their reader lease', async () => {
    await fixture(async ({base, gate, first, second, publish}) => {
        const filename = 'assets/sound-ssssssss.bin', bytes = Buffer.alloc(4 * 1024 * 1024, 120)
        await fs.writeFile(path.join(second.root, filename), bytes)
        second.registered.set(filename, {path: '.build/app/' + filename, size: bytes.length, sha256: sha(bytes)})
        second.output.outputDigest = await publicationOutputDigest(second.root)
        await publish(second, first)
        let leaseEnded
        const acquire = gate.acquireRead.bind(gate)
        gate.acquireRead = async () => {const release = await acquire(); return () => {release(); leaseEnded?.()}}
        let ended = new Promise(resolve => {leaseEnded = resolve})
        const head = await fetch(base + '/' + filename, {method: 'HEAD'})
        assert.equal(head.status, 200); assert.equal(await head.text(), '')
        await ended
        assert.equal(await gate.tryExclusive(async () => {}), true)
        ended = new Promise(resolve => {leaseEnded = resolve})
        let request, response
        try {
            response = await new Promise((resolve, reject) => {
                request = http.get(base + '/' + filename, {highWaterMark: 1}, res => {res.pause(); resolve(res)})
                request.on('error', reject)
            })
            assert.equal(response.statusCode, 200)
            assert.equal(await gate.tryExclusive(async () => {}), false)
            response.destroy(); request.destroy(); await ended
            assert.equal(await gate.tryExclusive(async () => {}), true)
        } finally {response?.destroy(); request?.destroy()}
    })
})

test('an aborted queued reader frees its waiter slot and never loads the publication pointer', async () => {
    await fixture(async ({base, server, gate, repository, second}) => {
        let beginExclusive, endExclusive, beginRead, responseClosed
        const exclusiveStarted = new Promise(resolve => {beginExclusive = resolve})
        const exclusiveEnd = new Promise(resolve => {endExclusive = resolve})
        const readStarted = new Promise(resolve => {beginRead = resolve})
        const closed = new Promise(resolve => {responseClosed = resolve})
        const holding = gate.tryExclusive(async () => {beginExclusive(); await exclusiveEnd})
        await exclusiveStarted
        const acquire = gate.acquireRead.bind(gate)
        gate.acquireRead = (...args) => {beginRead(); return acquire(...args)}
        let loads = 0, result
        const load = repository.load.bind(repository)
        repository.load = async () => {loads++; return load()}
        server.once('request', (_req, res) => res.once('close', responseClosed))
        const request = http.get(base + '/' + second.asset)
        request.on('error', () => {})
        try {
            await readStarted; request.destroy(); await closed
            const probe = gate.acquireRead().then(release => ({release}), error => ({error}))
            endExclusive(); await holding; result = await probe
            assert.equal(result.error, undefined, 'aborted request must free the only waiter slot')
            result.release()
            assert.equal(loads, 0)
        } finally {request.destroy(); endExclusive(); await holding; result?.release?.()}
    }, {maxWaiters: 1})
})
