const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const {fixture, write} = require('./support/publication-site-fixture.cjs')
const {withMachine} = require('./support/fake-publication-machine.cjs')
const {ProtectedPublicationCoordinator} = require('../publication/application/ProtectedPublicationCoordinator.cjs')
const {FilePublicationRepository} = require('../publication/infrastructure/FilePublicationRepository.cjs')
const {FilePublicationAttemptRepository} = require('../publication/infrastructure/FilePublicationAttemptRepository.cjs')
const {FrozenPublicationInputs} = require('../publication/infrastructure/FrozenPublicationInputs.cjs')
const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
const {PublicationManifestCollector} = require('../publication/infrastructure/PublicationManifestCollector.cjs')
const {AnzhiyuScriptPolicy} = require('../publication/infrastructure/AnzhiyuScriptPolicy.cjs')
const {PublicationServingGate} = require('../publication/infrastructure/PublicationServingGate.cjs')

async function protectedFixture(run) {
    await withMachine(async machine => fixture(async ({root, directory}) => {
        const page = body => write(root, 'source/index.html', '---\nlayout: false\n---\n<!doctype html><html><body>' + body + '</body></html>')
        await page("<img src='/file/blog/cover.png'>")
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const repository = new FilePublicationRepository(directory), attempts = new FilePublicationAttemptRepository(path.join(directory, 'attempts'))
        await fs.mkdir(path.join(directory, 'releases/legacy-existing'), {recursive: true})
        await fs.writeFile(path.join(directory, 'releases/legacy-existing/index.html'), '<!doctype html><body>legacy</body>')
        await repository.save({...await repository.load(), phase: 'CURRENT', activeRelease: 'legacy-existing', publishedDigest: 'f'.repeat(64)})
        const source = {fetchPosts: async () => []}, gate = new PublicationServingGate()
        const make = () => {
            const inputs = new FrozenPublicationInputs({root, directory, dev: false})
            const collector = new PublicationManifestCollector({directory, inputs, scriptPolicy: new AnzhiyuScriptPolicy()})
            const renderer = new HexoPublicationRenderer({root, directory, inputs, dev: false})
            const render = renderer.renderFrozen.bind(renderer)
            renderer.renderFrozen = async (snapshot, options) => {
                assert.equal(machine.state.pins.get(snapshot.releaseId)?.status, 'HELD')
                assert.equal((await attempts.get(snapshot.releaseId)).phase, 'RENDERING')
                machine.state.events.push({type: 'build', id: snapshot.releaseId})
                return render(snapshot, options)
            }
            return {inputs, collector, renderer, coordinator: new ProtectedPublicationCoordinator({repository, attempts, source,
                inputs, collector, renderer, client: machine.client(), gate})}
        }
        machine.state.onVerify = async id => {assert.notEqual((await repository.load()).activeRelease, id)}
        try {await run({root, directory, page, repository, attempts, source, gate, machine, make, ...make()})}
        finally {assert.deepEqual(machine.state.failures, [])}
    }, {dev: false}))
}

test('protected publication prepares before real Hexo and verifies before committing its pointer', async () => {
    await protectedFixture(async f => {
        await f.coordinator.tick()
        const state = await f.repository.load()
        assert.notEqual(state.activeRelease, 'legacy-existing')
        assert.equal(state.fallbackRelease, 'legacy-existing')
        assert.equal((await f.attempts.get(state.activeRelease)).phase, 'ACTIVE')
        assert.deepEqual(await f.coordinator.servingProof(state), (await f.attempts.get(state.activeRelease)).output)
        assert.deepEqual(f.machine.state.events.filter(event => event.id === state.activeRelease).map(event => event.type), ['prepare', 'build', 'verify'])
        assert.match(await fs.readFile(path.join(f.directory, 'releases', state.activeRelease, 'index.html'), 'utf8'), /cover\.png/)
    })
})

test('prepare outcome unknown is replayed with the same frozen release after restart', async () => {
    await protectedFixture(async f => {
        f.machine.state.prepareUnknown = true
        await f.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
        const [unknown] = await f.attempts.listUnfinished()
        assert.equal(unknown?.phase, 'PREPARE_UNKNOWN')
        const restarted = f.make()
        await restarted.coordinator.retry(); await restarted.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, unknown.releaseId)
        assert.equal(f.machine.state.pins.size, 1)
        assert.equal(f.machine.state.events.filter(event => event.type === 'prepare').length, 2)
    })
})

