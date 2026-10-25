'use strict'
// Synthetic integration provider. No production configuration or provider calls.
const http = require('node:http')
const {createHash} = require('node:crypto')
const pins = new Map(), manifests = new Map()
const token = 'synthetic-ingress-container-key-not-production'
const send = (res, data, status = 200) => {res.writeHead(status, {'Content-Type': 'application/json'}); res.end(JSON.stringify({code: status === 200 ? 0 : status, data}))}
http.createServer(async (req, res) => {
    try {
        if (req.url.startsWith('/api/content/article/list')) return send(res, {total: 0, records: []})
        if (req.url.startsWith('/api/')) return send(res, {syntheticBackend: true})
        if (req.headers['x-publication-key'] !== token || req.headers.origin !== undefined) return send(res, null, 401)
        let bytes = 0; const chunks = []
        for await (const chunk of req) {if ((bytes += chunk.length) > 4194304) return send(res, null, 413); chunks.push(chunk)}
        const input = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : null
        if (req.method === 'POST' && req.url === '/internal/media-publications/prepare') {
            const key = input.releaseId, encoded = JSON.stringify(input)
            if (pins.has(key) && manifests.get(key) !== encoded) return send(res, null, 409)
            if (!pins.has(key)) {
                pins.set(key, {protocolVersion: 1, environmentId: 'test', publisherId: 'hexo', releaseId: key,
                    manifestDigest: createHash('sha256').update(encoded).digest('hex'), assetIds: [], status: 'HELD', version: '0'})
                manifests.set(key, encoded)
            }
            return send(res, pins.get(key))
        }
        const match = /^\/internal\/media-publications\/([^/]+)(?:\/(verify|release))?$/.exec(req.url)
        const pin = match && pins.get(match[1])
        if (!pin) return send(res, null, 404)
        if (!match[2] && req.method === 'GET') return send(res, pin)
        if (input.manifestDigest !== pin.manifestDigest) return send(res, null, 409)
        if (match[2] === 'verify' && pin.status === 'HELD') return send(res, {protocolVersion: 1, verified: true})
        if (match[2] === 'release' && input.retirementConfirmed === true && input.expectedVersion === pin.version) {
            const next = {...pin, status: 'RELEASED', version: String(BigInt(pin.version) + 1n)}
            pins.set(pin.releaseId, next); return send(res, next)
        }
        return send(res, null, 409)
    } catch {send(res, null, 503)}
}).listen(8080, '0.0.0.0')
