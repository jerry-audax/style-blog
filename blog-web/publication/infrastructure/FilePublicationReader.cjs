'use strict'
const fs = require('node:fs/promises')
const {constants} = require('node:fs')
const path = require('node:path')
const {releaseId} = require('../domain/PublicationPin.cjs')
const {createHash} = require('node:crypto')
const samePath = (left, right) => process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right
const unsafe = () => new Error('Unsafe publication file')
async function real(filename) {
    if (!samePath(await fs.realpath(filename), filename)) throw unsafe()
    const info = await fs.lstat(filename, {bigint: true})
    if (info.isSymbolicLink()) throw unsafe()
    return info
}
async function openPublicationFile(directory, id, relative, expectedRoot, expectedFile, {signal} = {}) {
    const requireActive = () => {if (signal?.aborted) throw Object.assign(new Error('Publication file cancelled'), {name: 'AbortError'})}
    requireActive()
    if (!releaseId(id)) throw unsafe()
    const root = path.resolve(directory, 'releases', id)
    const rootInfo = await real(root)
    if (!rootInfo.isDirectory() || expectedRoot && (String(rootInfo.dev) !== expectedRoot.device || String(rootInfo.ino) !== expectedRoot.inode)) throw unsafe()
    let filename = path.resolve(root, '.' + relative)
    if (filename !== root && !filename.startsWith(root + path.sep)) throw Object.assign(new Error('Not found'), {code: 'ENOENT'})
    let info = await real(filename)
    if (info.isDirectory()) {filename = path.join(filename, 'index.html'); info = await real(filename)}
    if (!info.isFile()) throw Object.assign(new Error('Not found'), {code: 'ENOENT'})
    const handle = await fs.open(filename, constants.O_RDONLY | (constants.O_NOFOLLOW || 0))
    try {
        requireActive()
        const opened = await handle.stat({bigint: true}), after = await real(filename), afterRoot = await real(root)
        if (!opened.isFile() || opened.dev !== info.dev || opened.ino !== info.ino || opened.size !== info.size
                || opened.dev !== after.dev || opened.ino !== after.ino || opened.size !== after.size
                || opened.mtimeNs !== after.mtimeNs || opened.ctimeNs !== after.ctimeNs
                || rootInfo.dev !== afterRoot.dev || rootInfo.ino !== afterRoot.ino) throw unsafe()
        if (expectedFile !== undefined) {
            if (expectedFile?.path !== '.build/app/' + path.relative(root, filename).split(path.sep).join('/')
                    || !Number.isSafeInteger(expectedFile.size) || expectedFile.size < 0 || expectedFile.size > 4 * 1024 * 1024
                    || BigInt(expectedFile.size) !== opened.size || !/^[a-f0-9]{64}$/.test(expectedFile.sha256)) throw unsafe()
            const hash = createHash('sha256'), chunk = Buffer.alloc(Math.min(65536, expectedFile.size + 1))
            let position = 0
            while (position < expectedFile.size) {
                requireActive()
                const read = await handle.read(chunk, 0, Math.min(chunk.length, expectedFile.size - position), position)
                if (!read.bytesRead) throw unsafe()
                hash.update(chunk.subarray(0, read.bytesRead)); position += read.bytesRead
            }
            const final = await handle.stat({bigint: true}), named = await real(filename), finalRoot = await real(root)
            if (hash.digest('hex') !== expectedFile.sha256 || final.size !== opened.size || final.mtimeNs !== opened.mtimeNs
                    || final.ctimeNs !== opened.ctimeNs || named.dev !== opened.dev || named.ino !== opened.ino
                    || finalRoot.dev !== rootInfo.dev || finalRoot.ino !== rootInfo.ino) throw unsafe()
        }
        // 流使用已核对的句柄，避免核对路径后再次按文件名打开。
        requireActive()
        return {filename, size: String(opened.size), handle}
    } catch (error) {await handle.close(); throw error}
}
module.exports = {openPublicationFile}
