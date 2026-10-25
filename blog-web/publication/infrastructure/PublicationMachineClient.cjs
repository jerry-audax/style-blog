'use strict'
const {validatePin, PublicationProtocolError, failure, digest, releaseId, identity, version, exactKeys} = require('../domain/PublicationPin.cjs')
const BODY_LIMIT = 4 * 1024 * 1024
const ROOT = '/internal/media-publications'

function validateManifest(input, fields) {
    if (!exactKeys(input, fields) || input.protocolVersion !== 1 || !digest(input.sourceDigest)
        || !['links', 'contents', 'htmlContents'].every(key => Array.isArray(input[key]))) throw failure('INVALID')
    if (input.links.length + input.contents.length + input.htmlContents.length > 10000) throw failure('LIMIT')
    let total = 0
    for (const key of ['links', 'contents', 'htmlContents']) for (const value of input[key]) {
        if (typeof value !== 'string') throw failure('INVALID')
        const size = Buffer.byteLength(value, 'utf8')
        if (size > 1048576 || (total += size) > BODY_LIMIT) throw failure('LIMIT')
    }
}

// Check exact JSON escaping costs before allocating the encoded body. Never call a caller's toJSON.
function boundedJson(input) {
    let budget = 0
    const add = size => {budget += size; if (budget > BODY_LIMIT) throw failure('LIMIT')}
    const string = value => {
        add(2)
        for (let i = 0; i < value.length; i++) {
            const code = value.charCodeAt(i)
            if (code === 34 || code === 92 || code === 8 || code === 9 || code === 10 || code === 12 || code === 13) add(2)
            else if (code < 32) add(6)
            else if (code >= 0xd800 && code <= 0xdbff && i + 1 < value.length && value.charCodeAt(i + 1) >= 0xdc00 && value.charCodeAt(i + 1) <= 0xdfff) {add(4); i++}
            else if (code >= 0xd800 && code <= 0xdfff) add(6)
            else add(code < 128 ? 1 : code < 2048 ? 2 : 3)
        }
        return JSON.stringify(value)
    }
    const encode = value => {
        if (typeof value === 'string') return string(value)
        if (value === 1) {add(1); return '1'}
        if (value === true) {add(4); return 'true'}
        if (Array.isArray(value)) {
            add(2 + Math.max(0, value.length - 1))
            const entries = []; for (let i = 0; i < value.length; i++) entries.push(encode(value[i]))
            return '[' + entries.join(',') + ']'
        }
        if (!value || typeof value !== 'object') throw failure('INVALID')
        const keys = Object.keys(value)
        add(2 + Math.max(0, keys.length - 1) + keys.length)
        return '{' + keys.map(key => string(key) + ':' + encode(value[key])).join(',') + '}'
    }
    try {return encode(input)} catch (error) {if (error instanceof PublicationProtocolError) throw error; throw failure('INVALID')}
}

