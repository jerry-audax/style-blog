const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const {PublicationCoordinator} = require('../publication/application/PublicationCoordinator.cjs')
const {FilePublicationRepository} = require('../publication/infrastructure/FilePublicationRepository.cjs')
const {snapshotDigest} = require('../publication/domain/Snapshot.cjs')

const post = (body = '正文', updated = '2026-10-01') => ({name: 'database-article-11.md', text: `---\nupdated: "${updated}"\ncover: "https://images.example/cover.jpg"\n---\n${body}`})

async function fixture(run) {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'blog-publication-test-'))
    let posts = [post()]
    let attempts = 0
    const repository = new FilePublicationRepository(directory)
    const source = {fetchPosts: async () => posts}
    const renderer = {render: async () => {attempts++; return `release-${attempts}`}}
    const coordinator = new PublicationCoordinator({repository, source, renderer, clock: () => 100000})
    try { await run({directory, repository, source, renderer, coordinator, setPosts: value => {posts = value}, attempts: () => attempts}) }
    finally {
        assert.equal(path.dirname(directory), os.tmpdir())
        assert.match(path.basename(directory), /^blog-publication-test-/)
        await fs.rm(directory, {recursive: true, force: true})
    }
}

test('saved cover and body changes become a published release without rebuilding unchanged content', async () => fixture(async f => {
    await f.coordinator.tick()
    assert.equal((await f.repository.load()).activeRelease, 'release-1')
    await f.coordinator.tick()
    assert.equal(f.attempts(), 1)
    f.setPosts([post('<img src="https://images.example/new.jpg">')])
    await f.coordinator.tick()
    const state = await f.repository.load()
    assert.equal(state.phase, 'CURRENT')
    assert.equal(state.activeRelease, 'release-2')
    assert.equal(state.publishedDigest, snapshotDigest([post('<img src="https://images.example/new.jpg">')]))
}))

test('view-count timestamp changes do not trigger a rebuild', async () => fixture(async f => {
    await f.coordinator.tick()
    f.setPosts([post('正文', '2026-10-04')])
    await f.coordinator.tick()
    assert.equal(f.attempts(), 1)
}))

test('failed generation keeps the previous release and can be retried after restart', async () => fixture(async f => {
    await f.coordinator.tick()
    f.setPosts([post('新正文')])
    f.renderer.render = async () => {throw new Error('private provider details')}
    await f.coordinator.tick()
    const failed = await f.repository.load()
    assert.equal(failed.phase, 'FAILED')
    assert.equal(failed.activeRelease, 'release-1')
    assert.doesNotMatch(failed.error, /private provider/)
    const restarted = new PublicationCoordinator({repository: new FilePublicationRepository(f.directory), source: f.source, renderer: {render: async () => 'release-recovered'}, clock: () => 100000})
    await restarted.retry()
    await restarted.tick()
    assert.equal((await f.repository.load()).activeRelease, 'release-recovered')
}))

test('an older build cannot replace a newer saved snapshot', async () => fixture(async f => {
    await f.coordinator.tick()
    f.setPosts([post('版本二')])
    f.renderer.render = async () => {f.setPosts([post('版本三')]); return 'stale-release'}
    await f.coordinator.tick()
    assert.equal((await f.repository.load()).activeRelease, 'release-1')
    assert.equal((await f.repository.load()).phase, 'WAITING')
    f.renderer.render = async () => 'latest-release'
    await f.coordinator.tick()
    assert.equal((await f.repository.load()).activeRelease, 'latest-release')
}))

test('withdrawals publish an empty snapshot and concurrent ticks do not double build', async () => fixture(async f => {
    await f.coordinator.tick()
    f.setPosts([])
    await Promise.all([f.coordinator.tick(), f.coordinator.tick()])
    assert.equal(f.attempts(), 2)
    assert.equal((await f.repository.load()).publishedDigest, snapshotDigest([]))
}))

test('source failure preserves active pages instead of interpreting failure as an empty blog', async () => fixture(async f => {
    await f.coordinator.tick()
    f.source.fetchPosts = async () => {throw new Error('unreachable')}
    await f.coordinator.tick()
    assert.equal((await f.repository.load()).activeRelease, 'release-1')
    assert.equal((await f.repository.load()).phase, 'FAILED')
}))
