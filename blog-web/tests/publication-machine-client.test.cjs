const {test} = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const {PublicationMachineClient} = require('../publication/infrastructure/PublicationMachineClient.cjs')
const {validatePin} = require('../publication/domain/PublicationPin.cjs')
const TOKEN = 'isolated-publication-client-test-key-1234567890'
const digest = 'a'.repeat(64)
const input = {protocolVersion: 1, releaseId: 'release-test', sourceDigest: digest, links: [], contents: [], htmlContents: []}
const expected = {environmentId: 'local', publisherId: 'hexo', releaseId: 'release-test'}
const pin = {protocolVersion: 1, ...expected, manifestDigest: digest, assetIds: ['00000000-0000-0000-0000-000000000001'], status: 'HELD', version: '0'}
function answer(res, data = pin) {res.writeHead(200, {'Content-Type': 'application/json'}); res.end(JSON.stringify({code: 0, data}))}
async function fixture(handler, run) {
    const sockets = new Set()
    const server = http.createServer(handler)
    server.on('connection', socket => {sockets.add(socket); socket.on('close', () => sockets.delete(socket))})
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    const baseUrl = `http://127.0.0.1:${server.address().port}`
    const make = overrides => new PublicationMachineClient({baseUrl, tokenSupplier: () => TOKEN, environmentId: 'local', publisherId: 'hexo', ...overrides})
    try {await run({make, baseUrl})} finally {
        for (const socket of sockets) socket.destroy()
        await new Promise(resolve => server.close(resolve))
    }
}
function rejects(action, category) {
    return assert.rejects(action, error => {
        assert.equal(error.category, category)
        assert.doesNotMatch(String(error), new RegExp(TOKEN + '|private upstream|file/blog'))
        return true
    })
}

test('client follows fixed machine routes with no browser credentials and exact version strings', async () => {
    const calls = []
    await fixture(async (req, res) => {
        const chunks = []; for await (const chunk of req) chunks.push(chunk)
        calls.push({path: req.url, method: req.method, headers: req.headers, body: chunks.length ? JSON.parse(Buffer.concat(chunks)) : null})
        if (req.url.endsWith('/verify')) answer(res, {protocolVersion: 1, verified: true})
        else if (req.url.endsWith('/release')) answer(res, {...pin, status: 'RELEASED', version: '1'})
        else answer(res)
    }, async ({make}) => {
        const client = make()
        assert.deepEqual(await client.prepare(input), pin)
        assert.deepEqual(await client.get('release-test'), pin)
        const {releaseId, ...output} = input
        await client.verify(releaseId, {...output, manifestDigest: digest})
        assert.equal((await client.release(releaseId, {manifestDigest: digest, expectedVersion: '0', retirementConfirmed: true})).status, 'RELEASED')
    })
    assert.deepEqual(calls.map(call => [call.method, call.path]), [
        ['POST', '/internal/media-publications/prepare'], ['GET', '/internal/media-publications/release-test'],
        ['POST', '/internal/media-publications/release-test/verify'], ['POST', '/internal/media-publications/release-test/release']])
    for (const call of calls) {
        assert.equal(call.headers['x-publication-key'], TOKEN)
        for (const name of ['origin', 'cookie', 'authorization']) assert.equal(call.headers[name], undefined)
        assert.equal(call.body?.environmentId, undefined); assert.equal(call.body?.publisherId, undefined)
    }
})

test('redirect never receives credential and unknown prepare outcome is not retried', async () => {
    let trapCalls = 0, prepareCalls = 0
    await fixture((req, res) => {trapCalls++; answer(res)}, async trap => {
        await fixture((req, res) => {prepareCalls++; res.writeHead(307, {Location: trap.baseUrl}); res.end()}, async ({make}) => {
            await rejects(() => make().prepare(input), 'UNKNOWN')
        })
    })
    assert.equal(trapCalls, 0); assert.equal(prepareCalls, 1)
})

