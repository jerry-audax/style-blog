'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {randomUUID} = require('node:crypto')
const {constants} = require('node:fs')
const {validateState} = require('../domain/PublicationState.cjs')
const MAX = 4 * 1024 * 1024
const same = (left, right) => process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right
async function realDirectory(directory) {
    const info = await fs.lstat(directory)
    if (!info.isDirectory() || info.isSymbolicLink() || !same(await fs.realpath(directory), directory)) throw new Error('Unsafe publication state directory')
}
async function createDirectory(directory) {
    const missing = []; let cursor = directory
    while (true) {
        try {await realDirectory(cursor); break} catch (error) {
            if (error.code !== 'ENOENT' || missing.length >= 64 || path.dirname(cursor) === cursor) throw error
            missing.push(cursor); cursor = path.dirname(cursor)
        }
    }
    for (const target of missing.reverse()) {try {await fs.mkdir(target)} catch (error) {if (error.code !== 'EEXIST') throw error}; await realDirectory(target)}
}

class FilePublicationRepository {
    constructor(directory) { this.directory = path.resolve(directory); this.filename = path.join(this.directory, 'publication.json') }
    async load() {
        try {
            await realDirectory(this.directory)
            const before = await fs.lstat(this.filename)
            if (!before.isFile() || before.isSymbolicLink() || before.size > MAX) throw new Error('Unsafe publication pointer')
            const handle = await fs.open(this.filename, constants.O_RDONLY | (constants.O_NOFOLLOW || 0))
            try {
                const info = await handle.stat()
                if (!info.isFile() || info.size > MAX) throw new Error('Publication pointer limit exceeded')
                const buffer = Buffer.alloc(info.size + 1); let size = 0
                while (size < buffer.length) {const read = await handle.read(buffer, size, buffer.length - size, size); if (!read.bytesRead) break; size += read.bytesRead}
                const after = await handle.stat()
                if (size !== info.size || info.mtimeMs !== after.mtimeMs || info.ctimeMs !== after.ctimeMs) throw new Error('Publication pointer changed')
                return validateState(JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(buffer.subarray(0, size))))
            } finally {await handle.close()}
        } catch (error) {
            if (error.code !== 'ENOENT') throw error
            return {phase: 'WAITING', activeRelease: null, publishedDigest: null, desiredDigest: null, publishedAt: null, checkedAt: null, error: null, failures: 0, retryAt: 0}
        }
    }
    async save(state) {
        const value = validateState(state), content = JSON.stringify(value)
        if (Buffer.byteLength(content) > MAX) throw new Error('Publication pointer limit exceeded')
        await createDirectory(this.directory)
        try {
            const target = await fs.lstat(this.filename)
            if (!target.isFile() || target.isSymbolicLink()) throw new Error('Unsafe publication pointer')
        } catch (error) {if (error.code !== 'ENOENT') throw error}
        const temporary = path.join(this.directory, `state-${randomUUID()}.tmp`)
        const handle = await fs.open(temporary, 'wx', 0o600)
        try {await handle.writeFile(content); await handle.sync()} finally {await handle.close()}
        await realDirectory(this.directory)
        await fs.rename(temporary, this.filename)
        let parent
        try {parent = await fs.open(this.directory, 'r'); await parent.sync()} catch (error) {
            if (process.platform !== 'win32' || !['EISDIR', 'EINVAL', 'EPERM'].includes(error.code)) throw error
        } finally {if (parent) await parent.close()}
    }
}
module.exports = {FilePublicationRepository}
