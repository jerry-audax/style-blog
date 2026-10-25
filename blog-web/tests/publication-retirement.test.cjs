const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const {randomUUID} = require('node:crypto')
const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
const {publicationOutputDigest} = require('../publication/infrastructure/PublicationManifestCollector.cjs')

async function fixture(run) {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-retirement-test-'))
    const releases = path.join(directory, 'releases'), id = 'release-' + randomUUID(), root = path.join(releases, id)
    try {
        await fs.mkdir(path.join(root, 'nested'), {recursive: true})
        await fs.mkdir(path.join(directory, 'stages'))
        await fs.writeFile(path.join(root, 'first.txt'), 'first')
        await fs.writeFile(path.join(root, 'nested/second.txt'), 'second')
        const info = await fs.lstat(root, {bigint: true})
        const output = {releaseId: id, stageName: 'stage-' + randomUUID(), device: String(info.dev), inode: String(info.ino),
            outputDigest: await publicationOutputDigest(root)}
        const renderer = new HexoPublicationRenderer({root: path.join(directory, 'project'), directory})
        const intent = await renderer.prepareRemoval(output)
        await run({directory, releases, root, output, renderer, intent})
    } finally {
        assert.equal(path.dirname(directory), os.tmpdir()); assert.match(path.basename(directory), /^publication-retirement-test-/)
        await fs.rm(directory, {recursive: true, force: true})
    }
}

test('partial removal accepts already-absent entries and returns only after exact root removal', async () => {
    await fixture(async ({root, output, renderer, intent}) => {
        await fs.unlink(path.join(root, 'first.txt'))
        await renderer.removeOutput(output, intent)
        await assert.rejects(fs.access(root))
        await renderer.removeOutput(output, intent)
    })
})

test('partial removal preserves new files and changed remaining files', async () => {
    for (const mode of ['added', 'changed']) await fixture(async ({root, output, renderer, intent}) => {
        await fs.unlink(path.join(root, 'first.txt'))
        const filename = path.join(root, mode === 'added' ? 'unowned.txt' : 'nested/second.txt')
        await fs.writeFile(filename, 'must remain untouched')
        await assert.rejects(renderer.removeOutput(output, intent))
        assert.equal(await fs.readFile(filename, 'utf8'), 'must remain untouched')
    })
})

test('same-name replacement root is never accepted by a persisted removal intent', async () => {
    await fixture(async ({releases, root, output, renderer, intent}) => {
        const retained = path.join(releases, 'retained-' + randomUUID())
        assert.equal(path.dirname(root), releases); assert.equal(path.dirname(retained), releases)
        await fs.rename(root, retained)
        await fs.mkdir(root)
        await fs.writeFile(path.join(root, 'sentinel.txt'), 'replacement remains')
        await assert.rejects(renderer.removeOutput(output, intent))
        assert.equal(await fs.readFile(path.join(root, 'sentinel.txt'), 'utf8'), 'replacement remains')
        assert.equal(await fs.readFile(path.join(retained, 'first.txt'), 'utf8'), 'first')
    })
})
