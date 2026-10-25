'use strict'
const sanitizeHtml = require('sanitize-html')
const {escapeHTML} = require('hexo-util')
const {normalizeArticleSummary} = require('./article-summary.cjs')

function articleId(value) {
    if (typeof value === 'number' && !Number.isSafeInteger(value))
        throw new Error('文章 ID 超出 JavaScript 安全范围，API 需要返回字符串 ID')
    const id = String(value)
    if (!/^[1-9]\d{0,18}$/.test(id)) throw new Error('文章 ID 必须为正整数')
    return id
}

function safeUrl(value) {
    if (typeof value !== 'string') return ''
    return /^(https?:\/\/|\/(?!\/))/.test(value) ? value : ''
}

function sanitizeContent(content) {
    return sanitizeHtml(content, {
        allowedTags: [
            ...sanitizeHtml.defaults.allowedTags,
            'img',
            'figure',
            'figcaption',
            'del',
            's',
            'input',
        ],
        allowedAttributes: {
            '*': ['id', 'class'],
            a: ['href', 'title', 'target', 'rel'],
            img: ['src', 'alt', 'title', 'width', 'height', 'loading'],
            th: ['colspan', 'rowspan'],
            td: ['colspan', 'rowspan'],
            ol: ['start'],
            code: ['class'],
            input: ['type', 'checked', 'disabled'],
        },
        allowedSchemes: ['http', 'https', 'mailto'],
        allowedSchemesByTag: {img: ['http', 'https']},
        allowProtocolRelative: false,
        transformTags: {
            a: (tagName, attribs) => ({tagName, attribs: {...attribs, rel: 'noopener noreferrer'}}),
            input: () => ({tagName: 'input', attribs: {type: 'checkbox', disabled: 'disabled'}}),
        },
    })
}

function toPost(article) {
    const id = articleId(article.id)
    if (article.status !== 1 || article.deleted === 1)
        throw new Error(`文章 ${id} 不是公开已发布内容`)
    if (typeof article.title !== 'string' || !article.title.trim())
        throw new Error(`文章 ${id} 缺少标题`)
    const body = article.contentHtml || article.contentMd
    if (typeof body !== 'string' || !body.trim()) throw new Error(`文章 ${id} 缺少正文，停止同步`)
    const date = article.publishTime || article.createdAt
    if (typeof date !== 'string' || !Number.isFinite(Date.parse(date)))
        throw new Error(`文章 ${id} 发布时间无效`)
    const quote = (value) => JSON.stringify(String(value))
    const fields = [
        '---',
        'layout: post',
        `title: ${quote(article.title)}`,
        `date: ${quote(date)}`,
        `updated: ${quote(article.updatedAt || date)}`,
        `permalink: article/${id}/`,
        `blog_article_id: ${quote(id)}`,
        'blog_export_owned: true',
        'comments: false',
        `description: ${quote(escapeHTML(normalizeArticleSummary(article.summary)))}`,
        `cover: ${quote(safeUrl(article.coverUrl) || '/blog/img/javerry-sky.webp')}`,
    ]
    if (article.categoryName) fields.push(`categories: [${quote(article.categoryName)}]`)
    if (Array.isArray(article.tagNames))
        fields.push(`tags: [${article.tagNames.map(quote).join(', ')}]`)
    fields.push('---', '', body, '')
    // HTML and Markdown are both supported. The Hexo post-render filter sanitizes
    // the rendered HTML, so Markdown code samples are not altered before parsing.
    return {name: `database-article-${id}.md`, text: fields.join('\n')}
}

async function readPublicPage(url, fetcher, {timeoutMs, signal}) {
    const controller = new AbortController()
    let rejectDeadline, reader, response
    const interruption = new Promise((_, reject) => {rejectDeadline = reject})
    const interrupt = message => {controller.abort(); rejectDeadline(new Error(message))}
    const stopped = () => interrupt('公开文章读取已停止')
    const timer = setTimeout(() => interrupt('公开文章读取超时'), timeoutMs)
    if (signal) {if (signal.aborted) stopped(); else signal.addEventListener('abort', stopped, {once: true})}
    try {
        // 时限覆盖 fetch 和实际 body，而不是只等响应头；不读 Cookie/管理身份。
        response = await Promise.race([Promise.resolve().then(() => fetcher(url, {signal: controller.signal,
            redirect: 'error', credentials: 'omit'})), interruption])
        if (!response.ok) throw new Error('公开文章接口请求失败')
        const declared = response.headers?.get('content-length')
        if (declared !== null && declared !== undefined && (!/^\d+$/.test(declared) || Number(declared) > 4 * 1024 * 1024))
            throw new Error('公开文章响应超过安全上限')
        if (!response.body || typeof response.body.getReader !== 'function') throw new Error('公开文章接口结构无效')
        reader = response.body.getReader()
        const chunks = []; let bytes = 0
        while (true) {
            const result = await Promise.race([reader.read(), interruption])
            if (result.done) break
            if (!(result.value instanceof Uint8Array)) throw new Error('公开文章接口结构无效')
            if ((bytes += result.value.byteLength) > 4 * 1024 * 1024) throw new Error('公开文章响应超过安全上限')
            chunks.push(Buffer.from(result.value))
        }
        try {return JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(Buffer.concat(chunks, bytes)))}
        catch {throw new Error('公开文章接口结构无效')}
    } finally {
        clearTimeout(timer)
        signal?.removeEventListener('abort', stopped)
        if (reader) void reader.cancel().catch(() => {})
        else if (response?.body) void response.body.cancel().catch(() => {})
        controller.abort()
    }
}

async function fetchPublished(baseUrl, fetcher = fetch, {timeoutMs = 10000, signal} = {}) {
    const base = new URL(baseUrl)
    if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password)
        throw new Error('公开 API 地址无效')
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 10000) throw new Error('公开 API 时限无效')
    const articles = [],
        seen = new Set()
    let expectedTotal, exportedBytes = 0
    for (let page = 1; page <= 10000; page++) {
        const url = new URL('/api/content/article/list', base)
        url.search = new URLSearchParams({status: '1', pageNum: String(page), pageSize: '50'})
        const result = await readPublicPage(url, fetcher, {timeoutMs, signal})
        const data = result.data
        if (result.code !== 0 || !data || !Array.isArray(data.records))
            throw new Error('公开文章接口结构无效')
        const total = Number(data.total)
        if (!Number.isSafeInteger(total) || total < 0) throw new Error('文章总数无效')
        if (total > 10000 || data.records.length > 50 || articles.length + data.records.length > 10000)
            throw new Error('公开文章数量超过安全上限')
        if (expectedTotal === undefined) expectedTotal = total
        if (total !== expectedTotal) throw new Error('同步期间文章数量变化，请重试')
        for (const article of data.records) {
            const id = articleId(article.id)
            if (seen.has(id)) throw new Error('分页文章重复，请重试')
            seen.add(id)
            const post = toPost(article), size = Buffer.byteLength(post.text)
            if (size > 4 * 1024 * 1024 || (exportedBytes += size) > 64 * 1024 * 1024) throw new Error('文章导出超过安全上限')
            articles.push(post)
        }
        if (articles.length === total) return articles
        if (!data.records.length || articles.length > total) throw new Error('文章分页不完整，请重试')
    }
    throw new Error('文章分页超过安全上限')
}

module.exports = {articleId, safeUrl, sanitizeContent, toPost, fetchPublished}
