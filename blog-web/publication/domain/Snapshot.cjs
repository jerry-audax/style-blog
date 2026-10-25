'use strict'
const {createHash} = require('node:crypto')

function snapshotDigest(posts) {
    if (!Array.isArray(posts) || posts.length > 10000) throw new Error('Publication snapshot exceeds safe limit')
    const names = new Set()
    let bytes = 0
    const sorted = posts.map(post => {
        if (!post || typeof post.text !== 'string' || post.text.length > 4 * 1024 * 1024
                || Buffer.byteLength(post.text) > 4 * 1024 * 1024
                || (bytes += Buffer.byteLength(post.text)) > 64 * 1024 * 1024) throw new Error('Publication snapshot exceeds safe limit')
        if (!/^database-article-[1-9]\d{0,18}\.md$/.test(post.name) || typeof post.text !== 'string' || names.has(post.name))
            throw new Error('Invalid publication snapshot')
        names.add(post.name)
        return post
    }).sort((a, b) => a.name.localeCompare(b.name))
    const hash = createHash('sha256').update('[')
    for (let index = 0; index < sorted.length; index++) {
        const post = sorted[index]
        // 保留旧 JSON 数组字节和计数时间忽略规则，仅逐篇编码，不整体分配正文快照。
        const text = post.text.replace(/^(---\r?\n)([\s\S]*?)(\r?\n---)/, (_, open, header, close) =>
            open + header.replace(/^updated:.*(?:\r?\n|$)/m, '') + close)
        if (index) hash.update(',')
        hash.update(JSON.stringify({name: post.name, text}))
    }
    return hash.update(']').digest('hex')
}
module.exports = {snapshotDigest}
