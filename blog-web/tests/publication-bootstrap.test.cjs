'use strict'
const {test} = require('node:test')
const assert = require('node:assert/strict')
const {randomUUID} = require('node:crypto')
const {setTimeout: delay} = require('node:timers/promises')
const {startPublicationServer} = require('../publication/interfaces/server.cjs')
const {startUnifiedContainer} = require('../publication/interfaces/container.cjs')
const fs = require('node:fs/promises')
const path = require('node:path')

test('owned server exposes instance identity and stop awaits its sole in-flight tick', async () => {
    assert.equal(typeof startPublicationServer, 'function')
    let finishTick, enteredTick, ticks = 0, stopped = false, settled = false
    const entered = new Promise(resolve => {enteredTick = resolve})
    const runtime = {instanceId: randomUUID(), directory: __dirname,
        repository: {load: async () => ({})},
        coordinator: {retry: async () => {}, tick: async () => {ticks++; enteredTick(); await new Promise(resolve => {finishTick = resolve})},
            stop: () => {stopped = true}}}
    const owned = await startPublicationServer({runtime, host: '127.0.0.1', port: 0, interval: 20, token: 'synthetic-key-'.repeat(4)})
    try {
        await entered
        const response = await fetch(`http://127.0.0.1:${owned.server.address().port}/health`)
        assert.deepEqual(await response.json(), {status: 'UP', instanceId: runtime.instanceId})
        await delay(65); assert.equal(ticks, 1)
        const stopping = owned.stop().then(() => {settled = true})
        await delay(20); assert.equal(stopped, true); assert.equal(settled, false)
        finishTick(); await stopping; assert.equal(settled, true)
        await owned.stop(); assert.equal(ticks, 1)
    } finally {finishTick?.(); await owned.stop()}
})

test('listen failure closes only its own coordinator before rejecting', async () => {
    assert.equal(typeof startPublicationServer, 'function')
    const http = require('node:http')
    const occupied = http.createServer(); await new Promise(resolve => occupied.listen(0, '127.0.0.1', resolve))
    let stopped = 0, ticks = 0
    try {
        await assert.rejects(startPublicationServer({runtime: {directory: __dirname, repository: {load: async () => ({})},
            coordinator: {retry: async () => {}, tick: async () => {ticks++}, stop: () => {stopped++}}},
            host: '127.0.0.1', port: occupied.address().port, token: 'synthetic-key-'.repeat(4)}))
        assert.equal(stopped, 1); assert.equal(ticks, 0); assert.equal(occupied.listening, true)
    } finally {await new Promise(resolve => occupied.close(resolve))}
})

function containerFixture({legacy = false, ingressFails = false} = {}) {
    const events = [], id = randomUUID()
    const pointer = {schemaVersion: legacy ? 2 : 3, layoutVersion: legacy ? 1 : 2, activeRelease: id,
        protection: {releases: [{releaseId: id, layoutVersion: legacy ? 1 : 2}]}}
    const runtime = {instanceId: randomUUID(), directory: __dirname,
        gate: {acquireRead: async () => {events.push('lease'); return () => {events.push('release')}}},
        repository: {load: async () => pointer},
        coordinator: {servingProof: async () => {events.push('proof'); return {releaseId: id, layoutVersion: legacy ? 1 : 2}},
            renderer: {verifyOutput: async () => {events.push('verify')}}}}
    let closeIngress
    const ingress = {start: async () => {events.push('nginx-start'); if (ingressFails) throw new Error('synthetic-failed')},
        stop: async () => {events.push('nginx-stop')}, evidence: () => ({ready: true}),
        closed: new Promise(resolve => {closeIngress = resolve})}
    return {events, runtime, closeIngress, ingress, options: {runtime,
        settings: {directory: __dirname, port: 18081, token: 'synthetic-key-'.repeat(4)},
        env: {PUBLICATION_MEDIA_ENABLED: 'true', PUBLICATION_URL: 'http://127.0.0.1:18081', NGINX_LISTEN_PORT: '18080', BACKEND_URL: 'http://fixture:8080'},
        startupTimeoutMs: 30,
        startServer: async () => {events.push('node-start'); return {stop: async () => {events.push('node-stop')}}},
        ingressFactory: () => ingress}}
}

test('unified container verifies current output under lease before starting owned ingress', async () => {
    assert.equal(typeof startUnifiedContainer, 'function')
    const fixture = containerFixture()
    const owned = await startUnifiedContainer(fixture.options)
    assert.deepEqual(fixture.events, ['node-start', 'lease', 'proof', 'verify', 'release', 'nginx-start'])
    fixture.closeIngress()
    await owned.closed
    assert.equal(fixture.events.at(-1), 'node-stop')
    await owned.stop()
    assert.equal(fixture.events.filter(event => event === 'node-stop').length, 1)
})

test('legacy current output or failed protected ingress cannot fall back to old static serving', async () => {
    assert.equal(typeof startUnifiedContainer, 'function')
    for (const scenario of [{legacy: true}, {ingressFails: true}]) {
        const fixture = containerFixture(scenario)
        await assert.rejects(startUnifiedContainer(fixture.options))
        assert.equal(fixture.events.at(-1), 'node-stop')
        if (scenario.legacy) assert.equal(fixture.events.includes('nginx-start'), false)
    }
})

test('container private listener origin must match the owned server port', async () => {
    assert.equal(typeof startUnifiedContainer, 'function')
    const fixture = containerFixture()
    fixture.options.env.PUBLICATION_URL = 'http://127.0.0.1:18082'
    await assert.rejects(startUnifiedContainer(fixture.options))
    assert.deepEqual(fixture.events, [])
})

test('protected shell entrypoint selects owned bootstrap before legacy envsubst and never falls back', async () => {
    const source = await fs.readFile(path.join(__dirname, '../docker-entrypoint.sh'), 'utf8')
    const selection = source.indexOf('case "${PUBLICATION_MEDIA_ENABLED:-false}" in')
    assert.ok(selection >= 0, 'an explicit default-off mode selection is required')
    assert.ok(selection < source.indexOf('envsubst'))
    assert.match(source, /true\)\s*exec node \/app\/publication\/interfaces\/container\.cjs\s*;;/)
    assert.match(source, /false\)\s*;;/)
    assert.match(source, /\*\)[\s\S]*?exit 1/)
})