test('invalid origins, identity, missing credentials and invalid requests fail before network', async () => {
    let calls = 0
    await fixture((req, res) => {calls++; answer(res)}, async ({make, baseUrl}) => {
        for (const bad of [baseUrl + '/wrong', baseUrl + '?key=private', baseUrl + '#secret', 'http://user:secret@127.0.0.1', 'http://remote.example', 'file:///tmp/example'])
            assert.throws(() => make({baseUrl: bad}), error => error.category === 'INVALID')
        assert.throws(() => make({environmentId: ''}), error => error.category === 'INVALID')
        await rejects(() => make({tokenSupplier: () => ''}).prepare(input), 'UNAUTHORIZED')
        await rejects(() => make().get('../escape'), 'INVALID')
        await rejects(() => make().prepare({...input, environmentId: 'production'}), 'INVALID')
        await rejects(() => make().prepare({...input, links: ['中'.repeat(349526)]}), 'LIMIT')
        await rejects(() => make().prepare({...input, links: Array(10001).fill('')}), 'LIMIT')
        await rejects(() => make().prepare({...input, contents: Array(5).fill('x'.repeat(1048576))}), 'LIMIT')
        await rejects(() => make().release('release-test', {manifestDigest: digest, expectedVersion: 0, retirementConfirmed: true}), 'INVALID')
        await rejects(() => make().release('release-test', {manifestDigest: digest, expectedVersion: '0', retirementConfirmed: false}), 'INVALID')
    })
    assert.equal(calls, 0)
})

test('headers without finished body still timeout and do not trigger automatic retry', async () => {
    let calls = 0
    await fixture((req, res) => {calls++; res.writeHead(200, {'Content-Type': 'application/json'}); res.write('{"code":0,')}, async ({make}) => {
        const start = Date.now()
        await rejects(() => make({timeoutMs: 80}).prepare(input), 'UNKNOWN')
        assert.ok(Date.now() - start < 2000)
    })
    assert.equal(calls, 1)
})

test('chunked response limit is checked before trusting content or declared length', async () => {
    await fixture((req, res) => {res.writeHead(200, {'Content-Type': 'application/json'}); res.end(' '.repeat(4 * 1024 * 1024 + 1))}, async ({make}) => {
        await rejects(() => make().prepare(input), 'LIMIT')
    })
})

test('wrong identity or status cannot be held, and long versions are never rounded', async () => {
    for (const changed of [{environmentId: 'production'}, {publisherId: 'other'}, {releaseId: 'other'}, {status: 'RELEASED'},
        {version: 9007199254740992}, {version: '9223372036854775808'}, {assetIds: [pin.assetIds[0], pin.assetIds[0]]}, {objectKey: 'file/blog/private'}]) {
        await fixture((req, res) => answer(res, {...pin, ...changed}), async ({make}) => {await rejects(() => make().prepare(input), 'UNAVAILABLE')})
    }
    await fixture((req, res) => answer(res, {...pin, version: '9223372036854775807'}), async ({make}) => {
        assert.equal((await make().get('release-test')).version, '9223372036854775807')
    })
    const validated = validatePin(pin, expected)
    assert.ok(Object.isFrozen(validated)); assert.ok(Object.isFrozen(validated.assetIds))
    assert.notEqual(validated.assetIds, pin.assetIds)
})

test('HTTP errors, content types and successful envelopes cannot leak or invent success', async () => {
    for (const [status, category] of [[401, 'UNAUTHORIZED'], [403, 'UNAUTHORIZED'], [409, 'CONFLICT'], [413, 'LIMIT'], [503, 'UNAVAILABLE']]) {
        await fixture((req, res) => {res.writeHead(status, {'Content-Type': 'text/plain'}); res.end('private upstream ' + TOKEN)}, async ({make}) => {
            await rejects(() => make().prepare(input), category)
        })
    }
    for (const body of ['not json', JSON.stringify({code: 500, data: pin}), JSON.stringify({code: 0, data: {protocolVersion: 1, verified: false}})]) {
        await fixture((req, res) => {res.writeHead(200, {'Content-Type': 'application/json'}); res.end(body)}, async ({make}) => {
            await rejects(() => make().verify('release-test', {protocolVersion: 1, sourceDigest: digest, manifestDigest: digest, links: [], contents: [], htmlContents: []}), 'UNAVAILABLE')
        })
    }
    await fixture((req, res) => {res.writeHead(200, {'Content-Type': 'text/plain'}); res.end(JSON.stringify({code: 0, data: pin}))}, async ({make}) => {
        await rejects(() => make().prepare(input), 'UNAVAILABLE')
    })
})

