const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const {fixture, write} = require('./support/publication-site-fixture.cjs')
const post = text => ({name: 'database-article-11.md', text: `---\nblog_export_owned: true\n---\n${text}`})

test('freeze produces an immutable independent byte snapshot including handwritten and database posts', async () => fixture(async ({root, inputs}) => {
    const snapshot = await inputs.freeze([post('数据库正文')])
    assert.notEqual(snapshot.workRoot, root)
    assert.match(snapshot.releaseId, /^release-[a-f0-9-]{36}$/)
    assert.match(snapshot.sourceDigest, /^[a-f0-9]{64}$/)
    assert.ok(Object.isFrozen(snapshot)); assert.ok(Object.isFrozen(snapshot.files)); assert.ok(snapshot.files.every(Object.isFrozen))
    const names = snapshot.files.map(file => file.path)
    assert.ok(names.includes('source/_posts/手写.md')); assert.ok(names.includes('source/_posts/database-article-11.md'))
    await write(root, 'source/_posts/手写.md', '原目录后来修改')
    assert.equal(await fs.readFile(path.join(snapshot.workRoot, 'source/_posts/手写.md'), 'utf8'), '# 原来手写内容')
    assert.deepEqual(await fs.readFile(path.join(snapshot.workRoot, 'source/图片.bin')), Buffer.from([0, 255, 128, 42]))
    assert.equal(JSON.parse(await fs.readFile(path.join(snapshot.workRoot, 'package.json'), 'utf8')).workspaces, undefined)
    await inputs.verify(snapshot)
    await inputs.dispose(snapshot)
    await assert.rejects(fs.access(snapshot.workRoot))
    assert.equal(await fs.readFile(path.join(root, 'source/_posts/手写.md'), 'utf8'), '原目录后来修改')
}))

test('tampering with a frozen file or adding a source file cannot be accepted by recomputing digest', async () => fixture(async ({inputs}) => {
    for (const name of ['_config.yml', 'source/added.md']) {
        const snapshot = await inputs.freeze([])
        await write(snapshot.workRoot, name, 'changed frozen bytes')
        await assert.rejects(inputs.verify(snapshot))
        await inputs.dispose(snapshot)
    }
}))

test('config, handwritten posts and database bytes all contribute deterministically to sourceDigest', async () => fixture(async ({root, inputs}) => {
    const first = await inputs.freeze([post('one')]), same = await inputs.freeze([post('one')])
    assert.equal(first.sourceDigest, same.sourceDigest)
    await write(root, '_config.anzhiyu.yml', 'avatar: /file/blog/new.png')
    const config = await inputs.freeze([post('one')]); assert.notEqual(config.sourceDigest, first.sourceDigest)
    await write(root, 'source/_posts/手写.md', '# 新的手写内容')
    const handwritten = await inputs.freeze([post('one')]); assert.notEqual(handwritten.sourceDigest, config.sourceDigest)
    const database = await inputs.freeze([post('two')]); assert.notEqual(database.sourceDigest, handwritten.sourceDigest)
    for (const snapshot of [first, same, config, handwritten, database]) await inputs.dispose(snapshot)
}))

test('unsafe source links and forged cleanup targets cannot read or delete outside the work area', async () => fixture(async ({root, inputs, temporary}) => {
    const outside = path.join(temporary, 'outside'); await write(outside, 'sentinel.txt', 'must survive')
    await fs.symlink(outside, path.join(root, 'source/escape'), 'junction')
    await assert.rejects(inputs.freeze([]))
    await fs.unlink(path.join(root, 'source/escape'))
    await assert.rejects(inputs.dispose({workRoot: outside, releaseId: 'release-fake'}))
    assert.equal(await fs.readFile(path.join(outside, 'sentinel.txt'), 'utf8'), 'must survive')
    await assert.rejects(inputs.freeze([{name: '../escape.md', text: 'x'}]))
}))

test('handwritten database-name collisions are retained and oversized input is never truncated', async () => fixture(async ({root, inputs}) => {
    await write(root, 'source/_posts/database-article-11.md', '# handwritten collision')
    await assert.rejects(inputs.freeze([post('new')]))
    assert.equal(await fs.readFile(path.join(root, 'source/_posts/database-article-11.md'), 'utf8'), '# handwritten collision')
    await fs.unlink(path.join(root, 'source/_posts/database-article-11.md'))
    await write(root, 'source/large.bin', Buffer.alloc(4 * 1024 * 1024 + 1))
    await assert.rejects(inputs.freeze([]))
}))

