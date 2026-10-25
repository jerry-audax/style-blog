const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const {randomUUID, createHash} = require('node:crypto')
const {fixture} = require('./support/publication-site-fixture.cjs')
const {newAttempt, validateAttempt, transition} = require('../publication/domain/PublicationAttempt.cjs')
const {FilePublicationAttemptRepository} = require('../publication/infrastructure/FilePublicationAttemptRepository.cjs')
const {PublicationServingGate} = require('../publication/infrastructure/PublicationServingGate.cjs')

function record() {
    const releaseId = 'release-' + randomUUID(), files = [{path: 'source/index.html', sha256: createHash('sha256').update('').digest('hex'), size: 0}]
    const sourceDigest = createHash('sha256').update(JSON.stringify({protocolVersion: 1, files})).digest('hex')
    return newAttempt({releaseId, environmentId: 'test', publisherId: 'test-publisher', input: {schemaVersion: 1,
        releaseId, sourceDigest, workName: 'work-' + randomUUID(), workDevice: '1', workInode: '2', files,
        initialInventory: [{path: 'source', type: 'directory'}, {path: 'source/index.html', type: 'file'}]}})
}

test('attempt identities are immutable and unknown phases paths and secret fields fail', () => {
    const value = record()
    assert.ok(Object.isFrozen(value)); assert.ok(Object.isFrozen(value.input.files))
    for (const bad of [{phase: 'GUESS'}, {token: 'synthetic-forbidden'}, {recordVersion: 1},
        {input: {...value.input, workName: '../outside'}}, {sourceDigest: 'b'.repeat(64)}])
        assert.throws(() => validateAttempt({...value, ...bad}))
    assert.throws(() => transition(value, 'ACTIVE'))
    assert.equal(transition(value, 'PREPARE_UNKNOWN').phase, 'PREPARE_UNKNOWN')
})

test('durable attempt repository reloads exact immutable identity and enforces string CAS', async () => {
    await fixture(async ({directory}) => {
        const root = path.join(directory, 'attempts'), repository = new FilePublicationAttemptRepository(root)
        const created = await repository.create(record())
        assert.equal(created.recordVersion, '1')
        const second = new FilePublicationAttemptRepository(root)
        assert.deepEqual(await second.get(created.releaseId), created)
        const changed = await repository.save(transition(created, 'PREPARE_UNKNOWN'), '1')
        assert.equal(changed.recordVersion, '2')
        await assert.rejects(second.save(created, '1'))
        await assert.rejects(second.create(created))
        assert.equal((await second.listUnfinished()).length, 1)
    })
})

test('only pre-request attempts can become terminal REJECTED and disappear from pending work', async () => {
    await fixture(async ({directory}) => {
        const repository = new FilePublicationAttemptRepository(path.join(directory, 'attempts'))
        const created = await repository.create(record())
        const rejected = await repository.save(transition(created, 'REJECTED', {error: 'SOURCE_CHANGED'}), created.recordVersion)
        assert.equal(rejected.phase, 'REJECTED')
        assert.deepEqual(await repository.listUnfinished(), [])
        assert.throws(() => transition(rejected, 'FROZEN'))
        assert.throws(() => transition(transition(record(), 'PREPARE_UNKNOWN'), 'REJECTED'))
    })
})

test('corrupt half-written and unknown journal formats fail without changing sentinel', async () => {
    await fixture(async ({directory}) => {
        const root = path.join(directory, 'attempts'), repository = new FilePublicationAttemptRepository(root)
        const created = await repository.create(record()), filename = path.join(root, created.releaseId + '.json')
        for (const content of ['{"schemaVersion":', JSON.stringify({...created, schemaVersion: created.schemaVersion + 1})]) {
            await fs.writeFile(filename, content)
            await assert.rejects(repository.get(created.releaseId))
        }
        await assert.rejects(repository.get('../sentinel'))
        assert.equal(await fs.readFile(filename, 'utf8'), JSON.stringify({...created, schemaVersion: created.schemaVersion + 1}))
    })
})

test('the immutable intended semantic digest survives restart and cannot be rebound by CAS', async () => {
    await fixture(async ({directory}) => {
        const value = record(), desiredDigest = 'e'.repeat(64)
        const intended = newAttempt({...value, desiredDigest})
        assert.equal(intended.desiredDigest, desiredDigest)
        const repository = new FilePublicationAttemptRepository(path.join(directory, 'attempts'))
        const created = await repository.create(intended)
        await assert.rejects(repository.save({...created, desiredDigest: 'f'.repeat(64)}, created.recordVersion))
        assert.equal((await repository.get(created.releaseId)).desiredDigest, desiredDigest)
    })
})