test('aggregate and escaped request sizes fail before whole-object serialization or credential acquisition', async () => {
    let tokens = 0, serialized = 0, calls = 0
    const client = new PublicationMachineClient({baseUrl: 'http://127.0.0.1:1', environmentId: 'local', publisherId: 'hexo',
        tokenSupplier: () => {tokens++; return TOKEN}, fetcher: async () => {calls++; throw new Error('network must not run')}})
    for (const values of [Array(10000).fill('x'.repeat(1048576)), ['\u0000'.repeat(800000)]]) {
        const body = Object.assign(Object.create({toJSON() {serialized++; throw new Error('bulk serialization reached')}}), input, {links: values})
        await rejects(() => client.prepare(body), 'LIMIT')
    }
    assert.equal(serialized, 0); assert.equal(tokens, 0); assert.equal(calls, 0)
})

test('exact serialized UTF8 body limit includes JSON overhead and escaping', async () => {
    const limit = 4 * 1024 * 1024
    const body = {...input, links: ['', '', '', '']}
    const overhead = Buffer.byteLength(JSON.stringify(body))
    body.links = ['x'.repeat(1048576), 'x'.repeat(1048576), 'x'.repeat(1048576), 'x'.repeat(limit - overhead - 3 * 1048576)]
    assert.equal(Buffer.byteLength(JSON.stringify(body)), limit)
    let received = 0
    await fixture(async (req, res) => {for await (const chunk of req) received += chunk.length; answer(res)}, async ({make}) => {
        await make().prepare(body)
        await rejects(() => make().prepare({...body, links: [...body.links.slice(0, 3), body.links[3] + 'x']}), 'LIMIT')
    })
    assert.equal(received, limit)
})

test('internal HTTP requires one explicitly matching trusted origin without widening the default', async () => {
    let tokenReads = 0, rejectedCalls = 0
    const options = {baseUrl: 'http://blog-server:8080', environmentId: 'local', publisherId: 'hexo',
        tokenSupplier: () => {tokenReads++; return TOKEN}, fetcher: async () => {rejectedCalls++; throw new Error('not allowed')}}
    assert.throws(() => new PublicationMachineClient(options), error => error.category === 'INVALID')
    for (const trustedHttpOrigin of ['http://other:8080', 'http://blog-server:8080/path', 'http://blog-server:8080?key=example',
        'http://user:secret@blog-server:8080', '*', null, {toString: () => 'http://blog-server:8080'}])
        assert.throws(() => new PublicationMachineClient({...options, trustedHttpOrigin}), error => error.category === 'INVALID')
    assert.equal(tokenReads, 0); assert.equal(rejectedCalls, 0)
    const calls = []
    const client = new PublicationMachineClient({...options, trustedHttpOrigin: 'http://blog-server:8080', fetcher: async (url, init) => {
        calls.push({url, init}); return new Response(JSON.stringify({code: 0, data: pin}), {headers: {'Content-Type': 'application/json'}})
    }})
    assert.deepEqual(await client.prepare(input), pin)
    assert.equal(calls.length, 1); assert.equal(calls[0].url, 'http://blog-server:8080/internal/media-publications/prepare')
    assert.equal(calls[0].init.headers['X-Publication-Key'], TOKEN); assert.equal(calls[0].init.redirect, 'error')
})