test('unknown verify outcome retries the same built output without rendering another version', async () => {
    await protectedFixture(async f => {
        f.machine.state.verifyUnknown = true
        await f.coordinator.tick()
        const [unknown] = await f.attempts.listUnfinished()
        assert.equal(unknown?.phase, 'VERIFY_UNKNOWN')
        const restarted = f.make()
        await restarted.coordinator.retry(); await restarted.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, unknown.releaseId)
        assert.equal(f.machine.state.events.filter(event => event.type === 'build').length, 1)
        assert.equal(f.machine.state.events.filter(event => event.type === 'verify').length, 2)
    })
})

test('DELETE_PENDING rejects preparation and keeps the legacy current directory untouched', async () => {
    await protectedFixture(async f => {
        f.machine.state.pending.add('/file/blog/cover.png')
        await f.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
        assert.equal((await f.repository.load()).phase, 'FAILED')
        assert.equal(f.machine.state.events.filter(event => event.type === 'build').length, 0)
        assert.match(await fs.readFile(path.join(f.directory, 'releases/legacy-existing/index.html'), 'utf8'), /legacy/)
    })
})

test('three protected versions retain two HELD and release the old one only after its directory is gone', async () => {
    await protectedFixture(async f => {
        const retired = []
        f.machine.state.onRelease = async id => {
            await assert.rejects(fs.access(path.join(f.directory, 'releases', id)))
            retired.push(id)
        }
        await f.coordinator.tick()
        const first = (await f.repository.load()).activeRelease
        await f.page("<p>second</p><img src='/file/blog/cover.png'>"); await f.coordinator.tick()
        const second = (await f.repository.load()).activeRelease
        assert.notEqual(first, second)
        await f.page("<p>third</p><img src='/file/blog/new.png'>"); await f.coordinator.tick(); await f.coordinator.tick()
        const current = await f.repository.load()
        assert.equal(current.fallbackRelease, second)
        assert.notEqual(current.activeRelease, second)
        assert.deepEqual(retired, [first])
        assert.equal(f.machine.state.pins.get(first).status, 'RELEASED')
        assert.equal(f.machine.state.pins.get(second).status, 'HELD')
        await fs.access(path.join(f.directory, 'releases/legacy-existing/index.html'))
    })
})

test('an atomic pointer already committed before a journal failure is authoritative after restart', async () => {
    await protectedFixture(async f => {
        const save = f.repository.save.bind(f.repository)
        let failed = false
        f.repository.save = async state => {
            await save(state)
            if (!failed && state.activeRelease !== 'legacy-existing') {failed = true; throw new Error('synthetic crash after pointer rename')}
        }
        await f.coordinator.tick()
        const committed = await f.repository.load()
        assert.notEqual(committed.activeRelease, 'legacy-existing')
        assert.equal((await f.attempts.get(committed.activeRelease)).phase, 'VERIFIED')
        const restarted = f.make()
        await restarted.coordinator.retry(); await restarted.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, committed.activeRelease)
        assert.equal((await f.attempts.get(committed.activeRelease)).phase, 'ACTIVE')
        assert.equal(f.machine.state.pins.size, 1)
    })
})

test('manual retry rebuilds an acknowledged current source while ordinary unchanged ticks do not', async () => {
    await protectedFixture(async f => {
        await f.coordinator.tick()
        const first = (await f.repository.load()).activeRelease
        await f.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, first)
        await f.coordinator.retry(); await f.coordinator.tick()
        assert.notEqual((await f.repository.load()).activeRelease, first)
    })
})

test('unknown release response is replayed with the original expected version and absent directory', async () => {
    await protectedFixture(async f => {
        await f.coordinator.tick()
        const first = (await f.repository.load()).activeRelease
        await f.page("<p>second</p><img src='/file/blog/cover.png'>"); await f.coordinator.tick()
        f.machine.state.releaseUnknown = true
        await f.page("<p>third</p><img src='/file/blog/new.png'>"); await f.coordinator.tick()
        assert.equal((await f.attempts.get(first)).phase, 'RELEASE_UNKNOWN')
        assert.equal(f.machine.state.pins.get(first).status, 'RELEASED')
        const active = (await f.repository.load()).activeRelease
        const restarted = f.make()
        await restarted.coordinator.retry(); await restarted.coordinator.tick()
        assert.equal((await f.attempts.get(first)).phase, 'RELEASED')
        assert.equal((await f.repository.load()).activeRelease, active)
    })
})

