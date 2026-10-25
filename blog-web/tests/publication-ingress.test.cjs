const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const {spawn} = require('node:child_process')
const {createUnifiedIngressPolicy} = require('../publication/domain/UnifiedIngressPolicy.cjs')
const {ControlledNginxRuntime} = require('../publication/infrastructure/ControlledNginxRuntime.cjs')
const options = {listenPort: 18080, publicationOrigin: 'http://127.0.0.1:18081', backendOrigin: 'http://backend-fixture:18082'}
const instanceId = '00000000-0000-4000-8000-000000000001'

test('unified ingress policy has fixed public proxy and explicit private path denial', () => {
    const policy = createUnifiedIngressPolicy(options)
    assert.equal(typeof policy.digest, 'string')
    assert.match(policy.digest, /^[a-f0-9]{64}$/)
    assert.ok(Object.isFrozen(policy)); assert.ok(Object.isFrozen(policy.routes))
    assert.equal(policy.routes.find(route => route.path === '/').upstream, 'publication')
    assert.equal(policy.routes.find(route => route.path === '/api/').upstream, 'backend')
    assert.equal(policy.routes.find(route => route.path === '/internal/').action, 'deny')
    assert.equal(policy.routes.find(route => route.path === '/uploads/').action, 'deny')
    assert.ok(policy.routes.every(route => !Object.hasOwn(route, 'root') && !Object.hasOwn(route, 'alias')))
    assert.equal(createUnifiedIngressPolicy(options).digest, policy.digest)
    assert.notEqual(createUnifiedIngressPolicy({...options, listenPort: 18083}).digest, policy.digest)
})

test('unsafe upstreams loops ports and caller-supplied routes cannot become a trusted policy', () => {
    for (const changes of [{listenPort: 0}, {listenPort: 65536}, {listenPort: '80'},
        {publicationOrigin: 'http://remote.example:8081'}, {publicationOrigin: 'http://127.0.0.1:18080'},
        {backendOrigin: 'http://user:pass@backend-fixture:8080'}, {backendOrigin: 'http://backend-fixture:8080/?extra=1'},
        {backendOrigin: 'http://backend-fixture;evil:8080'}, {backendOrigin: 'http://backend-fixture:8080/extra'},
        {routes: [{path: '/', root: '/tmp/static'}]}, {include: '/tmp/extra.conf'}])
        assert.throws(() => createUnifiedIngressPolicy({...options, ...changes}))
})

async function fixture(run, {scenario = 'ready'} = {}) {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-ingress-test-'))
    const policy = createUnifiedIngressPolicy(options)
    const spawnProcess = (binary, args, settings) => {
        assert.equal(binary, '/usr/sbin/nginx')
        return spawn(process.execPath, [path.join(__dirname, 'support/fake-nginx-child.cjs'), ...args],
            {...settings, env: {SystemRoot: process.env.SystemRoot || '', TEST_NGINX_SCENARIO: scenario}})
    }
    const runtime = new ControlledNginxRuntime({policy, directory, publicationInstanceId: instanceId,
        spawnProcess, probe: async () => ({status: 'UP', instanceId}), startupTimeoutMs: 1000})
    try {await run({runtime, directory, policy})} finally {
        await runtime.stop()
        assert.equal(path.dirname(directory), os.tmpdir()); assert.match(path.basename(directory), /^publication-ingress-test-/)
        await fs.rm(directory, {recursive: true, force: true})
    }
}

test('runtime evidence is absent before start and binds an owned child pid with the exact config', async () => {
    await fixture(async ({runtime, directory, policy}) => {
        assert.equal(runtime.evidence(), null)
        await runtime.start()
        const proof = runtime.evidence()
        assert.ok(proof)
        assert.equal(proof.ready, true); assert.equal(proof.policyDigest, policy.digest)
        assert.match(proof.instanceId, /^[a-f0-9-]{36}$/)
        const entries = await fs.readdir(path.join(directory, 'ingress'))
        assert.equal(entries.length, 1)
        const config = await fs.readFile(path.join(directory, 'ingress', entries[0], 'nginx.conf'), 'utf8')
        assert.doesNotMatch(config, /(?:^|[;{}])\s*(?:include|root|alias)\s/)
        assert.match(config, /proxy_buffering off/)
        assert.match(config, /location \^~ \/internal\//)
        await runtime.stop(); assert.equal(runtime.evidence(), null)
    })
})

test('a healthy response from another process does not replace owned master pid evidence', async () => {
    await fixture(async ({runtime}) => {
        await assert.rejects(runtime.start())
        assert.equal(runtime.evidence(), null)
    }, {scenario: 'wrong-pid'})
})

test('validation failure and early child exit never produce ready evidence', async () => {
    for (const scenario of ['validation-failure', 'early-exit']) await fixture(async ({runtime}) => {
        await assert.rejects(runtime.start())
        assert.equal(runtime.evidence(), null)
    }, {scenario})
})

test('rewritten config invalidates evidence even while the owned child is alive', async () => {
    await fixture(async ({runtime, directory}) => {
        await runtime.start(); assert.ok(runtime.evidence())
        const [name] = await fs.readdir(path.join(directory, 'ingress'))
        const filename = path.join(directory, 'ingress', name, 'nginx.conf')
        await fs.chmod(filename, 0o600)
        await fs.appendFile(filename, '\ninclude /tmp/untrusted.conf;\n')
        assert.equal(runtime.evidence(), null)
    })
})

test('calling code cannot replace the policy identity after the controlled config was verified', async () => {
    await fixture(async ({runtime, policy}) => {
        await runtime.start()
        Reflect.set(runtime, 'policy', {...policy, digest: '0'.repeat(64)})
        assert.equal(runtime.evidence()?.policyDigest, policy.digest)
    })
})

test('stopping one owned runtime leaves another runtime child and proof intact', async () => {
    await fixture(async ({runtime: first}) => fixture(async ({runtime: second}) => {
        await first.start(); await second.start()
        await first.stop()
        assert.equal(first.evidence(), null)
        assert.equal(second.evidence()?.ready, true)
    }))
})

test('stop during asynchronous startup leaves no owned child or private config directory', async () => {
    await fixture(async ({runtime, directory}) => {
        const starting = runtime.start()
        await runtime.stop()
        await assert.rejects(starting)
        assert.equal(runtime.evidence(), null)
        const names = await fs.readdir(path.join(directory, 'ingress')).catch(error => {
            if (error.code !== 'ENOENT') throw error
            return []
        })
        assert.deepEqual(names, [])
    })
})
