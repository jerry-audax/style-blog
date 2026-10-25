const {test} = require('node:test')
const assert = require('node:assert/strict')
const {normalizeArticleSummary, summaryOnlyPostView, removeEmptyCardSummaries} = require('../tools/article-summary.cjs')

test('only visible supplied text becomes an article summary', () => {
    for (const value of [undefined, null, '', ' \n\t', '<p><br></p>', '<p>&nbsp;</p>', '<script>alert(1)</script>', '\u200b\ufeff'])
        assert.equal(normalizeArticleSummary(value), '')
    assert.equal(normalizeArticleSummary('<p>摘要内容 &amp; 安全</p>'), '摘要内容 & 安全')
    assert.equal(normalizeArticleSummary('&lt;img src=x&gt;'), '<img src=x>')
    assert.equal(normalizeArticleSummary({text: 'not a string'}), '')
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
