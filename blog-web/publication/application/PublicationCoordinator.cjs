'use strict'
const {snapshotDigest} = require('../domain/Snapshot.cjs')

class PublicationCoordinator {
    constructor({repository, source, renderer, clock = Date.now}) {
        this.repository = repository; this.source = source; this.renderer = renderer; this.clock = clock
        this.running = false; this.force = false
    }
    async status() {
        const state = await this.repository.load()
        return {configured: true, phase: state.phase, publishedDigest: state.publishedDigest, desiredDigest: state.desiredDigest,
            publishedAt: state.publishedAt, checkedAt: state.checkedAt, error: state.error}
    }
    async retry() {
        this.force = true
        if (!this.running) {
            const state = await this.repository.load()
            await this.repository.save({...state, phase: 'WAITING', retryAt: 0, error: null})
        }
        return this.status()
    }
    async tick() {
        if (this.running) return
        this.running = true
        let state
        try {
            state = await this.repository.load()
            const posts = await this.source.fetchPosts()
            const digest = snapshotDigest(posts)
            const force = this.force
            state = {...state, desiredDigest: digest, checkedAt: new Date(this.clock()).toISOString()}
            if (!force && digest === state.publishedDigest) {
                await this.repository.save({...state, phase: 'CURRENT', error: null, failures: 0, retryAt: 0})
                return
            }
            if (!force && digest === state.failedDigest && this.clock() < state.retryAt) return
            this.force = false
            state = {...state, phase: 'BUILDING', error: null}
            await this.repository.save(state)
            const release = await this.renderer.render(posts)
            const latestDigest = snapshotDigest(await this.source.fetchPosts())
            if (latestDigest !== digest) {
                await this.repository.save({...state, phase: 'WAITING', desiredDigest: latestDigest, retryAt: 0})
                return
            }
            // Persisting this pointer is the only publication commit. A partial
            // build or stale result never replaces the previously served tree.
            await this.repository.save({...state, phase: 'CURRENT', activeRelease: release, publishedDigest: digest,
                publishedAt: new Date(this.clock()).toISOString(), error: null, failures: 0, retryAt: 0, failedDigest: null})
        } catch {
            if (!state) throw new Error('Publication state cannot be loaded')
            if (state) {
                const failures = (state.failures || 0) + 1
                await this.repository.save({...state, phase: 'FAILED', failures, failedDigest: state.desiredDigest,
                    retryAt: this.clock() + Math.min(300000, 15000 * 2 ** Math.min(failures - 1, 5)),
                    error: '公开内容读取或页面生成失败，仍展示上一版；稍后自动重试，也可手动重试。'})
            }
        } finally { this.running = false }
    }
}
module.exports = {PublicationCoordinator}
