'use strict'
const path = require('node:path')
const {setTimeout: delay} = require('node:timers/promises')
const {createPublicationRuntime, startPublicationServer} = require('./server.cjs')
const {loadRuntimeSettings} = require('./runtime-settings.cjs')
const {createUnifiedIngressPolicy} = require('../domain/UnifiedIngressPolicy.cjs')
const {ControlledNginxRuntime} = require('../infrastructure/ControlledNginxRuntime.cjs')

async function waitForUnifiedPublication(runtime, timeoutMs) {
    const deadline = Date.now() + timeoutMs
    do {
        const release = await runtime.gate.acquireRead()
        try {
            const state = await runtime.repository.load()
            if (state.schemaVersion === 3 && state.layoutVersion === 2 && state.activeRelease
                    && state.protection?.releases?.some(item => item.releaseId === state.activeRelease && item.layoutVersion === 2)) {
                const output = await runtime.coordinator.servingProof(state)
                if (output.layoutVersion !== 2 || output.releaseId !== state.activeRelease) throw new Error('Unified output identity mismatch')
                await runtime.coordinator.renderer.verifyOutput(output)
                if (JSON.stringify(await runtime.repository.load()) === JSON.stringify(state)) return
            }
        } finally {release()}
        if (Date.now() >= deadline) break
        await delay(Math.min(100, deadline - Date.now()))
    } while (Date.now() < deadline)
    throw new Error('Verified unified publication is not ready')
}

async function startUnifiedContainer({settings, env = process.env, runtime = createPublicationRuntime({...settings, env}),
    ingressFactory = options => new ControlledNginxRuntime(options), startServer = startPublicationServer, startupTimeoutMs = 120000}) {
    if (env.PUBLICATION_MEDIA_ENABLED !== 'true' || !Number.isSafeInteger(startupTimeoutMs) || startupTimeoutMs < 1 || startupTimeoutMs > 120000)
        throw new Error('Protected container requires explicit valid settings')
    const policy = createUnifiedIngressPolicy({listenPort: Number(env.NGINX_LISTEN_PORT || 80),
        publicationOrigin: env.PUBLICATION_URL || 'http://127.0.0.1:8081', backendOrigin: env.BACKEND_URL || 'http://blog-server:8080'})
    const privateUrl = new URL(policy.publicationOrigin)
    if (Number(privateUrl.port || 80) !== settings.port) throw new Error('Publication listener and ingress origin must match')
    const host = privateUrl.hostname === '[::1]' ? '::1' : privateUrl.hostname === 'localhost' ? 'localhost' : '127.0.0.1'
    const ingress = ingressFactory({policy, directory: settings.directory, publicationInstanceId: runtime.instanceId})
    let publication, stopping
    const stop = () => {
        if (!stopping) stopping = Promise.resolve().then(async () => {
            try {await ingress.stop()} finally {await publication?.stop()}
        })
        return stopping
    }
    try {
        publication = await startServer({runtime, ...settings, host})
        await waitForUnifiedPublication(runtime, startupTimeoutMs)
        await ingress.start()
        if (!ingress.evidence()) throw new Error('Controlled ingress is not ready')
    } catch (error) {await stop(); throw error}
    const closed = ingress.closed.then(() => stop())
    return {stop, closed, ingress, publication}
}

async function main() {
    const settings = await loadRuntimeSettings({root: path.resolve(__dirname, '../..')})
    const owned = await startUnifiedContainer({settings})
    let requested = false
    const stop = () => {requested = true; void owned.stop().catch(() => {process.exitCode = 1})}
    process.once('SIGTERM', stop); process.once('SIGINT', stop)
    await owned.closed
    if (!requested) process.exitCode = 1
}
if (require.main === module) main().catch(() => {console.error('受控发布入口启动失败，请检查配置与发布状态'); process.exitCode = 1})
module.exports = {startUnifiedContainer, waitForUnifiedPublication}