test('owned old exports disappear only in the frozen copy, not from project sources', async () => fixture(async ({root, inputs}) => {
    await write(root, 'source/_posts/database-article-99.md', '---\nblog_export_owned: true\n---\nold export')
    const snapshot = await inputs.freeze([post('replacement')])
    assert.notEqual(snapshot.workRoot, root)
    await assert.rejects(fs.access(path.join(snapshot.workRoot, 'source/_posts/database-article-99.md')))
    assert.match(await fs.readFile(path.join(root, 'source/_posts/database-article-99.md'), 'utf8'), /old export/)
    await inputs.dispose(snapshot)
}))

test('real Hexo renders frozen bytes after original changes and leaves the original tree intact', async () => fixture(async ({root, directory, inputs}) => {
    const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
    await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
    const renderer = new HexoPublicationRenderer({root, directory, dev: true, inputs})
    assert.equal(typeof renderer.renderFrozen, 'function')
    const snapshot = await inputs.freeze([])
    const originalConfig = await fs.readFile(path.join(root, '_config.yml'))
    await write(root, 'source/index.html', '---\nlayout: false\n---\n<!doctype html><html><body>后来修改</body></html>')
    const release = await renderer.renderFrozen(snapshot)
    assert.equal(release, snapshot.releaseId)
    const html = await fs.readFile(path.join(directory, 'releases', release, 'index.html'), 'utf8')
    assert.match(html, /冻结正文/); assert.doesNotMatch(html, /后来修改/)
    assert.match(await fs.readFile(path.join(root, 'source/index.html'), 'utf8'), /后来修改/)
    assert.deepEqual(await fs.readFile(path.join(root, '_config.yml')), originalConfig)
    await inputs.verify(snapshot); await inputs.dispose(snapshot)
}))

test('tampered snapshots and escaped release directories are rejected before publication', async () => fixture(async ({root, directory, inputs, temporary}) => {
    const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
    await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
    const renderer = new HexoPublicationRenderer({root, directory, dev: true, inputs})
    assert.equal(typeof renderer.renderFrozen, 'function')
    const tampered = await inputs.freeze([])
    await write(tampered.workRoot, 'source/index.html', 'changed')
    await assert.rejects(renderer.renderFrozen(tampered))
    await inputs.dispose(tampered)
    const outside = path.join(temporary, 'outside'); await fs.mkdir(outside)
    await fs.symlink(outside, path.join(directory, 'releases'), 'junction')
    const clean = await inputs.freeze([])
    await assert.rejects(renderer.renderFrozen(clean))
    assert.deepEqual(await fs.readdir(outside), [])
    await inputs.dispose(clean)
}))

test('a build that rewrites frozen inputs cannot create a release', async () => fixture(async ({root, directory, inputs}) => {
    const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
    await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
    await write(root, 'scripts/change-input.js', "require('node:fs').writeFileSync('source/index.html', '<!doctype html><html><body>modified during build</body></html>')")
    const snapshot = await inputs.freeze([])
    const renderer = new HexoPublicationRenderer({root, directory, dev: true, inputs})
    await assert.rejects(renderer.renderFrozen(snapshot))
    await assert.rejects(fs.access(path.join(directory, 'releases', snapshot.releaseId)))
    assert.match(await fs.readFile(path.join(root, 'source/index.html'), 'utf8'), /冻结正文/)
    await inputs.dispose(snapshot)
}))

test('timed out Hexo exits before its isolated work directory is disposed', async () => fixture(async ({root, directory, inputs}) => {
    const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
    await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
    const snapshot = await inputs.freeze([])
    const renderer = new HexoPublicationRenderer({root, directory, dev: true, inputs, timeoutMs: 1})
    await assert.rejects(renderer.renderFrozen(snapshot))
    await inputs.dispose(snapshot)
    await assert.rejects(fs.access(snapshot.workRoot))
    assert.match(await fs.readFile(path.join(root, 'source/index.html'), 'utf8'), /冻结正文/)
}))

test('effective Hexo source, public and theme paths cannot escape frozen locations', async () => fixture(async ({root, directory, inputs, temporary}) => {
    const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
    await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
    const renderer = new HexoPublicationRenderer({root, directory, dev: true, inputs})
    const outside = path.join(temporary, 'outside'); await write(outside, 'index.html', 'external sentinel')
    const config = await fs.readFile(path.join(root, '_config.yml'), 'utf8')
    const variations = [config.replace(/^public_dir:.*$/m, 'public_dir: ' + JSON.stringify(outside)),
        config.replace(/^public_dir:.*$/m, 'public_dir: ../outside'),
        config + 'source_dir: ' + JSON.stringify(path.join(root, 'source')) + '\n',
        config + 'source_dir: ../root/source\n', config.replace('theme: false', 'theme: ../../outside')]
    for (const variation of variations) {
        await write(root, '_config.yml', variation)
        await assert.rejects(async () => {
            const snapshot = await inputs.freeze([])
            try {await renderer.renderFrozen(snapshot)} finally {await inputs.dispose(snapshot)}
        })
        assert.equal(await fs.readFile(path.join(outside, 'index.html'), 'utf8'), 'external sentinel')
    }
}))

