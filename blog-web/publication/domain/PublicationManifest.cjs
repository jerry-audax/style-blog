'use strict'
class PublicationManifestError extends Error {
    constructor(reason) {super(`Publication manifest: ${reason}`); this.reason = reason}
}
function createManifest(input) {
    const keys = ['sourceDigest', 'links', 'contents', 'htmlContents']
    if (!input || typeof input !== 'object' || Object.keys(input).length !== keys.length
            || !keys.every(key => Object.hasOwn(input, key)) || typeof input.sourceDigest !== 'string'
            || !/^[a-f0-9]{64}$/.test(input.sourceDigest)
            || !keys.slice(1).every(key => Array.isArray(input[key]))) throw new PublicationManifestError('INVALID')
    if (input.links.length + input.contents.length + input.htmlContents.length > 10000) throw new PublicationManifestError('LIMIT')
    const result = {sourceDigest: input.sourceDigest}; let total = 0
    for (const key of keys.slice(1)) {
        const values = []
        for (const value of input[key]) {
            if (typeof value !== 'string') throw new PublicationManifestError('INVALID')
            const size = Buffer.byteLength(value, 'utf8')
            if (size > 1048576 || (total += size) > 4 * 1024 * 1024) throw new PublicationManifestError('LIMIT')
            values.push(value)
        }
        result[key] = Object.freeze(values)
    }
    return Object.freeze(result)
}
module.exports = {createManifest, PublicationManifestError}
