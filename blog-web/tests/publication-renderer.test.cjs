const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
const {toPost} = require('../tools/content.cjs')

test('publication renders real HTML with saved images, withdraws old exported posts and leaves source untouched', async () => {
    const root = path.resolve(__dirname, '..')
    const directory = await fs.mkdtemp(path.join(root, '.test-publication-'))
    const filename = path.join(root, '_config.anzhiyu.yml')
    const before = await fs.readFile(filename)
    const originalPosts = async () => {
        const result = {}
        const posts = path.join(root, 'source/_posts')
        let names
        try {names = await fs.readdir(posts)} catch (error) {if (error.code === 'ENOENT') return result; throw error}
        for (const name of names.sort()) if ((await fs.stat(path.join(posts, name))).isFile()) result[name] = await fs.readFile(path.join(posts, name), 'utf8')
        return result
    }
    const postsBefore = await originalPosts()
    try {
        const renderer = new HexoPublicationRenderer({root, directory, dev: true})
        const post = toPost({id: 99, status: 1, title: '自动发布集成测试', createdAt: '2026-09-01T12:00:00', coverUrl: 'https://images.example/new-cover.jpg', contentHtml: '<p>新正文</p><img src="https://images.example/new-body.jpg" onerror="alert(1)">'})
        const release = await renderer.render([post])
        const tree = path.join(directory, 'releases', release)
        const html = await fs.readFile(path.join(tree, 'article/99/index.html'), 'utf8')
        assert.match(html, /https:\/\/images\.example\/new-cover\.jpg/)
        assert.match(html, /https:\/\/images\.example\/new-body\.jpg/)
        assert.doesNotMatch(html, /onerror="alert/)
        await assert.rejects(fs.access(path.join(tree, 'article/11/index.html')))
        const empty = await renderer.render([])
        const home = await fs.readFile(path.join(directory, 'releases', empty, 'index.html'), 'utf8')
        assert.match(home, /暂无文章/)
        await assert.rejects(fs.access(path.join(directory, 'releases', empty, 'article/99/index.html')))
        assert.deepEqual(await fs.readFile(filename), before)
        assert.deepEqual(await originalPosts(), postsBefore)
    } finally {
        assert.equal(path.dirname(directory), root)
        assert.match(path.basename(directory), /^\.test-publication-/)
        await fs.rm(directory, {recursive: true, force: true})
    }
})
