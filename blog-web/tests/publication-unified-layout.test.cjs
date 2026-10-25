const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const {randomUUID} = require('node:crypto')
const {once} = require('node:events')
const {createPublicationServer} = require('../publication/interfaces/server.cjs')
const {fixture, write} = require('./support/publication-site-fixture.cjs')
const {layoutOf, publicPath, assertUnifiedBudget} = require('../publication/domain/UnifiedPublicationLayout.cjs')
const {assembleUnified} = require('../publication/infrastructure/UnifiedPublicationAssembler.cjs')
const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
const {PublicationManifestCollector} = require('../publication/infrastructure/PublicationManifestCollector.cjs')
const {AnzhiyuScriptPolicy} = require('../publication/infrastructure/AnzhiyuScriptPolicy.cjs')
const {newAttempt, validateAttempt} = require('../publication/domain/PublicationAttempt.cjs')
const {FilePublicationRepository} = require('../publication/infrastructure/FilePublicationRepository.cjs')
const {FilePublicationAttemptRepository} = require('../publication/infrastructure/FilePublicationAttemptRepository.cjs')
const {ProtectedPublicationCoordinator} = require('../publication/application/ProtectedPublicationCoordinator.cjs')
const {PublicationServingGate} = require('../publication/infrastructure/PublicationServingGate.cjs')
const {withMachine} = require('./support/fake-publication-machine.cjs')

const shell = '<!doctype html><html><body>frozen Vue shell<script type="module" src="/assets/interaction.js"></script></body></html>'
async function vue(root) {await write(root, '.build/app/index.html', shell)}

test('combined layout enforces shared output budgets even when each input independently fits', () => {
    const mib = 1024 * 1024
    const files = count => Array.from({length: count}, (_, index) => ({path: `assets/file-${index}.bin`, size: 16 * mib}))
    assert.doesNotThrow(() => assertUnifiedBudget(files(16)))
    assert.throws(() => assertUnifiedBudget(files(17)))
    assert.throws(() => assertUnifiedBudget([{path: 'oversize.bin', size: 16 * mib + 1}]))
    assert.throws(() => assertUnifiedBudget(Array.from({length: 50001}, (_, index) => ({path: `f${index}`, size: 0}))))
    assert.doesNotThrow(() => assertUnifiedBudget([{path: Array(64).fill('d').join('/'), size: 0}]))
    assert.throws(() => assertUnifiedBudget([{path: Array(65).fill('d').join('/'), size: 0}]))
})

test('layout versions preserve legacy paths and distinguish the unified public root', () => {
    assert.equal(layoutOf({schemaVersion: 2}), 1)
    assert.equal(layoutOf({schemaVersion: 3, layoutVersion: 2}), 2)
    assert.throws(() => layoutOf({schemaVersion: 99}))
    assert.throws(() => layoutOf({schemaVersion: 3, layoutVersion: 1}))
    assert.equal(publicPath(1, '/blog/article/19/'), 'article/19/')
    assert.equal(publicPath(1, '/assets/app.js'), null)
    assert.equal(publicPath(2, '/blog/article/19/'), 'blog/article/19/')
    assert.equal(publicPath(2, '/assets/app.js'), 'assets/app.js')
    for (const uri of ['/../secret', '/blog/%2e%2e/secret', '/assets/%252e%252e/secret', '/.vite/manifest.json', '/internal/status', '/api/articles', '/uploads/x'])
        assert.equal(publicPath(2, uri), null)
})

test('unified attempts explicitly record layout and collector versions without upgrading old records', async () => {
    await fixture(async ({root, directory, inputs}) => {
        await vue(root)
        const snapshot = await inputs.freeze([])
        try {
            const input = await inputs.exportDescriptor(snapshot)
            const unified = newAttempt({releaseId: snapshot.releaseId, environmentId: 'test', publisherId: 'web', input, layoutVersion: 2})
            assert.equal(unified.schemaVersion, 3)
            assert.equal(unified.layoutVersion, 2)
            assert.equal(unified.collectorVersion, 2)
            const legacy = {...unified, schemaVersion: 2, collectorVersion: 1}
            delete legacy.layoutVersion
            assert.equal(validateAttempt(legacy).schemaVersion, 2)
            assert.equal(layoutOf(validateAttempt(legacy)), 1)
            const repository = new FilePublicationAttemptRepository(path.join(directory, 'attempts'))
            const created = await repository.create(unified)
            await assert.rejects(repository.save({...legacy, recordVersion: created.recordVersion}, created.recordVersion))
        } finally {await inputs.dispose(snapshot)}
    }, {dev: false})
})