test('competing in-process CAS writes have one winner and preserve frozen identity', async () => {
    await fixture(async ({directory}) => {
        const repository = new FilePublicationAttemptRepository(path.join(directory, 'attempts'))
        const original = await repository.create(record()), next = transition(original, 'PREPARE_UNKNOWN')
        const results = await Promise.allSettled([repository.save(next, '1'), repository.save(next, '1')])
        assert.equal(results.filter(result => result.status === 'fulfilled').length, 1)
        assert.equal((await repository.get(original.releaseId)).recordVersion, '2')
        const alternate = record()
        const changed = {...next, recordVersion: '2', input: {...alternate.input, releaseId: original.releaseId}, sourceDigest: alternate.sourceDigest}
        await assert.rejects(repository.save(changed, '2'))
    })
})

test('read lease acquired before pointer load prevents retirement until released', async () => {
    const gate = new PublicationServingGate()
    const release = await gate.acquireRead(); let retired = false
    assert.equal(await gate.tryExclusive(async () => {retired = true}), false)
    assert.equal(retired, false)
    release(); release()
    assert.equal(await gate.tryExclusive(async () => {retired = true}), true)
    assert.equal(retired, true)
})

test('new readers queue with limits while exclusive local retirement runs', async () => {
    const gate = new PublicationServingGate({maxWaiters: 1, timeoutMs: 20})
    let finish
    const pending = new Promise(resolve => {finish = resolve})
    const exclusive = gate.tryExclusive(() => pending)
    try {
        const queued = gate.acquireRead()
        await assert.rejects(gate.acquireRead())
        await assert.rejects(queued)
    } finally {finish(); await exclusive}
    const release = await gate.acquireRead(); release()
})

test('a failed retirement unlocks and hands queued readers their releasable leases', async () => {
    const gate = new PublicationServingGate()
    let finish
    const barrier = new Promise(resolve => {finish = resolve})
    const exclusive = gate.tryExclusive(async () => {await barrier; throw new Error('synthetic local I/O failure')})
    const queued = gate.acquireRead()
    finish()
    await assert.rejects(exclusive)
    const release = await queued
    assert.equal(await gate.tryExclusive(async () => {}), false)
    release()
    assert.equal(await gate.tryExclusive(async () => {}), true)
})

test('restart clone preserves frozen identity without consuming cache or changed original inputs', async () => {
    await fixture(async ({root, directory, inputs}) => {
        const snapshot = await inputs.freeze([])
        let clone
        try {
            assert.equal(typeof inputs.exportDescriptor, 'function')
            const descriptor = await inputs.exportDescriptor(snapshot)
            await fs.writeFile(path.join(snapshot.workRoot, 'db.json'), 'poisoned previous cache')
            await fs.writeFile(path.join(root, 'source/index.html'), '<p>changed original</p>')
            const {FrozenPublicationInputs} = require('../publication/infrastructure/FrozenPublicationInputs.cjs')
            const restarted = new FrozenPublicationInputs({root, directory, dev: true})
            clone = await restarted.restoreClone(descriptor)
            assert.equal(clone.releaseId, snapshot.releaseId)
            assert.equal(clone.sourceDigest, snapshot.sourceDigest)
            assert.notEqual(clone.workRoot, snapshot.workRoot)
            await assert.rejects(fs.access(path.join(clone.workRoot, 'db.json')))
            assert.match(await fs.readFile(path.join(clone.workRoot, 'source/index.html'), 'utf8'), /冻结正文/)
            await restarted.verify(clone)
            await restarted.dispose(clone); clone = null
            await assert.rejects(restarted.restoreClone({...descriptor, workName: '../outside'}))
            await fs.appendFile(path.join(snapshot.workRoot, 'source/index.html'), 'changed frozen file')
            await assert.rejects(restarted.restoreClone(descriptor))
        } finally {await inputs.dispose(snapshot)}
    })
})

function heldPin(value) {return {protocolVersion: 1, environmentId: value.environmentId, publisherId: value.publisherId,
    releaseId: value.releaseId, manifestDigest: 'c'.repeat(64), assetIds: [], status: 'HELD', version: '1'}}

