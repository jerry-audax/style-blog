'use strict'
const {digest, identity, releaseId, exactKeys} = require('./PublicationPin.cjs')
const {ownedName} = require('./PublicationAttempt.cjs')
const bad = () => new Error('Invalid publication pointer')
const baseKeys = ['phase', 'activeRelease', 'publishedDigest', 'desiredDigest', 'publishedAt', 'checkedAt', 'error', 'failures', 'retryAt']
const nullableDigest = value => value === null || digest(value)
const timestamp = value => value === null || typeof value === 'string' && value.length <= 32 && Number.isFinite(Date.parse(value))
function validateState(value) {
    const unified = value?.schemaVersion === 3
    const protectedState = unified || value?.schemaVersion === 2
    const allowed = [...baseKeys, 'failedDigest', ...(protectedState ? ['schemaVersion', 'fallbackRelease', 'protection'] : []), ...(unified ? ['layoutVersion'] : [])]
    if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !allowed.includes(key))
            || !baseKeys.every(key => Object.hasOwn(value, key)) || !['WAITING', 'BUILDING', 'CURRENT', 'FAILED'].includes(value.phase)
            || value.activeRelease !== null && !releaseId(value.activeRelease) || !nullableDigest(value.publishedDigest)
            || !nullableDigest(value.desiredDigest) || value.failedDigest !== undefined && !nullableDigest(value.failedDigest)
            || !timestamp(value.publishedAt) || !timestamp(value.checkedAt)
            || value.error !== null && (typeof value.error !== 'string' || value.error.length > 2048 || /[\u0000-\u001f]/.test(value.error))
            || !Number.isSafeInteger(value.failures) || value.failures < 0 || value.failures > 100000
            || !Number.isSafeInteger(value.retryAt) || value.retryAt < 0) throw bad()
    if (!protectedState) return Object.freeze({...value})
    if (unified && value.layoutVersion !== 2) throw bad()
    const protection = value.protection
    if (!Object.hasOwn(value, 'fallbackRelease') || value.activeRelease === null || !digest(value.publishedDigest)
            || value.fallbackRelease !== null && !releaseId(value.fallbackRelease) || value.activeRelease === value.fallbackRelease
            || !exactKeys(protection, ['protocolVersion', 'environmentId', 'publisherId', 'releases'])
            || protection.protocolVersion !== 1 || !identity(protection.environmentId) || !identity(protection.publisherId)
            || !Array.isArray(protection.releases) || protection.releases.length < 1 || protection.releases.length > 2) throw bad()
    const names = new Set(), releases = []
    for (const entry of protection.releases) {
        if (!exactKeys(entry, ['releaseId', 'sourceDigest', 'outputDigest', ...(unified ? ['layoutVersion'] : [])]) || !ownedName(entry.releaseId, 'release')
                || unified && (![1, 2].includes(entry.layoutVersion) || entry.releaseId === value.activeRelease && entry.layoutVersion !== 2)
                || !digest(entry.sourceDigest) || !digest(entry.outputDigest) || names.has(entry.releaseId)
                || ![value.activeRelease, value.fallbackRelease].includes(entry.releaseId)) throw bad()
        names.add(entry.releaseId); releases.push(Object.freeze({...entry}))
    }
    if (!names.has(value.activeRelease)) throw bad()
    return Object.freeze({...value, protection: Object.freeze({...protection, releases: Object.freeze(releases)})})
}
module.exports = {validateState}