test('invalid protected pointer versions and proof identities are rejected before replacing the committed file', async () => {
    await protectedFixture(async f => {
        await f.coordinator.tick()
        const current = await f.repository.load()
        const before = await fs.readFile(path.join(f.directory, 'publication.json'), 'utf8')
        for (const changes of [{schemaVersion: 99}, {activeRelease: '../outside'},
            {protection: {...current.protection, releases: [{...current.protection.releases[0], outputDigest: 'invalid'}]}},
            {protection: {...current.protection, publisherId: 'BAD-OWNER'}}, {token: 'synthetic-not-allowed'}])
            await assert.rejects(f.repository.save({...current, ...changes}))
        assert.equal(await fs.readFile(path.join(f.directory, 'publication.json'), 'utf8'), before)
    })
})

test('restart refuses a protected pointer whose published intent differs from its active journal', async () => {
    await protectedFixture(async f => {
        await f.coordinator.tick()
        const active = (await f.repository.load()).activeRelease
        const get = f.attempts.get.bind(f.attempts)
        f.attempts.get = async id => {
            const attempt = await get(id)
            return id === active ? {...attempt, desiredDigest: '0'.repeat(64)} : attempt
        }
        await assert.rejects(f.coordinator.servingProof(await f.repository.load()))
        await f.make().coordinator.tick()
        assert.equal((await f.repository.load()).phase, 'FAILED')
        assert.equal((await f.repository.load()).activeRelease, active)
        assert.equal(f.machine.state.events.filter(event => event.type === 'build').length, 1)
    })
})

test('source changes during verification never activate the stale rendered version', async () => {
    await protectedFixture(async f => {
        f.machine.state.onVerify = async () => {
            await f.page("<p>new intent</p><img src='/file/blog/new.png'>")
            f.machine.state.onVerify = null
        }
        await f.coordinator.tick()
        const obsolete = f.machine.state.events.find(event => event.type === 'build').id
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
        assert.equal((await f.attempts.get(obsolete)).phase, 'RELEASED')
        assert.equal(f.machine.state.pins.get(obsolete).status, 'RELEASED')
        await assert.rejects(fs.access(path.join(f.directory, 'releases', obsolete)))
        await f.coordinator.tick()
        const current = await f.repository.load()
        assert.notEqual(current.activeRelease, obsolete)
        assert.notEqual(current.activeRelease, 'legacy-existing')
        assert.match(await fs.readFile(path.join(f.directory, 'releases', current.activeRelease, 'index.html'), 'utf8'), /new intent/)
    })
})

test('a live reader blocks third-version retirement until its read lease ends', async () => {
    await protectedFixture(async f => {
        await f.coordinator.tick()
        const first = (await f.repository.load()).activeRelease
        const release = await f.gate.acquireRead()
        try {
            await f.page("<p>second</p><img src='/file/blog/cover.png'>"); await f.coordinator.tick()
            await f.page("<p>third</p><img src='/file/blog/new.png'>"); await f.coordinator.tick()
            assert.equal((await f.attempts.get(first)).phase, 'ACTIVE')
            assert.equal(f.machine.state.pins.get(first).status, 'HELD')
            await fs.access(path.join(f.directory, 'releases', first, 'index.html'))
        } finally {release()}
        await f.coordinator.tick()
        assert.equal((await f.attempts.get(first)).phase, 'RELEASED')
        await assert.rejects(fs.access(path.join(f.directory, 'releases', first)))
    })
})

test('a committed stage with unknown checkpoint result resumes without running Hexo twice', async () => {
    await protectedFixture(async f => {
        const save = f.attempts.save.bind(f.attempts)
        let dropped = false
        f.attempts.save = async (value, version) => {
            const saved = await save(value, version)
            if (!dropped && value.phase === 'STAGED') {dropped = true; throw new Error('synthetic checkpoint response lost')}
            return saved
        }
        await f.coordinator.tick()
        const id = f.machine.state.events.find(event => event.type === 'build').id
        assert.equal((await f.attempts.get(id)).phase, 'STAGED')
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
        const restarted = f.make()
        await restarted.coordinator.retry(); await restarted.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, id)
        assert.equal((await f.attempts.get(id)).phase, 'ACTIVE')
        assert.equal(f.machine.state.events.filter(event => event.type === 'build').length, 1)
    })
})