test('crash after durable stage checkpoint recovers the same output identity', async () => {
    await fixture(async ({root, directory, inputs}) => {
        const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const snapshot = await inputs.freeze([]), journalRoot = path.join(directory, 'attempts')
        const repository = new FilePublicationAttemptRepository(journalRoot)
        let attempt = await repository.create(newAttempt({releaseId: snapshot.releaseId, environmentId: 'test',
            publisherId: 'test-publisher', input: await inputs.exportDescriptor(snapshot)}))
        attempt = await repository.save(transition(attempt, 'PREPARE_UNKNOWN'), attempt.recordVersion)
        attempt = await repository.save(transition(attempt, 'HELD', {pin: heldPin(attempt)}), attempt.recordVersion)
        attempt = await repository.save(transition(attempt, 'RENDERING'), attempt.recordVersion)
        const renderer = new HexoPublicationRenderer({root, directory, inputs, dev: true})
        try {
            await assert.rejects(renderer.renderFrozen(snapshot, {onStageReady: async output => {
                attempt = await repository.save(transition(attempt, 'STAGED', {output}), attempt.recordVersion)
                throw new Error('synthetic crash after fsync')
            }}))
            const recovered = await new FilePublicationAttemptRepository(journalRoot).get(snapshot.releaseId)
            assert.equal(recovered.phase, 'STAGED')
            const restarted = new HexoPublicationRenderer({root, directory, dev: true})
            assert.equal(await restarted.recoverStage(recovered.output), snapshot.releaseId)
            await restarted.verifyOutput(recovered.output)
            const actual = await fs.lstat(path.join(directory, 'releases', snapshot.releaseId), {bigint: true})
            assert.equal(String(actual.ino), recovered.output.inode)
            await assert.rejects(fs.access(path.join(directory, 'stages', recovered.output.stageName)))
            await assert.rejects(restarted.verifyOutput({...recovered.output, inode: String(actual.ino + 1n)}))
        } finally {await inputs.dispose(snapshot)}
    })
})

test('a partial copy cannot poison a subsequent render of the same frozen release', async () => {
    await fixture(async ({root, directory, inputs}) => {
        const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const snapshot = await inputs.freeze([]), descriptor = await inputs.exportDescriptor(snapshot)
        let clone
        try {
            const broken = new HexoPublicationRenderer({root, directory, inputs, dev: true, copy: async (source, target) => {
                await fs.writeFile(path.join(target, 'partial.txt'), 'partially copied output')
                throw new Error('synthetic disk copy failure')
            }})
            await assert.rejects(broken.renderFrozen(snapshot))
            await assert.rejects(fs.access(path.join(directory, 'releases', snapshot.releaseId)))
            assert.deepEqual(await fs.readdir(path.join(directory, 'stages')), [])
            clone = await inputs.restoreClone(descriptor)
            const renderer = new HexoPublicationRenderer({root, directory, inputs, dev: true})
            assert.equal(await renderer.renderFrozen(clone), snapshot.releaseId)
            await assert.rejects(fs.access(path.join(directory, 'releases', snapshot.releaseId, 'partial.txt')))
        } finally {if (clone) await inputs.dispose(clone); await inputs.dispose(snapshot)}
    })
})

test('a silently incomplete copy cannot be checkpointed as a complete output', async () => {
    await fixture(async ({root, directory, inputs}) => {
        const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const snapshot = await inputs.freeze([])
        try {
            const renderer = new HexoPublicationRenderer({root, directory, inputs, dev: true, copy: async (source, target) => {
                await fs.copyFile(path.join(source, 'index.html'), path.join(target, 'index.html'))
            }})
            await assert.rejects(renderer.renderFrozen(snapshot))
            await assert.rejects(fs.access(path.join(directory, 'releases', snapshot.releaseId)))
        } finally {await inputs.dispose(snapshot)}
    })
})

test('an unrelated same-name release is never overwritten or reclaimed during stage recovery', async () => {
    await fixture(async ({root, directory, inputs}) => {
        const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const snapshot = await inputs.freeze([]), renderer = new HexoPublicationRenderer({root, directory, inputs, dev: true})
        let output
        const target = path.join(directory, 'releases', snapshot.releaseId)
        try {
            await assert.rejects(renderer.renderFrozen(snapshot, {onStageReady: async value => {
                output = value
                await fs.mkdir(target)
                await fs.writeFile(path.join(target, 'sentinel.txt'), 'unrelated release')
            }}))
            await assert.rejects(renderer.recoverStage(output))
            assert.equal(await fs.readFile(path.join(target, 'sentinel.txt'), 'utf8'), 'unrelated release')
        } finally {await inputs.dispose(snapshot)}
    })
})
