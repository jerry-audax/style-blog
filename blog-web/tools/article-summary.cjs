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

module.exports = {normalizeArticleSummary, summaryOnlyPostView, removeEmptyCardSummaries}
