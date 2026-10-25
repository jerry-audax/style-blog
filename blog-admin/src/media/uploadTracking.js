const prefix = 'blog.media.uploads.v1.'
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
function key(userId) {
    const id = typeof userId === 'number' && Number.isSafeInteger(userId) && userId > 0 ? String(userId) : userId
    return typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n ? prefix + id : null
}
function hint(raw) {
    if (!raw || !uuid.test(raw.operationId)) return null
    if (raw.targetType === 'MUSIC_TRACK') {
        if (typeof raw.targetId !== 'string' || !raw.targetId.trim() || raw.targetId.length > 64 || !['AUDIO','COVER','LYRICS'].includes(raw.slot)) return null
    } else if (raw.targetType === 'SURPRISE_CREATE') {
        if (raw.slot !== 'VIDEO' || raw.targetId != null && (typeof raw.targetId !== 'string' || raw.targetId.length > 64)) return null
    } else if (raw.targetType === 'IMAGE_CREATE') {
        if (!['IMAGE','AVATAR'].includes(raw.slot) || raw.targetId != null && !uuid.test(raw.targetId)) return null
    } else return null
    if (typeof raw.createdAt !== 'string' || raw.createdAt.length > 40 || !Number.isFinite(Date.parse(raw.createdAt))) return null
    return {operationId: raw.operationId, targetType: raw.targetType, targetId: raw.targetId ?? null, slot: raw.slot, createdAt: raw.createdAt}
}
export function readTrackedUploads(userId) {
    try {
        const namespace = key(userId)
        if (!namespace) return []
        const text = sessionStorage.getItem(namespace)
        if (!text || text.length > 20000) return []
        const rows = JSON.parse(text)
        if (!Array.isArray(rows)) return []
        const seen = new Set()
        return rows.slice(0, 20).map(hint).filter(row => row && !seen.has(row.operationId) && seen.add(row.operationId))
    } catch { return [] }
}
export function rememberUpload(userId, raw) {
    try {
        const namespace = key(userId), row = hint(raw)
        if (namespace && row) sessionStorage.setItem(namespace, JSON.stringify([row, ...readTrackedUploads(userId).filter(item => item.operationId !== row.operationId)].slice(0, 20)))
    } catch { /* Hints are optional. Server-owned history remains authoritative. */ }
}
export function forgetActiveUploads(userId) {
    try { const namespace = key(userId); if (namespace) sessionStorage.removeItem(namespace) } catch { /* Storage may be blocked. */ }
}
export function forgetUpload(userId, operationId) {
    try {
        const namespace = key(userId)
        if (!namespace || !uuid.test(operationId)) return
        const remaining = readTrackedUploads(userId).filter(row => row.operationId !== operationId)
        if (remaining.length) sessionStorage.setItem(namespace, JSON.stringify(remaining))
        else sessionStorage.removeItem(namespace)
    } catch { /* A missing server record never causes an automatic upload. */ }
}
