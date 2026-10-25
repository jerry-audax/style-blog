'use strict'
class PublicationProtocolError extends Error {
    constructor(category) { super(`Publication protocol: ${category}`); this.category = category }
}
const failure = category => new PublicationProtocolError(category)
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const releaseId = value => typeof value === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(value)
const identity = value => typeof value === 'string' && /^[a-z][a-z0-9-]{0,31}$/.test(value)
const version = value => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n
function exactKeys(value, keys) {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
        && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
}
function validatePin(value, expected) {
    if (!exactKeys(value, ['protocolVersion', 'environmentId', 'publisherId', 'releaseId', 'manifestDigest', 'assetIds', 'status', 'version'])
        || value.protocolVersion !== 1 || !identity(value.environmentId) || !identity(value.publisherId) || !releaseId(value.releaseId)
        || !digest(value.manifestDigest) || !version(value.version) || !['HELD', 'RELEASED'].includes(value.status)
        || !Array.isArray(value.assetIds) || value.assetIds.length > 10000
        || value.assetIds.some(id => typeof id !== 'string' || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id))
        || value.assetIds.some((id, index) => index > 0 && value.assetIds[index - 1] >= id)
        || !expected || ['environmentId', 'publisherId', 'releaseId', 'status', 'manifestDigest'].some(key =>
            expected[key] !== undefined && expected[key] !== value[key])) throw failure('UNAVAILABLE')
    return Object.freeze({...value, assetIds: Object.freeze([...value.assetIds])})
}
module.exports = {validatePin, PublicationProtocolError, failure, digest, releaseId, identity, version, exactKeys}
