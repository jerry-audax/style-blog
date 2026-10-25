'use strict'
const {layoutOf, publicPath} = require('../domain/UnifiedPublicationLayout.cjs')
const integrity = () => new Error('Publication serving proof is invalid')
const isVueRoute = value => /^(?:article\/[1-9]\d{0,18}|login|register|profile|ai)\/?$/.test(value)
const isHashedAsset = value => /^assets\/[^/]+-[A-Za-z0-9_-]{8,}\.[A-Za-z0-9]+$/.test(value)

class PublicationStaticRequest {
    constructor({repository, coordinator, directory, gate, openFile}) {
        if (typeof openFile !== 'function') throw new Error('Publication file port is required')
        Object.assign(this, {repository, coordinator, directory, gate, openFile})
    }
    async resolve(urlPath, {signal} = {}) {
        if (publicPath(2, urlPath) === null) return null
        const release = await this.gate.acquireRead({signal})
        let transferred = false
        const requireActive = () => {if (signal?.aborted) throw Object.assign(new Error('Publication request cancelled'), {name: 'AbortError'})}
        try {
            requireActive()
            const state = await this.repository.load()
            requireActive()
            if (!state.activeRelease) throw Object.assign(new Error('Publication not ready'), {code: 'PUBLICATION_NOT_READY'})
            const layout = layoutOf(state), relative = publicPath(layout, urlPath)
            if (relative === null) return null
            let file
            if (layout === 1 || relative.startsWith('blog/')) {
                const proof = state.protection ? await this.coordinator.servingProof(state) : undefined
                requireActive()
                if (state.protection && (!proof || layoutOf(proof) !== layout)) throw integrity()
                file = await this.openFile(this.directory, state.activeRelease, '/' + relative, proof, undefined, {signal})
            } else {
                const requested = isVueRoute(relative) ? 'index.html' : relative
                let id = state.activeRelease
                let entry = await this.coordinator.servingEntry(state, id, requested)
                if (!entry?.sourceFile && isHashedAsset(relative) && state.fallbackRelease
                        && state.protection?.releases.some(item => item.releaseId === state.fallbackRelease && item.layoutVersion === 2)) {
                    id = state.fallbackRelease
                    entry = await this.coordinator.servingEntry(state, id, relative)
                }
                if (!entry?.sourceFile) return null
                requireActive()
                if (layoutOf(entry.proof) !== 2) throw integrity()
                try {file = await this.openFile(this.directory, id, '/' + requested, entry.proof, entry.sourceFile, {signal})}
                catch (error) {if (error.code === 'ENOENT') throw integrity(); throw error}
            }
            transferred = true
            let released = false
            return {file, releaseLease: () => {if (!released) {released = true; release()}}}
        } finally {if (!transferred) release()}
    }
}
module.exports = {PublicationStaticRequest}
