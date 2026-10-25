'use strict'
const path = require('node:path')
const {createHash} = require('node:crypto')
const {snapshotDigest} = require('../domain/Snapshot.cjs')
const {newAttempt, transition} = require('../domain/PublicationAttempt.cjs')
const {identity} = require('../domain/PublicationPin.cjs')
const {layoutOf} = require('../domain/UnifiedPublicationLayout.cjs')
const fail = category => Object.assign(new Error('Publication protection rejected'), {category})
const samePin = (left, right) => ['environmentId', 'publisherId', 'releaseId', 'manifestDigest', 'status', 'version'].every(key => left[key] === right[key])
    && left.assetIds.join(',') === right.assetIds.join(',')
const userError = '发布保护未通过，保留已提交的页面；稍后重试或在管理端手动重试。'
class ProtectedPublicationCoordinator {
    #work = new Map()
    constructor({repository, attempts, source, inputs, collector, renderer, client, gate, clock = Date.now}) {
        if (!identity(client?.environmentId) || !identity(client?.publisherId)) throw fail('INVALID')
        Object.assign(this, {repository, attempts, source, inputs, collector, renderer, client, gate, clock})
        this.layoutVersion = renderer.layoutVersion ?? 1
        if (![1, 2].includes(this.layoutVersion) || (collector.layoutVersion ?? 1) !== this.layoutVersion) throw fail('INVALID')
        this.running = false; this.force = false; this.closed = false; this.workingId = null
    }
    async status() {
        const state = await this.repository.load()
        return {configured: true, phase: state.phase, publishedDigest: state.publishedDigest, desiredDigest: state.desiredDigest,
            publishedAt: state.publishedAt, checkedAt: state.checkedAt, error: state.error}
    }
    async retry() {this.force = true; return this.status()}
    stop() {this.closed = true; this.source.stop?.(); this.renderer.stop?.()}
    #remember(snapshot) {
        if (!this.#work.has(snapshot.releaseId)) this.#work.set(snapshot.releaseId, new Set())
        this.#work.get(snapshot.releaseId).add(snapshot)
        return snapshot
    }
    async #cleanup(id, keepWorkName) {
        const work = this.#work.get(id)
        if (!work) return
        for (const snapshot of work) {
            if (path.basename(snapshot.workRoot) === keepWorkName) continue
            await this.inputs.dispose(snapshot); work.delete(snapshot)
        }
        if (!work.size) this.#work.delete(id)
    }
    async #desired() {
        const posts = await this.source.fetchPosts(), semantic = snapshotDigest(posts), probe = await this.inputs.probe()
        return {posts, probe, digest: createHash('sha256').update(JSON.stringify([semantic, probe])).digest('hex')}
    }
    async #save(attempt, phase, changes = {}) {
        return this.attempts.save(transition(attempt, phase, changes), attempt.recordVersion)
    }
    async #pointerAttempt(state, item) {
        const protection = state.protection
        if (!item || protection?.protocolVersion !== 1 || protection.environmentId !== this.client.environmentId
                || protection.publisherId !== this.client.publisherId) throw fail('INTEGRITY')
        const attempt = await this.attempts.get(item.releaseId)
        if (!attempt || !['VERIFIED', 'ACTIVE'].includes(attempt.phase) || !attempt.output || !attempt.pin
                || layoutOf(attempt) !== (item.layoutVersion ?? 1)
                || attempt.environmentId !== protection.environmentId || attempt.publisherId !== protection.publisherId
                || attempt.pin.status !== 'HELD' || attempt.pin.environmentId !== protection.environmentId
                || attempt.pin.publisherId !== protection.publisherId || attempt.pin.releaseId !== item.releaseId
                || item.releaseId === state.activeRelease && attempt.desiredDigest !== state.publishedDigest
                || attempt.sourceDigest !== item.sourceDigest || attempt.output.outputDigest !== item.outputDigest
                || item.releaseId !== state.activeRelease && item.releaseId !== state.fallbackRelease) throw fail('INTEGRITY')
        return attempt
    }
    async #servingAttempt(state, releaseId) {
        if (!state.protection) throw fail('INTEGRITY')
        const item = state.protection.releases?.find(entry => entry.releaseId === releaseId)
        return this.#pointerAttempt(state, item)
    }
    async servingProof(state, releaseId = state.activeRelease) {
        // 请求路径仅检查耐久登记与目录身份；后台恢复另做完整树校验和远端 pin 核对。
        return (await this.#servingAttempt(state, releaseId)).output
    }
    async servingEntry(state, releaseId, relative) {
        const attempt = await this.#servingAttempt(state, releaseId)
        const sourceFile = layoutOf(attempt) === 2 && !relative.split('/').some(part => part.startsWith('.'))
            ? attempt.input.files.find(file => file.path === '.build/app/' + relative) ?? null : null
        return {proof: attempt.output, sourceFile}
    }
    async #recoverPointer(state) {
        if (!state.protection) return false
        let recovered = false
        const protection = state.protection
        if (protection.protocolVersion !== 1 || protection.environmentId !== this.client.environmentId
                || protection.publisherId !== this.client.publisherId || !Array.isArray(protection.releases)
                || !protection.releases.some(item => item.releaseId === state.activeRelease)) throw fail('INTEGRITY')
        for (const item of protection.releases) {
            let attempt = await this.#pointerAttempt(state, item)
            await this.renderer.recoverStage(attempt.output)
            const pin = await this.client.get(attempt.releaseId)
            if (!samePin(pin, attempt.pin)) throw fail('CONFLICT')
            // 指针是提交事实；崩溃在指针之后时，仅补齐日志，绝不退回旧指针。
            if (attempt.phase === 'VERIFIED') {attempt = await this.#save(attempt, 'ACTIVE'); recovered = true}
            await this.#cleanup(attempt.releaseId)
        }
        return recovered
    }
    async #retire() {
        let recovered = false
        for (let attempt of await this.attempts.listUnfinished()) {
            // 旧布局可能仍由旧入口服务；新版不能自行认领或退休它。
            if (this.layoutVersion === 2 && layoutOf(attempt) !== 2) continue
            if (!['ACTIVE', 'RETIRING', 'RELEASE_UNKNOWN'].includes(attempt.phase)) continue
            const pointer = await this.repository.load()
            if ([pointer.activeRelease, pointer.fallbackRelease].includes(attempt.releaseId)) continue
            this.workingId = attempt.releaseId
            const entered = await this.gate.tryExclusive(async () => {
                const current = await this.repository.load()
                if ([current.activeRelease, current.fallbackRelease].includes(attempt.releaseId)) throw fail('INTEGRITY')
                if (attempt.phase === 'ACTIVE') attempt = await this.#save(attempt, 'RETIRING')
                const exists = await this.renderer.outputExists(attempt.output)
                if (attempt.phase === 'RELEASE_UNKNOWN') {
                    if (exists || !attempt.retirement) throw fail('INTEGRITY')
                    return
                }
                if (!attempt.retirement) {
                    if (!exists) throw fail('INTEGRITY')
                    const retirement = await this.renderer.prepareRemoval(attempt.output)
                    attempt = await this.#save(attempt, 'RETIRING', {retirement})
                }
                await this.renderer.removeOutput(attempt.output, attempt.retirement)
                attempt = await this.#save(attempt, 'RELEASE_UNKNOWN', {retirement: {directoryRemoved: true,
                    outputDigest: attempt.output.outputDigest, device: attempt.output.device, inode: attempt.output.inode}})
            })
            if (!entered) {this.workingId = null; continue}
            recovered = true
            // 目录与移除凭据已提交，HTTP 在读/退休锁之外；未知结果重放同一退休元组。
            const pin = await this.client.release(attempt.releaseId, {manifestDigest: attempt.pin.manifestDigest,
                expectedVersion: attempt.pin.version, retirementConfirmed: true})
            attempt = await this.#save(attempt, 'RELEASED', {pin, error: null})
            await this.#cleanup(attempt.releaseId)
            this.workingId = null
        }
        return recovered
    }
    async #resume(attempt, provided) {
        this.workingId = attempt.releaseId
        const snapshot = this.#remember(provided || await this.inputs.restoreClone(attempt.input))
        if (['FROZEN', 'PREPARE_UNKNOWN'].includes(attempt.phase)) {
            const manifest = await this.collector.source(snapshot)
            if (manifest.sourceDigest !== attempt.sourceDigest) throw fail('INTEGRITY')
            // 本地校验成功后才记录可能发出的请求；真正 UNKNOWN 的登记不能因源修正被抛弃。
            if (attempt.phase === 'FROZEN') attempt = await this.#save(attempt, 'PREPARE_UNKNOWN', {error: null})
            const pin = await this.client.prepare({protocolVersion: 1, releaseId: attempt.releaseId, ...manifest})
            attempt = await this.#save(attempt, 'HELD', {pin, error: null})
        }
        if (['HELD', 'RENDERING'].includes(attempt.phase)) {
            if (attempt.phase === 'HELD') attempt = await this.#save(attempt, 'RENDERING', {error: null})
            await this.renderer.renderFrozen(snapshot, {onStageReady: async output => {
                attempt = await this.#save(attempt, 'STAGED', {output, error: null})
            }})
            attempt = await this.#save(attempt, 'BUILT')
        }
        if (attempt.phase === 'STAGED') {
            await this.renderer.recoverStage(attempt.output)
            attempt = await this.#save(attempt, 'BUILT', {error: null})
        }
        if (['BUILT', 'VERIFY_UNKNOWN'].includes(attempt.phase)) {
            await this.renderer.verifyOutput(attempt.output)
            const output = await this.collector.output(snapshot, attempt.releaseId)
            if (output.sourceDigest !== attempt.output.outputDigest) throw fail('INTEGRITY')
            if (attempt.phase === 'BUILT') attempt = await this.#save(attempt, 'VERIFY_UNKNOWN', {error: null})
            await this.client.verify(attempt.releaseId, {protocolVersion: 1, manifestDigest: attempt.pin.manifestDigest, ...output})
            await this.renderer.verifyOutput(attempt.output)
            attempt = await this.#save(attempt, 'VERIFIED', {error: null})
        }
        if (attempt.phase !== 'VERIFIED' || this.closed) return
        const latest = await this.#desired()
        if (latest.digest !== attempt.desiredDigest) {
            await this.#save(attempt, 'RETIRING', {error: 'SOURCE_CHANGED'})
            const current = await this.repository.load()
            await this.repository.save({...current, phase: 'WAITING', desiredDigest: latest.digest, error: null, retryAt: 0})
            return
        }
        await this.inputs.verify(snapshot); await this.renderer.verifyOutput(attempt.output)
        if (this.closed) return
        const current = await this.repository.load()
        const previous = current.protection?.releases.find(item => item.releaseId === current.activeRelease)
        const proof = {releaseId: attempt.releaseId, sourceDigest: attempt.sourceDigest, outputDigest: attempt.output.outputDigest,
            ...(this.layoutVersion === 2 ? {layoutVersion: 2} : {})}
        const fallback = previous && {...previous, ...(this.layoutVersion === 2 ? {layoutVersion: previous.layoutVersion ?? layoutOf(current)} : {})}
        await this.repository.save({...current, schemaVersion: this.layoutVersion === 2 ? 3 : 2,
            ...(this.layoutVersion === 2 ? {layoutVersion: 2} : {}), phase: 'CURRENT', activeRelease: attempt.releaseId,
            fallbackRelease: current.activeRelease, publishedDigest: attempt.desiredDigest, desiredDigest: attempt.desiredDigest,
            publishedAt: new Date(this.clock()).toISOString(), checkedAt: new Date(this.clock()).toISOString(),
            error: null, failures: 0, retryAt: 0, failedDigest: null,
            protection: {protocolVersion: 1, environmentId: this.client.environmentId, publisherId: this.client.publisherId,
                releases: [proof, ...(fallback ? [fallback] : [])]}})
        await this.#save(attempt, 'ACTIVE')
        await this.#cleanup(attempt.releaseId)
        this.workingId = null
    }
    async tick() {
        if (this.running || this.closed) return
        this.running = true
        const forced = this.force
        this.force = false
        try {
            let state = await this.repository.load()
            if (state.schemaVersion === 3 && this.layoutVersion !== 2) throw fail('INTEGRITY')
            const pointerRecovered = await this.#recoverPointer(state)
            const retirementRecovered = await this.#retire()
            let pending = (await this.attempts.listUnfinished()).filter(item => !['ACTIVE', 'RETIRING', 'RELEASE_UNKNOWN'].includes(item.phase))
            if (pending.length > 1) throw fail('INTEGRITY')
            if (!forced && this.clock() < state.retryAt) return
            if (pending[0]?.phase === 'FROZEN' && pending[0].error && (await this.#desired()).digest !== pending[0].desiredDigest) {
                await this.#save(pending[0], 'REJECTED', {error: 'SOURCE_CHANGED'})
                await this.#cleanup(pending[0].releaseId)
                pending = []
            }
            const forceBuild = forced && !pointerRecovered && !retirementRecovered
            if (pending.length) await this.#resume(pending[0])
            else {
                const desired = await this.#desired()
                state = {...await this.repository.load(), desiredDigest: desired.digest, checkedAt: new Date(this.clock()).toISOString()}
                if (!forceBuild && desired.digest === state.publishedDigest) {
                    await this.repository.save({...state, phase: 'CURRENT', error: null, failures: 0, retryAt: 0})
                    return
                }
                if (this.closed) return
                await this.repository.save({...state, phase: 'BUILDING', error: null})
                const snapshot = this.#remember(await this.inputs.freeze(desired.posts))
                this.workingId = snapshot.releaseId
                const attempt = await this.attempts.create(newAttempt({releaseId: snapshot.releaseId,
                    environmentId: this.client.environmentId, publisherId: this.client.publisherId,
                    desiredDigest: desired.digest, input: await this.inputs.exportDescriptor(snapshot), layoutVersion: this.layoutVersion}))
                await this.#resume(attempt, snapshot)
            }
            await this.#retire()
        } catch (error) {
            const categories = ['UNKNOWN', 'CONFLICT', 'UNAVAILABLE', 'LIMIT', 'INVALID', 'INTEGRITY', 'BUILD_FAILED', 'SOURCE_CHANGED', 'STOPPED', 'IO']
            const category = this.closed ? 'STOPPED' : categories.includes(error.category) ? error.category : 'IO'
            if (this.workingId) {
                const attempt = await this.attempts.get(this.workingId)
                if (attempt && attempt.phase !== 'RELEASED') {
                    await this.#save(attempt, attempt.phase, {error: category})
                    await this.#cleanup(attempt.releaseId, attempt.input.workName)
                } else if (!attempt) await this.#cleanup(this.workingId)
            }
            // 重读原子指针，尤其不能在 save 已提交但响应未知时用旧 state 覆盖它。
            const current = await this.repository.load(), failures = Math.min((current.failures || 0) + 1, 100000)
            await this.repository.save({...current, phase: 'FAILED', error: userError, failures, failedDigest: current.desiredDigest,
                retryAt: this.clock() + Math.min(300000, 15000 * 2 ** Math.min(failures - 1, 5))})
        } finally {this.running = false; this.workingId = null}
    }
}
module.exports = {ProtectedPublicationCoordinator}