test('real unified render uses frozen Vue and Hexo in distinct public namespaces', async () => {
    await fixture(async ({root, directory, inputs}) => {
        await vue(root)
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const snapshot = await inputs.freeze([])
        try {
            await write(root, '.build/app/index.html', '<!doctype html><body>later original</body>')
            const renderer = new HexoPublicationRenderer({root, directory, inputs, layoutVersion: 2})
            let output
            const id = await renderer.renderFrozen(snapshot, {onStageReady: async stage => {output = stage}})
            const release = path.join(directory, 'releases', id)
            assert.equal(await fs.readFile(path.join(release, 'index.html'), 'utf8'), shell)
            assert.match(await fs.readFile(path.join(release, 'blog/index.html'), 'utf8'), /冻结正文/)
            assert.match(await fs.readFile(path.join(release, 'assets/interaction.js'), 'utf8'), /widgetReady/)
            await assert.rejects(fs.access(path.join(release, '.vite/manifest.json')))
            assert.equal(output.layoutVersion, 2)
            await renderer.verifyOutput(output)
        } finally {await inputs.dispose(snapshot)}
    }, {dev: false})
})

test('output collection cannot hide modified public Vue bytes behind the frozen copy', async () => {
    await fixture(async ({root, directory, inputs}) => {
        await vue(root)
        const snapshot = await inputs.freeze([])
        try {
            const base = `releases/${snapshot.releaseId}`
            await write(directory, base + '/index.html', shell)
            await write(directory, base + '/assets/interaction.js', 'export const widgetReady = true;')
            await write(directory, base + '/assets/background.js', 'export const backgroundReady = true;')
            await write(directory, base + '/blog/index.html', '<!doctype html><body>blog</body>')
            const collector = new PublicationManifestCollector({directory, inputs, scriptPolicy: new AnzhiyuScriptPolicy(), layoutVersion: 2})
            await collector.output(snapshot, snapshot.releaseId)
            await write(directory, base + '/assets/interaction.js', 'export const widgetReady = false;')
            await assert.rejects(collector.output(snapshot, snapshot.releaseId))
        } finally {await inputs.dispose(snapshot)}
    }, {dev: false})
})

test('assembler rejects occupied targets and reserved Vue namespaces before touching a sentinel', async () => {
    await fixture(async ({root, directory, inputs}) => {
        await vue(root)
        await write(root, '.build/app/blog/index.html', '<!doctype html><body>collision</body>')
        const snapshot = await inputs.freeze([])
        try {
            const target = path.join(directory, 'stages', 'stage-' + randomUUID())
            await fs.mkdir(target, {recursive: true})
            await fs.writeFile(path.join(target, 'sentinel.txt'), 'preserve')
            await assert.rejects(assembleUnified(snapshot, path.join(snapshot.workRoot, '.build/blog'), target))
            assert.equal(await fs.readFile(path.join(target, 'sentinel.txt'), 'utf8'), 'preserve')
        } finally {await inputs.dispose(snapshot)}
    }, {dev: false})
})

test('a Vue blog namespace collision fails before copying into an empty stage', async () => {
    await fixture(async ({root, directory, inputs}) => {
        await vue(root)
        await write(root, '.build/app/blog/index.html', '<!doctype html><body>collision</body>')
        const snapshot = await inputs.freeze([])
        try {
            const target = path.join(directory, 'stages', 'stage-' + randomUUID())
            await fs.mkdir(target, {recursive: true})
            await write(snapshot.workRoot, '.build/blog/index.html', '<!doctype html><body>Hexo</body>')
            await assert.rejects(assembleUnified(snapshot, path.join(snapshot.workRoot, '.build/blog'), target), /Unified publication/)
            assert.deepEqual(await fs.readdir(target), [])
        } finally {await inputs.dispose(snapshot)}
    }, {dev: false})
})

test('an incomplete unified copy does not publish or poison a later correct build', async () => {
    await fixture(async ({root, directory, inputs}) => {
        await vue(root)
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        await write(directory, 'releases/previous/sentinel.txt', 'previous remains')
        const badSnapshot = await inputs.freeze([])
        try {
            const renderer = new HexoPublicationRenderer({root, directory, inputs, layoutVersion: 2,
                copy: async (_source, target) => fs.writeFile(target, 'partial bytes')})
            await assert.rejects(renderer.renderFrozen(badSnapshot))
            await assert.rejects(fs.access(path.join(directory, 'releases', badSnapshot.releaseId)))
            assert.equal(await fs.readFile(path.join(directory, 'releases/previous/sentinel.txt'), 'utf8'), 'previous remains')
            assert.deepEqual(await fs.readdir(path.join(directory, 'stages')), [])
        } finally {await inputs.dispose(badSnapshot)}
        const goodSnapshot = await inputs.freeze([])
        try {
            const release = await new HexoPublicationRenderer({root, directory, inputs, layoutVersion: 2}).renderFrozen(goodSnapshot)
            assert.equal(await fs.readFile(path.join(directory, 'releases', release, 'index.html'), 'utf8'), shell)
        } finally {await inputs.dispose(goodSnapshot)}
    }, {dev: false})
})

