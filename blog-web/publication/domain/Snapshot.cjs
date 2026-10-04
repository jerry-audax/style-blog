'use strict'
const {createHash} = require('node:crypto')

function snapshotDigest(posts) {
    const names = new Set()
    const canonical = posts.map(post => {
        if (!/^database-article-[1-9]\d{0,18}\.md$/.test(post.name) || typeof post.text !== 'string' || names.has(post.name))
            throw new Error('Invalid publication snapshot')
        names.add(post.name)
        // PostgreSQL touches updated_at on counter changes too. Content, cover,
        // taxonomy and visibility determine publication; counters do not.
        const text = post.text.replace(/^(---\r?\n)([\s\S]*?)(\r?\n---)/, (_, open, header, close) =>
            open + header.replace(/^updated:.*(?:\r?\n|$)/m, '') + close)
        return {name: post.name, text}
    }).sort((a, b) => a.name.localeCompare(b.name))
    return createHash('sha256').update(JSON.stringify(canonical)).digest('hex')
}
module.exports = {snapshotDigest}
