'use strict'
const {createHash} = require('node:crypto')
const {identity, digest, version, exactKeys, validatePin} = require('./PublicationPin.cjs')
const {layoutOf} = require('./UnifiedPublicationLayout.cjs')
const PHASES = ['FROZEN', 'PREPARE_UNKNOWN', 'HELD', 'RENDERING', 'STAGED', 'BUILT', 'VERIFY_UNKNOWN', 'VERIFIED', 'ACTIVE', 'RETIRING', 'RELEASE_UNKNOWN', 'RELEASED', 'REJECTED']
const errors = new Set(['UNKNOWN', 'CONFLICT', 'UNAVAILABLE', 'LIMIT', 'INVALID', 'INTEGRITY', 'BUILD_FAILED', 'SOURCE_CHANGED', 'STOPPED', 'IO'])
const bad = () => new Error('Invalid publication attempt')
const ownedName = (value, prefix) => typeof value === 'string' && new RegExp('^' + prefix + '-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$').test(value)
const number = value => typeof value === 'string' && /^(0|[1-9][0-9]{0,39})$/.test(value)
function safeRelative(value) {
    return typeof value === 'string' && value.length <= 4096 && value.length > 0 && !/[\\:\u0000-\u001f]/.test(value)
        && value.split('/').every(part => part && part !== '.' && part !== '..')
}
function validateDescriptor(value) {
    if (!exactKeys(value, ['schemaVersion', 'releaseId', 'sourceDigest', 'workName', 'workDevice', 'workInode', 'files', 'initialInventory'])
            || value.schemaVersion !== 1 || !ownedName(value.releaseId, 'release') || !ownedName(value.workName, 'work')
            || !number(value.workDevice) || !number(value.workInode) || !digest(value.sourceDigest)
            || !Array.isArray(value.files) || !value.files.length || value.files.length > 10000
            || !Array.isArray(value.initialInventory) || value.initialInventory.length > 20000) throw bad()
    let bytes = 0, namesSize = 0
    const files = [], names = new Set(), inventory = []
    for (const entry of value.files) {
        if (!exactKeys(entry, ['path', 'sha256', 'size']) || !safeRelative(entry.path) || !digest(entry.sha256)
                || !Number.isSafeInteger(entry.size) || entry.size < 0 || entry.size > 4 * 1024 * 1024
                || (bytes += entry.size) > 64 * 1024 * 1024 || names.has(entry.path)
                || (namesSize += Buffer.byteLength(entry.path)) > 2 * 1024 * 1024) throw bad()
        names.add(entry.path); files.push(Object.freeze({path: entry.path, sha256: entry.sha256, size: entry.size}))
    }
    const sorted = [...files].sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)))
    if (files.some((entry, index) => entry.path !== sorted[index].path)
            || createHash('sha256').update(JSON.stringify({protocolVersion: 1, files})).digest('hex') !== value.sourceDigest) throw bad()
    const tree = new Map()
    for (const entry of value.initialInventory) {
        if (!exactKeys(entry, ['path', 'type']) || !safeRelative(entry.path) || !['file', 'directory'].includes(entry.type)
                || tree.has(entry.path) || (namesSize += Buffer.byteLength(entry.path)) > 2 * 1024 * 1024) throw bad()
        tree.set(entry.path, entry.type); inventory.push(Object.freeze({path: entry.path, type: entry.type}))
    }
    if ([...tree.values()].filter(type => type === 'file').length !== files.length || files.some(file => tree.get(file.path) !== 'file')) throw bad()
    return Object.freeze({...value, files: Object.freeze(files), initialInventory: Object.freeze(inventory)})
}
function validateStage(value, releaseId) {
    if (!exactKeys(value, ['releaseId', 'stageName', 'outputDigest', 'device', 'inode', ...(value?.layoutVersion !== undefined ? ['layoutVersion'] : [])])
            || !ownedName(value.releaseId, 'release') || releaseId !== undefined && value.releaseId !== releaseId
            || !ownedName(value.stageName, 'stage') || !digest(value.outputDigest) || !number(value.device) || !number(value.inode)) throw bad()
    layoutOf(value)
    return Object.freeze({...value})
}
function validateRetirement(value, output) {
    const removed = value?.directoryRemoved === true
    if (!exactKeys(value, ['directoryRemoved', 'outputDigest', 'device', 'inode', ...(!removed ? ['entries'] : [])])
            || !output || ![false, true].includes(value.directoryRemoved)
            || ['outputDigest', 'device', 'inode'].some(key => value[key] !== output[key])) throw bad()
    if (removed) return Object.freeze({...value})
    if (!Array.isArray(value.entries) || value.entries.length > 100000) throw bad()
    let total = 0, namesBytes = 0, files = 0, previous = null
    const entries = [], types = new Map(), hash = createHash('sha256')
    for (const entry of value.entries) {
        if (!exactKeys(entry, ['path', 'type', 'sha256', 'size', 'device', 'inode']) || !safeRelative(entry.path)
                || !['file', 'directory'].includes(entry.type) || !number(entry.device) || !number(entry.inode)
                || previous !== null && Buffer.compare(Buffer.from(previous), Buffer.from(entry.path)) >= 0
                || (namesBytes += Buffer.byteLength(entry.path)) > 2 * 1024 * 1024) throw bad()
        if (entry.type === 'file') {
            if (!digest(entry.sha256) || !Number.isSafeInteger(entry.size) || entry.size < 0 || entry.size > 16 * 1024 * 1024
                    || (total += entry.size) > 256 * 1024 * 1024 || ++files > 50000) throw bad()
            hash.update(JSON.stringify([entry.path, entry.size, entry.sha256]) + '\n')
        } else if (entry.sha256 !== null || entry.size !== 0) throw bad()
        previous = entry.path; types.set(entry.path, entry.type); entries.push(Object.freeze({...entry}))
    }
    for (const entry of entries) {
        const parts = entry.path.split('/')
        for (let index = 1; index < parts.length; index++) if (types.get(parts.slice(0, index).join('/')) !== 'directory') throw bad()
    }
    if (hash.digest('hex') !== output.outputDigest) throw bad()
    return Object.freeze({...value, entries: Object.freeze(entries)})
}
function validateAttempt(value) {
    const unified = value?.schemaVersion === 3
    if (!exactKeys(value, ['schemaVersion', 'recordVersion', 'releaseId', 'environmentId', 'publisherId', 'collectorVersion',
        'phase', 'sourceDigest', 'desiredDigest', 'input', 'output', 'pin', 'error', 'retirement', ...(unified ? ['layoutVersion'] : [])]) || ![2, 3].includes(value.schemaVersion)
            || !version(value.recordVersion) || !ownedName(value.releaseId, 'release') || !identity(value.environmentId)
            || !identity(value.publisherId) || value.collectorVersion !== (unified ? 2 : 1) || !PHASES.includes(value.phase)
            || !digest(value.desiredDigest) || value.error !== null && !errors.has(value.error)) throw bad()
    const input = validateDescriptor(value.input)
    if (input.releaseId !== value.releaseId || input.sourceDigest !== value.sourceDigest) throw bad()
    const output = value.output === null ? null : validateStage(value.output, value.releaseId)
    if (output && layoutOf(output) !== layoutOf(value)) throw bad()
    layoutOf(value)
    const pin = value.pin === null ? null : validatePin(value.pin, {environmentId: value.environmentId, publisherId: value.publisherId,
        releaseId: value.releaseId, status: value.phase === 'RELEASED' ? 'RELEASED' : 'HELD'})
    if (value.phase === 'REJECTED' ? pin !== null || output !== null || value.retirement !== null
        : PHASES.indexOf(value.phase) >= PHASES.indexOf('HELD') && !pin
            || PHASES.indexOf(value.phase) >= PHASES.indexOf('STAGED') && !output) throw bad()
    const retirement = value.retirement === null ? null : validateRetirement(value.retirement, output)
    if (retirement?.directoryRemoved === false && value.phase !== 'RETIRING'
            || ['RELEASE_UNKNOWN', 'RELEASED'].includes(value.phase) && retirement?.directoryRemoved !== true) throw bad()
    return Object.freeze({...value, input, output, pin, retirement})
}
function newAttempt({releaseId, environmentId, publisherId, input, desiredDigest = input.sourceDigest, layoutVersion = 1}) {
    if (![1, 2].includes(layoutVersion)) throw bad()
    return validateAttempt({schemaVersion: layoutVersion === 2 ? 3 : 2, ...(layoutVersion === 2 ? {layoutVersion} : {}),
        recordVersion: '0', releaseId, environmentId, publisherId, collectorVersion: layoutVersion === 2 ? 2 : 1,
        phase: 'FROZEN', sourceDigest: input.sourceDigest, desiredDigest, input, output: null, pin: null, error: null, retirement: null})
}
function transition(input, phase, changes = {}) {
    const current = validateAttempt(input), from = PHASES.indexOf(current.phase), to = PHASES.indexOf(phase)
    if (phase === 'REJECTED' ? !['FROZEN', 'REJECTED'].includes(current.phase)
        : current.phase === 'REJECTED' || !(to === from + 1 || to === from || phase === 'RETIRING' && ['STAGED', 'BUILT', 'VERIFIED'].includes(current.phase))) throw bad()
    if (Object.keys(changes).some(key => !['output', 'pin', 'error', 'retirement'].includes(key))) throw bad()
    if (current.retirement && Object.hasOwn(changes, 'retirement')
            && changes.retirement?.directoryRemoved !== true && JSON.stringify(changes.retirement) !== JSON.stringify(current.retirement)) throw bad()
    return validateAttempt({...current, ...changes, phase})
}
module.exports = {newAttempt, validateAttempt, transition, validateDescriptor, validateStage, validateRetirement, ownedName, safeRelative}