test('unknown managed links fail prepare without replacing the old site or running Hexo', async () => {
    await protectedFixture(async f => {
        await f.page("<img src='/file/blog/not-registered.png'>")
        await f.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
        assert.equal((await f.repository.load()).phase, 'FAILED')
        assert.equal(f.machine.state.events.filter(event => event.type === 'build').length, 0)
        assert.equal(f.machine.state.pins.size, 0)
    })
})

test('unadapted remote scripts fail before prepare and do not fetch the remote script', async () => {
    await protectedFixture(async f => {
        await f.page('<script src="https://unreviewed.invalid/loader.js"></script>')
        await f.coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
        assert.equal((await f.repository.load()).phase, 'FAILED')
        assert.equal(f.machine.state.events.length, 0)
        assert.equal(f.machine.state.pins.size, 0)
    })
})

test('corrected source supersedes only a failed pre-request attempt after restart', async () => {
    await protectedFixture(async f => {
        await f.page('<script src="https://unreviewed.invalid/bad.js"></script>')
        await f.coordinator.tick()
        const [failed] = await f.attempts.listUnfinished()
        assert.equal(failed.phase, 'FROZEN')
        assert.equal(f.machine.state.events.length, 0)
        await f.page("<p>corrected</p><img src='/file/blog/cover.png'>")
        const restarted = f.make()
        await restarted.coordinator.retry(); await restarted.coordinator.tick()
        assert.equal((await f.attempts.get(failed.releaseId)).phase, 'REJECTED')
        const current = await f.repository.load()
        assert.equal(current.phase, 'CURRENT')
        assert.notEqual(current.activeRelease, failed.releaseId)
        assert.notEqual(current.activeRelease, 'legacy-existing')
        assert.equal(f.machine.state.pins.size, 1)
    })
})

test('partial retirement resumes from durable ownership without blocking a later publication', async () => {
    await protectedFixture(async f => {
        await f.coordinator.tick()
        const first = (await f.repository.load()).activeRelease
        await f.page("<p>second</p><img src='/file/blog/cover.png'>"); await f.coordinator.tick()
        f.renderer.removeOutput = async output => {
            assert.equal(output.releaseId, first)
            await fs.unlink(path.join(f.directory, 'releases', first, 'index.html'))
            throw new Error('synthetic crash after removing one output file')
        }
        await f.page("<p>third</p><img src='/file/blog/new.png'>"); await f.coordinator.tick()
        const partial = await f.attempts.get(first)
        assert.equal(partial.phase, 'RETIRING')
        assert.equal(partial.retirement?.directoryRemoved, false)
        assert.equal(f.machine.state.pins.get(first).status, 'HELD')
        const restarted = f.make()
        await f.page("<p>fourth</p><img src='/file/blog/cover.png'>")
        await restarted.coordinator.retry(); await restarted.coordinator.tick()
        assert.equal((await f.attempts.get(first)).phase, 'RELEASED')
        await assert.rejects(fs.access(path.join(f.directory, 'releases', first)))
        const current = await f.repository.load()
        assert.equal(current.phase, 'CURRENT')
        assert.match(await fs.readFile(path.join(f.directory, 'releases', current.activeRelease, 'index.html'), 'utf8'), /fourth/)
    })
})

test('output modified during remote verification remains HELD and never becomes current', async () => {
    await protectedFixture(async f => {
        f.machine.state.onVerify = async id => {
            await fs.writeFile(path.join(f.directory, 'releases', id, 'index.html'), '<!doctype html><body>tampered</body>')
        }
        await f.coordinator.tick()
        const id = f.machine.state.events.find(event => event.type === 'build').id
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
        assert.equal((await f.repository.load()).phase, 'FAILED')
        assert.equal((await f.attempts.get(id)).phase, 'VERIFY_UNKNOWN')
        assert.equal(f.machine.state.pins.get(id).status, 'HELD')
        assert.equal(f.machine.state.events.filter(event => event.type === 'release').length, 0)
        await f.make().coordinator.tick()
        assert.equal((await f.repository.load()).activeRelease, 'legacy-existing')
    })
})