test('an existing same-name release is preserved during unified stage recovery', async () => {
    await fixture(async ({root, directory, inputs}) => {
        await vue(root)
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const snapshot = await inputs.freeze([])
        try {
            await write(directory, `releases/${snapshot.releaseId}/sentinel.txt`, 'do not replace')
            await assert.rejects(new HexoPublicationRenderer({root, directory, inputs, layoutVersion: 2}).renderFrozen(snapshot))
            assert.equal(await fs.readFile(path.join(directory, 'releases', snapshot.releaseId, 'sentinel.txt'), 'utf8'), 'do not replace')
        } finally {await inputs.dispose(snapshot)}
    }, {dev: false})
})

test('protected unified lifecycle commits explicit layout proofs and preserves an old unregistered fallback', async () => {
    await withMachine(async machine => fixture(async ({root, directory, inputs}) => {
        await vue(root)
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const repository = new FilePublicationRepository(directory)
        await write(directory, 'releases/legacy-existing/index.html', '<!doctype html><body>legacy remains</body>')
        await repository.save({...await repository.load(), activeRelease: 'legacy-existing'})
        const attempts = new FilePublicationAttemptRepository(path.join(directory, 'attempts'))
        const renderer = new HexoPublicationRenderer({root, directory, inputs, layoutVersion: 2})
        const collector = new PublicationManifestCollector({directory, inputs, scriptPolicy: new AnzhiyuScriptPolicy(), layoutVersion: 2})
        const coordinator = new ProtectedPublicationCoordinator({repository, attempts, renderer, collector, inputs,
            source: {fetchPosts: async () => []}, gate: new PublicationServingGate(), client: machine.client()})
        await coordinator.tick()
        const pointer = await repository.load()
        assert.equal(pointer.phase, 'CURRENT')
        assert.equal(pointer.schemaVersion, 3)
        assert.equal(pointer.layoutVersion, 2)
        assert.equal(pointer.fallbackRelease, 'legacy-existing')
        assert.equal(pointer.protection.releases[0].layoutVersion, 2)
        assert.equal((await attempts.get(pointer.activeRelease)).layoutVersion, 2)
        assert.equal(machine.state.pins.get(pointer.activeRelease).status, 'HELD')
        await fs.access(path.join(directory, 'releases/legacy-existing/index.html'))
        const server = createPublicationServer({repository, coordinator, directory, gate: coordinator.gate,
            token: 'synthetic-unified-real-journal-http-key'})
        try {
            server.listen(0, '127.0.0.1'); await once(server, 'listening')
            const base = `http://127.0.0.1:${server.address().port}`
            const vueResponse = await fetch(base + '/index.html')
            assert.equal(vueResponse.status, 200); assert.equal(await vueResponse.text(), shell)
            const chunk = await fetch(base + '/assets/interaction.js')
            assert.equal(chunk.status, 200); assert.match(await chunk.text(), /widgetReady/)
            const blog = await fetch(base + '/blog/')
            assert.equal(blog.status, 200); assert.match(await blog.text(), /冻结正文/)
        } finally {server.closeAllConnections(); if (server.listening) await new Promise(resolve => server.close(resolve))}
    }, {dev: false}))
})

test('unified publishing never automatically retires an older protected Hexo-only release', async () => {
    await withMachine(async machine => fixture(async ({root, directory}) => {
        await vue(root)
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const {FrozenPublicationInputs} = require('../publication/infrastructure/FrozenPublicationInputs.cjs')
        const repository = new FilePublicationRepository(directory)
        const attempts = new FilePublicationAttemptRepository(path.join(directory, 'attempts'))
        const create = layoutVersion => {
            const inputs = new FrozenPublicationInputs({root, directory})
            const renderer = new HexoPublicationRenderer({root, directory, inputs, layoutVersion})
            const collector = new PublicationManifestCollector({directory, inputs, scriptPolicy: new AnzhiyuScriptPolicy(), layoutVersion})
            return new ProtectedPublicationCoordinator({repository, attempts, renderer, collector, inputs,
                source: {fetchPosts: async () => []}, gate: new PublicationServingGate(), client: machine.client()})
        }
        await create(1).tick()
        const legacy = (await repository.load()).activeRelease
        assert.equal((await attempts.get(legacy)).schemaVersion, 2)
        const unified = create(2)
        for (const name of ['second', 'third', 'fourth']) {
            await write(root, 'source/index.html', `---\nlayout: false\n---\n<!doctype html><body>${name}</body>`)
            await unified.tick()
        }
        const current = await repository.load()
        assert.equal(current.schemaVersion, 3)
        assert.notEqual(current.activeRelease, legacy)
        assert.notEqual(current.fallbackRelease, legacy)
        assert.equal(machine.state.pins.get(legacy).status, 'HELD')
        await fs.access(path.join(directory, 'releases', legacy, 'index.html'))
        assert.equal(machine.state.events.some(event => event.type === 'release' && event.id === legacy), false)
    }, {dev: false}))
})