test('Hexo work output ancestry cannot be replaced by a junction', async () => fixture(async ({root, directory, inputs, temporary}) => {
    const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
    await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
    const renderer = new HexoPublicationRenderer({root, directory, dev: true, inputs})
    const outside = path.join(temporary, 'outside'); await write(outside, 'index.html', 'external sentinel')
    for (const target of ['.build/blog', '.build']) {
        const snapshot = await inputs.freeze([])
        await fs.mkdir(path.dirname(path.join(snapshot.workRoot, target)), {recursive: true})
        await fs.symlink(outside, path.join(snapshot.workRoot, target), 'junction')
        await assert.rejects(renderer.renderFrozen(snapshot))
        assert.equal(await fs.readFile(path.join(outside, 'index.html'), 'utf8'), 'external sentinel')
        await fs.unlink(path.join(snapshot.workRoot, target)); await inputs.dispose(snapshot)
    }
}))

test('new effective theme and cache inputs or unknown empty directories invalidate a snapshot', async () => fixture(async ({inputs}) => {
    for (const name of ['themes/anzhiyu/layout/index.pug', 'db.json', '_config.other.yml', 'scaffolds/post.md', 'unexpected-empty/']) {
        const snapshot = await inputs.freeze([])
        if (name.endsWith('/')) await fs.mkdir(path.join(snapshot.workRoot, name))
        else await write(snapshot.workRoot, name, name === 'db.json' ? '{}' : 'changed effective input')
        await assert.rejects(inputs.verify(snapshot))
        await inputs.dispose(snapshot)
    }
}))

test('oversized export totals and counts reject before whole-input digesting or creating work', async () => fixture(async ({directory, inputs}) => {
    const chunk = 'x'.repeat(4 * 1024 * 1024)
    for (const posts of [Array.from({length: 17}, (_, i) => ({name: `database-article-${i + 1}.md`, text: chunk})),
        Array.from({length: 10001}, (_, i) => ({name: `database-article-${i + 1}.md`, text: 'x'}))]) {
        let bulkCalls = 0
        posts.map = () => {bulkCalls++; throw new Error('bulk digest reached')}
        await assert.rejects(inputs.freeze(posts), /Publication inputs cannot be safely frozen/)
        assert.equal(bulkCalls, 0)
        await assert.rejects(fs.access(directory))
    }
}))

test('combined retained-source and export budget rejects before writing any database export', async t => fixture(async ({root, inputs}) => {
    const realWrite = fs.writeFile, exportsWritten = []
    t.mock.method(fs, 'writeFile', async function (filename, ...args) {
        if (/database-article-[0-9]+\.md$/.test(String(filename))) exportsWritten.push(String(filename))
        return realWrite.call(fs, filename, ...args)
    })
    const text = 'x'.repeat(4 * 1024 * 1024)
    const posts = Array.from({length: 16}, (_, i) => ({name: `database-article-${i + 1}.md`, text}))
    await assert.rejects(inputs.freeze(posts))
    assert.deepEqual(exportsWritten, [])
    assert.equal(await fs.readFile(path.join(root, 'source/_posts/手写.md'), 'utf8'), '# 原来手写内容')
}))

test('generated routes cannot traverse outside the owned Hexo output', async () => fixture(async ({root, directory, inputs, temporary}) => {
    const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
    await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
    const outside = path.join(temporary, 'outside'); await write(outside, 'index.html', 'external sentinel')
    await write(root, 'scripts/route-mistake.js', "hexo.extend.generator.register('route-mistake', () => [{path:'../../../../outside/index.html',data:'mistaken output'}])")
    const snapshot = await inputs.freeze([])
    const renderer = new HexoPublicationRenderer({root, directory, dev: true, inputs})
    try {
        await assert.rejects(renderer.renderFrozen(snapshot))
        assert.equal(await fs.readFile(path.join(outside, 'index.html'), 'utf8'), 'external sentinel')
    } finally {await inputs.dispose(snapshot)}
}))
