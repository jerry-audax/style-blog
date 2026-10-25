'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {randomUUID} = require('node:crypto')
const {validateAttempt, transition, ownedName} = require('../domain/PublicationAttempt.cjs')
const {version} = require('../domain/PublicationPin.cjs')
const LIMIT = 4 * 1024 * 1024
const queues = new Map()
const bad = () => new Error('Publication journal cannot be verified or persisted')
const same = (a, b) => process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b
async function realDirectory(directory) {
    const info = await fs.lstat(directory)
    if (!info.isDirectory() || info.isSymbolicLink() || !same(await fs.realpath(directory), directory)) throw bad()
}
async function ensureDirectory(directory) {
    const root = path.parse(directory).root
    await realDirectory(root)
    let current = root
    for (const part of path.relative(root, directory).split(path.sep)) {
        if (!part) continue
        current = path.join(current, part)
        try {await fs.mkdir(current)} catch (error) {if (error.code !== 'EEXIST') throw error}
        await realDirectory(current)
    }
}
async function exclusive(directory, action) {
    const previous = queues.get(directory) || Promise.resolve()
    let done
    const queued = new Promise(resolve => {done = resolve})
    queues.set(directory, queued)
    await previous
    try {return await action()} finally {done(); if (queues.get(directory) === queued) queues.delete(directory)}
}
async function syncDirectory(directory) {
    let handle
    try {handle = await fs.open(directory, 'r'); await handle.sync()} catch (error) {
        // Windows 不支持目录 fsync；文件本身仍必须 fsync，不能吞掉其他平台/文件错误。
        if (process.platform !== 'win32' || !['EISDIR', 'EPERM', 'EINVAL'].includes(error.code)) throw error
    } finally {if (handle) await handle.close()}
}
class FilePublicationAttemptRepository {
    constructor(directory) {this.directory = path.resolve(directory)}
    #filename(releaseId) {
        if (!ownedName(releaseId, 'release')) throw bad()
        return path.join(this.directory, releaseId + '.json')
    }
    async create(input) {
        const value = validateAttempt(input)
        if (value.recordVersion !== '0' || value.phase !== 'FROZEN' || value.output || value.pin || value.retirement) throw bad()
        return exclusive(this.directory, async () => {
            await ensureDirectory(this.directory)
            if (await this.get(value.releaseId)) throw bad()
            return this.#write(validateAttempt({...value, recordVersion: '1'}))
        })
    }
    async get(releaseId) {
        const filename = this.#filename(releaseId)
        try {
            await realDirectory(this.directory)
            const before = await fs.lstat(filename)
            if (!before.isFile() || before.isSymbolicLink() || before.size > LIMIT || !same(await fs.realpath(filename), filename)) throw bad()
            const handle = await fs.open(filename, 'r')
            try {
                const opened = await handle.stat()
                if (opened.ino !== before.ino || opened.dev !== before.dev || opened.size !== before.size) throw bad()
                const buffer = Buffer.alloc(opened.size + 1); let size = 0
                while (size < buffer.length) {const read = await handle.read(buffer, size, buffer.length - size, size); if (!read.bytesRead) break; size += read.bytesRead}
                const after = await handle.stat()
                if (size !== opened.size || opened.mtimeMs !== after.mtimeMs || opened.ctimeMs !== after.ctimeMs) throw bad()
                const value = validateAttempt(JSON.parse(buffer.subarray(0, size).toString('utf8')))
                if (value.releaseId !== releaseId || value.recordVersion === '0') throw bad()
                return value
            } finally {await handle.close()}
        } catch (error) {if (error.code === 'ENOENT') return null; throw bad()}
    }
    async save(input, expectedVersion) {
        const value = validateAttempt(input)
        if (!version(expectedVersion) || value.recordVersion !== expectedVersion || BigInt(expectedVersion) >= 9223372036854775807n) throw bad()
        return exclusive(this.directory, async () => {
            const current = await this.get(value.releaseId)
            if (!current || current.recordVersion !== expectedVersion
                    || ['schemaVersion', 'layoutVersion', 'environmentId', 'publisherId', 'sourceDigest', 'desiredDigest', 'collectorVersion'].some(key => value[key] !== current[key])
                    || JSON.stringify(value.input) !== JSON.stringify(current.input)
                    || current.output && JSON.stringify(value.output) !== JSON.stringify(current.output)) throw bad()
            transition(current, value.phase, {output: value.output, pin: value.pin, retirement: value.retirement, error: value.error})
            if (current.pin) {
                const stable = pin => pin && JSON.stringify({...pin, version: '', status: ''})
                if (stable(current.pin) !== stable(value.pin)
                        || (value.phase === 'RELEASED' ? BigInt(value.pin.version) !== BigInt(current.pin.version) + 1n
                            : value.pin.version !== current.pin.version)) throw bad()
            }
            return this.#write(validateAttempt({...value, recordVersion: String(BigInt(expectedVersion) + 1n)}))
        })
    }
    async #write(value) {
        const content = JSON.stringify(value)
        if (Buffer.byteLength(content) > LIMIT) throw bad()
        await realDirectory(this.directory)
        const temporary = path.join(this.directory, 'attempt-' + randomUUID() + '.tmp')
        const handle = await fs.open(temporary, 'wx', 0o600)
        try {await handle.writeFile(content); await handle.sync()} finally {await handle.close()}
        await fs.rename(temporary, this.#filename(value.releaseId))
        await syncDirectory(this.directory)
        return value
    }
    async listUnfinished() {
        try {await realDirectory(this.directory)} catch (error) {if (error.code === 'ENOENT') return []; throw error}
        const names = []; let scanned = 0
        for await (const entry of await fs.opendir(this.directory)) {
            if (++scanned > 10000) throw bad()
            if (/^attempt-[a-f0-9-]{36}\.tmp$/.test(entry.name) && entry.isFile()) continue
            if (!entry.isFile() || !entry.name.endsWith('.json') || !ownedName(entry.name.slice(0, -5), 'release')) throw bad()
            names.push(entry.name.slice(0, -5))
        }
        const result = []
        for (const id of names.sort()) {
            const value = await this.get(id)
            if (!value) throw bad()
            if (!['RELEASED', 'REJECTED'].includes(value.phase)) result.push(value)
        }
        return result
    }
}
module.exports = {FilePublicationAttemptRepository}
