'use strict'
const sanitizeHtml = require('sanitize-html')
const {unescapeHTML} = require('hexo-util')
const {parseDocument} = require('htmlparser2')

function normalizeArticleSummary(value) {
    if (typeof value !== 'string') return ''
    return unescapeHTML(sanitizeHtml(value, {allowedTags: [], allowedAttributes: {}}))
        .replace(/[\s\u200b\ufeff]+/gu, ' ').trim()
}

function summaryOnlyPostView(post) {
    // Do not mutate Hexo's live collection: article details still need the body.
    return {...post, content: '', description: normalizeArticleSummary(post.description)}
}

function removeEmptyCardSummaries(html) {
    // Locate exact generated nodes and remove original source ranges. Avoid
    // serializing the whole page (scripts, SVGs and theme markup stay untouched).
    const document = parseDocument(html, {withStartIndices: true, withEndIndices: true})
    const removals = []
    const hasClass = (node, name) => node.attribs?.class?.split(/\s+/).includes(name)
    function visit(node) {
        const cardSummary = hasClass(node, 'content') && hasClass(node.parent, 'recent-post-info')
        const carouselSummary = hasClass(node, 'blog-slider__text') && hasClass(node.parent, 'blog-slider__content')
        if ((cardSummary || carouselSummary) &&
            !normalizeArticleSummary(html.slice(node.startIndex, node.endIndex + 1))) {
            removals.push([node.startIndex, node.endIndex + 1])
        } else for (const child of node.children || []) visit(child)
    }
    visit(document)
    for (const [start, end] of removals.sort((a, b) => b[0] - a[0]))
        html = html.slice(0, start) + html.slice(end)
    return html
}

function arrangeArticleCards(html) {
    const document = parseDocument(html, {withStartIndices: true, withEndIndices: true})
    const edits = []
    const hasClass = (node, name) => node.attribs?.class?.split(/\s+/).includes(name)
    const source = node => html.slice(node.startIndex, node.endIndex + 1)
    function visit(node) {
        if (node.parent?.attribs?.id === 'recent-posts' && hasClass(node, 'recent-post-item')) {
            if (Object.hasOwn(node.attribs, 'data-site-article-card')) return
            const info = node.children?.find(child => hasClass(child, 'recent-post-info'))
            const top = info?.children?.find(child => hasClass(child, 'recent-post-info-top'))
            const meta = info?.children?.find(child => hasClass(child, 'article-meta-wrap'))
            if (!top || !meta) return
            const tags = meta.children?.find(child => hasClass(child, 'tags'))
            let metadata = source(meta)
            // 只移动首页卡片的原标签节点，保留链接与日期；不重新序列化脚本或主题 HTML。
            if (tags) {
                metadata = metadata.slice(0, tags.startIndex - meta.startIndex) +
                    metadata.slice(tags.endIndex + 1 - meta.startIndex)
            }
            edits.push([meta.startIndex, meta.endIndex + 1, ''])
            const tagRow = tags ? `<div class="site-article-tags">${source(tags)}</div>` : ''
            edits.push([top.endIndex + 1, top.endIndex + 1,
                `<div class="site-article-details">${tagRow}${metadata}</div>`])
            const marker = node.startIndex + node.name.length + 1
            edits.push([marker, marker, ' data-site-article-card'])
            return
        }
        for (const child of node.children || []) visit(child)
    }
    visit(document)
    for (const [start, end, replacement] of edits.sort((a, b) => b[0] - a[0] || b[1] - a[1]))
        html = html.slice(0, start) + replacement + html.slice(end)
    return html
}

module.exports = {normalizeArticleSummary, summaryOnlyPostView, removeEmptyCardSummaries, arrangeArticleCards}
