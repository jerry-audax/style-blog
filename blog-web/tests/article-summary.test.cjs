const {test} = require('node:test')
const assert = require('node:assert/strict')
const {normalizeArticleSummary, summaryOnlyPostView, removeEmptyCardSummaries} = require('../tools/article-summary.cjs')
const {arrangeArticleCards} = require('../tools/article-summary.cjs')
const {JSDOM} = require('jsdom')

test('only visible supplied text becomes an article summary', () => {
    for (const value of [undefined, null, '', ' \n\t', '<p><br></p>', '<p>&nbsp;</p>', '<script>alert(1)</script>', '\u200b\ufeff'])
        assert.equal(normalizeArticleSummary(value), '')
    assert.equal(normalizeArticleSummary('<p>摘要内容 &amp; 安全</p>'), '摘要内容 & 安全')
    assert.equal(normalizeArticleSummary('&lt;img src=x&gt;'), '<img src=x>')
    assert.equal(normalizeArticleSummary({text: 'not a string'}), '')
})

test('home cards retain the complete summary and move existing tags between title and summary', () => {
    const summary = '完整摘要与长链接 https://example.com/'.repeat(80)
    const tags = '<span class="article-meta tags"><a class="article-meta__tags" href="/blog/tags/DDD/">DDD</a>' +
        '<a class="article-meta__tags" href="/blog/tags/Java/">Java</a></span>'
    const html = '<div id="recent-posts"><div class="recent-post-item"><div class="recent-post-info">' +
        '<div class="recent-post-info-top"><a class="article-title">标题</a></div>' +
        `<div class="article-meta-wrap"><time>2026-10-26</time>${tags}</div>` +
        `<div class="content">${summary}</div></div></div></div>`
    const output = arrangeArticleCards(html)
    const document = new JSDOM(output).window.document
    const info = document.querySelector('.recent-post-info')
    assert.deepEqual([...info.children].map(node => node.className),
        ['recent-post-info-top', 'site-article-details', 'content'])
    assert.equal(info.querySelector('.content').textContent, summary)
    assert.deepEqual([...info.querySelectorAll('.site-article-tags a')].map(node => node.getAttribute('href')),
        ['/blog/tags/DDD/', '/blog/tags/Java/'])
    assert.equal(info.querySelector('.article-meta-wrap .tags'), null)
    assert.equal(info.querySelector('.site-article-details > .article-meta-wrap time').textContent, '2026-10-26')
    assert.equal(info.querySelector('time').textContent, '2026-10-26')
    assert.equal(document.querySelectorAll('[data-site-article-card]').length, 1)
    assert.equal(arrangeArticleCards(output), output, 'reprocessing must not duplicate tags or wrappers')
})

test('cards without tags or visible summary do not gain placeholder rows', () => {
    const html = '<div id="recent-posts"><article class="recent-post-item"><div class="recent-post-info">' +
        '<div class="recent-post-info-top">标题</div><div class="article-meta-wrap"><time>日期</time></div>' +
        '<div class="content">&nbsp;</div></div></article></div>'
    const document = new JSDOM(arrangeArticleCards(removeEmptyCardSummaries(html))).window.document
    assert.equal(document.querySelector('.site-article-tags'), null)
    assert.equal(document.querySelector('.content'), null)
    assert.equal(document.querySelector('time').textContent, '日期')
})

test('carousel, archive and article detail markup stay byte-for-byte unchanged', () => {
    const html = '<div class="topGroup"><div class="recent-post-item"><div class="recent-post-info">' +
        '<div class="recent-post-info-top">轮播</div><div class="article-meta-wrap"><span class="tags">标签</span></div>' +
        '<div class="content">轮播简介</div></div></div></div>' +
        '<div id="archive"><div class="article-sort-item">归档</div></div>' +
        '<div id="post"><div class="content">正文</div></div><script>const text="<div>";</script>'
    assert.equal(arrangeArticleCards(html), html)
})

test('carousel view cannot fall back to the real article body', () => {
    const post = {title: 'A', content: 'BODY_NEVER_EXCERPT', description: '', path: 'article/1/'}
    assert.equal(summaryOnlyPostView(post).content, '')
    assert.equal(summaryOnlyPostView(post).description, '')
    assert.equal(post.content, 'BODY_NEVER_EXCERPT')
    assert.equal(summaryOnlyPostView({...post, description: '摘要内容'}).description, '摘要内容')
})

test('only known empty card summaries are removed, not article body content', () => {
    const html = '<article><div class="content"> </div></article>' +
        '<div class="recent-post-info"><div class="content">&nbsp;</div><div class="content">摘要</div></div>' +
        '<div class="blog-slider__content"><div class="blog-slider__text"><br></div></div>'
    assert.equal(removeEmptyCardSummaries(html), '<article><div class="content"> </div></article>' +
        '<div class="recent-post-info"><div class="content">摘要</div></div>' +
        '<div class="blog-slider__content"></div>')
})