class PublicationMachineClient {
    constructor({baseUrl, tokenSupplier, environmentId, publisherId, fetcher = fetch, timeoutMs = 10000, trustedHttpOrigin}) {
        let base
        try {base = new URL(baseUrl)} catch {throw failure('INVALID')}
        if (trustedHttpOrigin !== undefined) {
            if (typeof trustedHttpOrigin !== 'string') throw failure('INVALID')
            let trusted
            try {trusted = new URL(trustedHttpOrigin)} catch {throw failure('INVALID')}
            // 内网明文连接只允许显式配置的同一个origin，不能用通配或附加路径扩大密钥发送范围。
            if (trusted.protocol !== 'http:' || trusted.username || trusted.password || trusted.search || trusted.hash
                    || trusted.pathname !== '/' || trusted.origin !== base.origin) throw failure('INVALID')
        }
        if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash
            || base.pathname !== '/' || base.protocol === 'http:' && !['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname) && trustedHttpOrigin === undefined
            || !identity(environmentId) || !identity(publisherId) || typeof tokenSupplier !== 'function' || typeof fetcher !== 'function'
            || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 10000) throw failure('INVALID')
        this.base = base.origin; this.tokenSupplier = tokenSupplier; this.environmentId = environmentId
        this.publisherId = publisherId; this.fetcher = fetcher; this.timeoutMs = timeoutMs
    }
    expected(id, extra = {}) {return {environmentId: this.environmentId, publisherId: this.publisherId, releaseId: id, ...extra}}
    async prepare(input) {
        if (!releaseId(input?.releaseId)) throw failure('INVALID')
        validateManifest(input, ['protocolVersion', 'releaseId', 'sourceDigest', 'links', 'contents', 'htmlContents'])
        return validatePin(await this.request('POST', '/prepare', input), this.expected(input.releaseId, {status: 'HELD'}))
    }
    async get(id) {
        if (!releaseId(id)) throw failure('INVALID')
        return validatePin(await this.request('GET', '/' + id), this.expected(id))
    }
    async verify(id, input) {
        if (!releaseId(id) || !digest(input?.manifestDigest)) throw failure('INVALID')
        validateManifest(input, ['protocolVersion', 'manifestDigest', 'sourceDigest', 'links', 'contents', 'htmlContents'])
        const data = await this.request('POST', '/' + id + '/verify', input)
        if (!exactKeys(data, ['protocolVersion', 'verified']) || data.protocolVersion !== 1 || data.verified !== true) throw failure('UNAVAILABLE')
    }
    async release(id, input) {
        if (!releaseId(id) || !exactKeys(input, ['manifestDigest', 'expectedVersion', 'retirementConfirmed'])
            || !digest(input.manifestDigest) || !version(input.expectedVersion) || input.retirementConfirmed !== true) throw failure('INVALID')
        const pin = validatePin(await this.request('POST', '/' + id + '/release', {protocolVersion: 1, ...input}),
            this.expected(id, {status: 'RELEASED', manifestDigest: input.manifestDigest}))
        if (BigInt(pin.version) !== BigInt(input.expectedVersion) + 1n) throw failure('UNAVAILABLE')
        return pin
    }
    async request(method, route, input) {
        const body = input === undefined ? undefined : boundedJson(input)
        let token
        try {token = this.tokenSupplier()} catch {throw failure('UNAUTHORIZED')}
        if (typeof token !== 'string' || token.length < 32 || /[\r\n]/.test(token)) throw failure('UNAUTHORIZED')
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), this.timeoutMs)
        let reader
        try {
            const response = await this.fetcher(this.base + ROOT + route, {method, redirect: 'error', signal: controller.signal,
                headers: {'X-Publication-Key': token, Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, body})
            if (response.status !== 200) throw failure(({400: 'INVALID', 401: 'UNAUTHORIZED', 403: 'UNAUTHORIZED', 404: 'CONFLICT', 409: 'CONFLICT',
                413: 'LIMIT', 415: 'INVALID', 503: 'UNAVAILABLE'})[response.status] || 'UNAVAILABLE')
            if (!/^application\/json(?:\s*;|\s*$)/i.test(response.headers.get('content-type') || '')) throw failure('UNAVAILABLE')
            const length = response.headers.get('content-length')
            if (length !== null && (!/^[0-9]+$/.test(length) || BigInt(length) > BigInt(BODY_LIMIT))) throw failure('LIMIT')
            if (!response.body) throw failure('UNAVAILABLE')
            reader = response.body.getReader()
            const chunks = []; let size = 0
            while (true) {
                const {done, value} = await reader.read()
                if (done) break
                size += value.byteLength
                if (size > BODY_LIMIT) throw failure('LIMIT')
                chunks.push(value)
            }
            let result
            try {result = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(Buffer.concat(chunks, size)))} catch {throw failure('UNAVAILABLE')}
            if (!result || result.code !== 0 || !Object.hasOwn(result, 'data')) throw failure('UNAVAILABLE')
            return result.data
        } catch (error) {
            if (error instanceof PublicationProtocolError) throw error
            throw failure('UNKNOWN')
        } finally {
            clearTimeout(timer); controller.abort()
            if (reader) {try {await reader.cancel()} catch {} finally {reader.releaseLock()}}
        }
    }
}
module.exports = {PublicationMachineClient}
