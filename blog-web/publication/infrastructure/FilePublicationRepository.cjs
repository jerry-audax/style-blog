'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {randomUUID} = require('node:crypto')

class FilePublicationRepository {
    constructor(directory) { this.directory = path.resolve(directory); this.filename = path.join(this.directory, 'publication.json') }
    async load() {
        try {
            const state = JSON.parse(await fs.readFile(this.filename, 'utf8'))
            if (state.activeRelease && !/^[a-zA-Z0-9-]+$/.test(state.activeRelease)) throw new Error('Invalid active release')
            return state
        } catch (error) {
            if (error.code !== 'ENOENT') throw error
            return {phase: 'WAITING', activeRelease: null, publishedDigest: null, desiredDigest: null, publishedAt: null, checkedAt: null, error: null, failures: 0, retryAt: 0}
        }
    }
    async save(state) {
        await fs.mkdir(this.directory, {recursive: true})
        if ((await fs.lstat(this.directory)).isSymbolicLink()) throw new Error('Publication state cannot be a symlink')
        const temporary = path.join(this.directory, `state-${randomUUID()}.tmp`)
        await fs.writeFile(temporary, JSON.stringify(state), {flag: 'wx', mode: 0o600})
        await fs.rename(temporary, this.filename)
    }
}
module.exports = {FilePublicationRepository}
