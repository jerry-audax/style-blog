'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {createHash} = require('node:crypto')
const {readPublicationTree} = require('./PublicationManifestCollector.cjs')
const {validateRetirement} = require('../domain/PublicationAttempt.cjs')
const bad = () => new Error('Publication retirement ownership changed')
const samePath = (left, right) => process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right
async function rootIdentity(root, output) {
    if (!samePath(await fs.realpath(root), root)) throw bad()
    const info = await fs.lstat(root, {bigint: true})
    if (!info.isDirectory() || info.isSymbolicLink() || String(info.dev) !== output.device || String(info.ino) !== output.inode) throw bad()
}
async function inventory(root, output) {
    await rootIdentity(root, output)
    const files = await readPublicationTree(root), entries = []
    async function walk(directory, depth) {
        if (depth > 64) throw bad()
        for (const name of (await fs.readdir(directory)).sort()) {
            const filename = path.join(directory, name)
            if (entries.length >= 100000 || !samePath(await fs.realpath(filename), filename)) throw bad()
            const info = await fs.lstat(filename, {bigint: true})
            if (info.isSymbolicLink() || !info.isDirectory() && !info.isFile()) throw bad()
            const relative = path.relative(root, filename).split(path.sep).join('/')
            const bytes = info.isFile() ? files.get(relative) : null
            if (info.isFile() && (!bytes || BigInt(bytes.length) !== info.size)) throw bad()
            entries.push({path: relative, type: info.isDirectory() ? 'directory' : 'file',
                sha256: bytes === null ? null : createHash('sha256').update(bytes).digest('hex'), size: bytes === null ? 0 : bytes.length,
                device: String(info.dev), inode: String(info.ino)})
            if (info.isDirectory()) await walk(filename, depth + 1)
        }
    }
    await walk(root, 0); await rootIdentity(root, output)
    if (entries.filter(entry => entry.type === 'file').length !== files.size) throw bad()
    return entries.sort((left, right) => Buffer.compare(Buffer.from(left.path), Buffer.from(right.path)))
}
async function captureRemoval(root, output) {
    return validateRetirement({directoryRemoved: false, outputDigest: output.outputDigest, device: output.device,
        inode: output.inode, entries: await inventory(root, output)}, output)
}
async function resumeRemoval(root, output, input) {
    const intent = validateRetirement(input, output)
    if (intent.directoryRemoved) throw bad()
    try {await rootIdentity(root, output)} catch (error) {if (error.code === 'ENOENT') return; throw error}
    const expected = new Map(intent.entries.map(entry => [entry.path, entry]))
    const remaining = await inventory(root, output)
    for (const entry of remaining) {
        const original = expected.get(entry.path)
        if (!original || ['type', 'sha256', 'size', 'device', 'inode'].some(key => entry[key] !== original[key])) throw bad()
    }
    // 允许已移除的登记项；剩余项必须仍是原文件，新增/替换内容一律保留并失败。
    remaining.sort((left, right) => right.path.split('/').length - left.path.split('/').length)
    for (const entry of remaining) {
        const filename = path.join(root, entry.path)
        const current = await fs.lstat(filename, {bigint: true})
        if (current.isSymbolicLink() || String(current.dev) !== entry.device || String(current.ino) !== entry.inode) throw bad()
        if (entry.type === 'file') await fs.unlink(filename)
        else await fs.rmdir(filename)
    }
    await rootIdentity(root, output)
    await fs.rmdir(root)
}
module.exports = {captureRemoval, resumeRemoval}
