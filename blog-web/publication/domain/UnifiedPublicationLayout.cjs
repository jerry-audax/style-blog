'use strict'
function layoutOf(record) {
    if (!record || typeof record !== 'object' || Array.isArray(record)) throw new Error('Invalid publication layout')
    if (record.schemaVersion !== undefined && ![2, 3].includes(record.schemaVersion)
            || record.schemaVersion === 3 && record.layoutVersion !== 2
            || record.schemaVersion === 2 && record.layoutVersion !== undefined
            || record.layoutVersion !== undefined && record.layoutVersion !== 2) throw new Error('Invalid publication layout')
    return record.layoutVersion === 2 ? 2 : 1
}
function publicPath(layoutVersion, urlPath) {
    if (![1, 2].includes(layoutVersion)) throw new Error('Invalid publication layout')
    if (typeof urlPath !== 'string' || !urlPath.startsWith('/') || /%2f|%5c/i.test(urlPath)) return null
    let decoded
    try {decoded = decodeURIComponent(urlPath)} catch {return null}
    if (/[\\\u0000-\u001f\u007f?#]/.test(decoded) || /%[a-f0-9]{2}/i.test(decoded)) return null
    const parts = decoded.slice(1).split('/')
    if (parts.some(part => part.startsWith('.') || part === '' && parts.indexOf(part) < parts.length - 1)
            || ['api', 'internal', 'uploads'].includes(parts[0])) return null
    if (layoutVersion === 1) return decoded.startsWith('/blog/') ? decoded.slice(6) : null
    return decoded.slice(1)
}
function assertUnifiedBudget(entries) {
    let count = 0, total = 0
    const names = new Set()
    for (const entry of entries) {
        if (++count > 50000 || typeof entry?.path !== 'string' || !entry.path || entry.path.length > 4096
                || /[\\:\u0000-\u001f]/.test(entry.path) || entry.path.split('/').some(part => !part || part.startsWith('.'))
                || entry.path.split('/').length > 64 || names.has(entry.path)
                || !Number.isSafeInteger(entry.size) || entry.size < 0 || entry.size > 16 * 1024 * 1024
                || (total += entry.size) > 256 * 1024 * 1024) throw new Error('Unified publication output limit exceeded')
        names.add(entry.path)
    }
}
module.exports = {layoutOf, publicPath, assertUnifiedBudget}
