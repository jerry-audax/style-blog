const {test} = require('node:test')
const assert = require('node:assert/strict')
const {createHash} = require('node:crypto')
const {fetchPublished} = require('../tools/content.cjs')
const {snapshotDigest} = require('../publication/domain/Snapshot.cjs')
const {fixture, write} = require('./support/publication-site-fixture.cjs')
const response = value => new Response(JSON.stringify(value), {headers: {'Content-Type': 'application/json'}})

test('semantic digest preserves original JSON-array bytes while ignoring counter-only updated header', () => {
    const posts = [{name: 'database-article-2.md', text: '---\nupdated: "later"\n---\n正文'},
        {name: 'database-article-1.md', text: '---\nupdated: "earlier"\n---\n正文2'}]
    const canonical = posts.map(post => ({name: post.name, text: post.text.replace(/^(---\r?\n)([\s\S]*?)(\r?\n---)/,
        (_, open, header, close) => open + header.replace(/^updated:.*(?:\r?\n|$)/m, '') + close)})).sort((a, b) => a.name.localeCompare(b.name))
    assert.equal(snapshotDigest(posts), createHash('sha256').update(JSON.stringify(canonical)).digest('hex'))
    assert.throws(() => snapshotDigest(Array(10001).fill(posts[0])), /limit/)
    assert.throws(() => snapshotDigest([{name: 'database-article-1.md', text: 'x'.repeat(4 * 1024 * 1024 + 1)}]))
})

test('public export rejects excessive declared totals and oversized actual response bytes before publishing', async () => {
    await assert.rejects(fetchPublished('http://localhost', async () => response({code: 0, data: {total: 10001, records: []}})), /上限/)
    await assert.rejects(fetchPublished('http://localhost', async () => new Response(' '.repeat(4 * 1024 * 1024 + 1))), /上限/)
})

test('deadline covers body reading even when a supplied fetcher ignores abort', async () => {
    let cancelled = false
    const body = new ReadableStream({pull() {return new Promise(() => {})}, cancel() {cancelled = true}})
    let safety
    try {
        const guard = new Promise((_, reject) => {safety = setTimeout(() => reject(new Error('test safety deadline exceeded')), 500)})
        await assert.rejects(Promise.race([fetchPublished('http://localhost', async () => new Response(body), {timeoutMs: 20}), guard]), /超时/)
    } finally {clearTimeout(safety)}
    assert.equal(cancelled, true)
})

test('bounded input probing notices config handwritten and compiled bytes but ignores derived database exports', async () => {
    await fixture(async ({root, inputs}) => {
        assert.equal(typeof inputs.probe, 'function')
        const before = await inputs.probe()
        await write(root, 'source/_posts/database-article-7.md', '---\nblog_export_owned: true\nupdated: today\n---\nderived')
        assert.equal(await inputs.probe(), before)
        await write(root, 'source/_posts/手写.md', '# changed original')
        const handwritten = await inputs.probe()
        assert.notEqual(handwritten, before)
        await write(root, '_config.anzhiyu.yml', 'runtimeshow:\n  enable: false\n')
        const config = await inputs.probe()
        assert.notEqual(config, handwritten)
        await write(root, '.build/app/assets/interaction.js', 'export const changed=true')
        assert.notEqual(await inputs.probe(), config)
    }, {dev: false})
})
