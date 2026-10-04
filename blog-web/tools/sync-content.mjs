import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import content from './content.cjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const target = path.join(root, 'source', '_posts')
const manifestPath = path.join(root, '.content-sync.json')
const ownedName = /^database-article-[1-9]\d{0,18}\.md$/

try {
    const posts = await content.fetchPublished(process.env.BLOG_API_URL || 'http://127.0.0.1:8080')
    await fs.mkdir(target, {recursive: true})
    if ((await fs.lstat(target)).isSymbolicLink()) throw new Error('文章目录不能是符号链接')
    let previous = []
    try {
        previous = JSON.parse(await fs.readFile(manifestPath, 'utf8')).files
    } catch (error) {
        if (error.code !== 'ENOENT') throw error
    }
    if (!Array.isArray(previous) || previous.some((name) => !ownedName.test(name)))
        throw new Error('同步清单无效')
    const next = posts.map((post) => post.name)
    // Validate all existing targets before changing any file. Handwritten posts
    // are never replaced, even if someone used a database-article filename.
    for (const name of new Set([...previous, ...next])) {
        const filename = path.join(target, name)
        try {
            if ((await fs.lstat(filename)).isSymbolicLink()) throw new Error('导出文件不能是符号链接')
            const text = await fs.readFile(filename, 'utf8')
            if (!/^blog_export_owned: true$/m.test(text)) throw new Error(`拒绝覆盖手写文章：${name}`)
        } catch (error) {
            if (error.code !== 'ENOENT') throw error
        }
    }
    for (const post of posts) await fs.writeFile(path.join(target, post.name), post.text)
    for (const name of previous.filter((name) => !next.includes(name))) {
        await fs.rm(path.join(target, name), {force: true})
    }
    await fs.writeFile(manifestPath, JSON.stringify({files: next}, null, 2) + '\n')
    console.log(`已同步 ${posts.length} 篇公开文章；仅清理清单中已撤回的自动导出文章。`)
} catch (error) {
    console.error(`内容同步失败：${error.message}`)
    process.exitCode = 1
}
