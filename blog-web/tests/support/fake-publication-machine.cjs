const http = require('node:http')
const {createHash} = require('node:crypto')
const {PublicationMachineClient} = require('../../publication/infrastructure/PublicationMachineClient.cjs')
const TOKEN = 'synthetic-machine-lifecycle-test-key-123456'
async function withMachine(run) {
    const state = {pins: new Map(), inputs: new Map(), events: [], pending: new Set(), prepareUnknown: false,
        verifyUnknown: false, releaseUnknown: false, failures: [], onVerify: null, onRelease: null}
    const keys = ['/file/blog/cover.png', '/file/blog/new.png', '/file/blog/song.png']
    const resources = input => {
        const text = [...input.links, ...input.contents, ...input.htmlContents].join('\n')
        const found = [...new Set(text.match(/\/file\/blog\/[a-z-]+\.png/g) || [])].sort()
        if (found.some(key => !keys.includes(key) || state.pending.has(key))) throw new Error('CONFLICT')
        return found.map(key => '00000000-0000-0000-0000-' + String(keys.indexOf(key) + 1).padStart(12, '0')).sort()
    }
    const send = (res, data, status = 200) => {res.writeHead(status, {'Content-Type': 'application/json'}); res.end(JSON.stringify({code: status === 200 ? 0 : status, data}))}
    const server = http.createServer(async (req, res) => {
        try {
            if (req.headers['x-publication-key'] !== TOKEN || req.headers.origin !== undefined) return send(res, null, 401)
            const chunks = []; for await (const chunk of req) chunks.push(chunk)
            const input = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : null
            if (req.url === '/internal/media-publications/prepare') {
                const assetIds = resources(input), id = input.releaseId
                state.events.push({type: 'prepare', id})
                const existing = state.pins.get(id)
                if (existing && (existing.status !== 'HELD' || state.inputs.get(id) !== JSON.stringify(input))) return send(res, null, 409)
                const pin = existing || {protocolVersion: 1, environmentId: 'test', publisherId: 'hexo', releaseId: id,
                    manifestDigest: createHash('sha256').update(JSON.stringify(input)).digest('hex'), assetIds, status: 'HELD', version: '0'}
                state.pins.set(id, pin); state.inputs.set(id, JSON.stringify(input))
                if (state.prepareUnknown) {state.prepareUnknown = false; res.destroy(); return}
                return send(res, pin)
            }
            const match = /^\/internal\/media-publications\/([^/]+)(?:\/(verify|release))?$/.exec(req.url)
            const pin = match && state.pins.get(match[1])
            if (!pin) return send(res, null, 404)
            const id = pin.releaseId, action = match[2]
            state.events.push({type: action || 'get', id})
            if (!action) return send(res, pin)
            if (action === 'verify') {
                if (pin.status !== 'HELD' || pin.manifestDigest !== input.manifestDigest || resources(input).some(id => !pin.assetIds.includes(id))) return send(res, null, 409)
                if (state.onVerify) await state.onVerify(id, input)
                if (state.verifyUnknown) {state.verifyUnknown = false; res.destroy(); return}
                return send(res, {protocolVersion: 1, verified: true})
            }
            if (input.retirementConfirmed !== true || input.manifestDigest !== pin.manifestDigest) return send(res, null, 409)
            if (pin.status === 'RELEASED') return send(res, pin)
            if (input.expectedVersion !== pin.version) return send(res, null, 409)
            if (state.onRelease) await state.onRelease(id)
            const released = {...pin, status: 'RELEASED', version: String(BigInt(pin.version) + 1n)}
            state.pins.set(id, released)
            if (state.releaseUnknown) {state.releaseUnknown = false; res.destroy(); return}
            return send(res, released)
        } catch (error) {
            if (error.message === 'CONFLICT') send(res, null, 409)
            else {state.failures.push(error); send(res, null, 503)}
        }
    })
    const sockets = new Set()
    server.on('connection', socket => {sockets.add(socket); socket.on('close', () => sockets.delete(socket))})
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    const client = () => new PublicationMachineClient({baseUrl: `http://127.0.0.1:${server.address().port}`,
        tokenSupplier: () => TOKEN, environmentId: 'test', publisherId: 'hexo'})
    try {await run({...state, client, state})} finally {
        for (const socket of sockets) socket.destroy()
        await new Promise(resolve => server.close(resolve))
    }
}
module.exports = {withMachine}
