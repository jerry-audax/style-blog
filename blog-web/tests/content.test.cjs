const {test} = require('node:test')
const assert = require('node:assert/strict')
const {
    articleId,
    safeUrl,
    sanitizeContent,
    toPost,
    fetchPublished,
} = require('../tools/content.cjs')
const article = {
    id: 19,
    title: 'DDD: "架构"',
    status: 1,
    publishTime: '2026-09-01T10:00:00',
    contentHtml: '<h2>真实正文</h2>',
    categoryName: '系统设计',
    tagNames: ['Java'],
}

test('export keeps database identity, HTML, taxonomy and quoted frontmatter', () => {
    const post = toPost(article)
    assert.equal(post.name, 'database-article-19.md')
    assert.match(post.text, /permalink: article\/19\//)
    assert.match(post.text, /blog_article_id: "19"/)
    assert.match(post.text, /categories: \["系统设计"\]/)
    assert.match(post.text, /<h2>真实正文<\/h2>/)
})
test('rejects drafts, missing content, malformed IDs and unsafe covers', () => {
    assert.throws(() => toPost({...article, status: 0}))
    assert.throws(() => toPost({...article, contentHtml: ''}))
    for (const id of ['../../x', '-1', '0', '1.1']) assert.throws(() => articleId(id))
    assert.equal(safeUrl('javascript:alert(1)'), '')
    assert.equal(safeUrl('//evil.example/x'), '')
})
test('rendered HTML sanitation removes executable content but retains code and images', () => {
    const safe = sanitizeContent(
        '<script>alert(1)</script><img src="https://files.example/x" onerror="alert(1)"><a href="javascript:alert(1)">link</a><pre><code>&lt;script&gt;</code></pre>',
    )
    assert.doesNotMatch(safe, /onerror|javascript:|<script>/)
    assert.match(safe, /&lt;script&gt;/)
    assert.match(safe, /https:\/\/files.example\/x/)
})
test('pagination exports public content without identity or detail read side effects', async () => {
    const requests = []
    const result = await fetchPublished('http://127.0.0.1:8080', async (url, options) => {
        requests.push({url: String(url), options})
        const current = requests.length
        return {
            ok: true,
            json: async () => ({code: 0, data: {total: 2, records: [{...article, id: current}]}}),
        }
    })
    assert.equal(result.length, 2)
    assert.match(requests[1].url, /pageNum=2/)
    assert.match(requests[0].url, /status=1/)
    assert.equal(requests[0].options.headers, undefined)
    assert.ok(requests.every((request) => request.url.includes('/article/list')))
})
test('partial or duplicate exports fail without returning a replacement snapshot', async () => {
    let page = 0
    await assert.rejects(
        fetchPublished('http://localhost', async () => ({
            ok: true,
            json: async () => ({code: 0, data: {total: 2, records: ++page === 1 ? [article] : []}}),
        })),
        /分页不完整/,
    )
    await assert.rejects(
        fetchPublished('http://localhost', async () => ({
            ok: true,
            json: async () => ({code: 0, data: {total: 2, records: [article]}}),
        })),
        /重复/,
    )
})
