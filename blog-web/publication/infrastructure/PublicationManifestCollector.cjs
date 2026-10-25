'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {createHash} = require('node:crypto')
const {parseDocument} = require('htmlparser2')
const yaml = require('js-yaml')
const {extractCssReferences} = require('./PublicationCssReferences.cjs')
const {createManifest, PublicationManifestError} = require('../domain/PublicationManifest.cjs')
const bad = reason => new PublicationManifestError(reason)
const TEXT = new Set(['.html', '.htm', '.css', '.js', '.mjs', '.json', '.xml', '.svg', '.md', '.yml', '.yaml'])
const slash = value => value.split(path.sep).join('/')
const same = (a, b) => process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b
const PRESENTATION_URL = new Set(['fill', 'stroke', 'filter', 'clip-path', 'mask', 'marker',
    'marker-start', 'marker-mid', 'marker-end', 'cursor', 'color-profile'])
function browserUrl(value) {
    if (typeof value !== 'string') return value
    // URL 标准会去掉两端 C0/空格和任意位置的 TAB/CR/LF，清单必须保护浏览器实际读取的值。
    return value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '').replace(/[\t\n\r]/g, '')
}

// 只读受控树，句柄复核身份、大小与修改时间；拒绝重解析点和变化中的文件。
async function readTree(root) {
    const entries = new Map(); let total = 0
    async function visit(filename, depth) {
        if (depth > 64 || !same(await fs.realpath(filename), filename)) throw bad('UNSAFE_PATH')
        const before = await fs.lstat(filename)
        if (before.isSymbolicLink()) throw bad('UNSAFE_PATH')
        if (before.isDirectory()) {
            for (const name of (await fs.readdir(filename)).sort()) await visit(path.join(filename, name), depth + 1)
            return
        }
        if (!before.isFile() || before.size > 16 * 1024 * 1024 || entries.size >= 50000
                || (total += before.size) > 256 * 1024 * 1024) throw bad('LIMIT')
        const handle = await fs.open(filename, 'r')
        try {
            const opened = await handle.stat()
            if (opened.ino !== before.ino || opened.dev !== before.dev || opened.size !== before.size) throw bad('CHANGED')
            const buffer = Buffer.alloc(opened.size + 1); let size = 0
            while (size < buffer.length) {const read = await handle.read(buffer, size, buffer.length - size, size); if (!read.bytesRead) break; size += read.bytesRead}
            const after = await handle.stat(), current = await fs.lstat(filename)
            if (size !== before.size || after.mtimeMs !== opened.mtimeMs || after.ctimeMs !== opened.ctimeMs
                    || current.ino !== opened.ino || current.dev !== opened.dev || !same(await fs.realpath(filename), filename)) throw bad('CHANGED')
            const name = slash(path.relative(root, filename)), content = buffer.subarray(0, size)
            if (TEXT.has(path.extname(name)) && size > 8 * 1024 * 1024) throw bad('LIMIT')
            entries.set(name, content)
        } finally {await handle.close()}
    }
    await visit(root, 0)
    return entries
}
function stripFrontmatter(text) {
    const match = /^(?:\uFEFF)?---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/.exec(text)
    return match ? {data: yaml.load(match[1]), body: text.slice(match[0].length)} : {body: text}
}
function fingerprintEntries(files) {
    const hash = createHash('sha256')
    const sorted = [...files].sort(([left], [right]) => Buffer.compare(Buffer.from(left), Buffer.from(right)))
    for (const [name, bytes] of sorted) hash.update(JSON.stringify([name, bytes.length, createHash('sha256').update(bytes).digest('hex')]) + '\n')
    return hash.digest('hex')
}
async function publicationOutputDigest(directory) {return fingerprintEntries(await readTree(directory))}
async function readFrozen(snapshot, file) {
    const filename = path.join(snapshot.workRoot, file.path)
    if (!same(await fs.realpath(filename), filename) || file.size > 4 * 1024 * 1024) throw bad('CHANGED')
    const handle = await fs.open(filename, 'r')
    try {
        const opened = await handle.stat()
        if (!opened.isFile() || opened.size !== file.size) throw bad('CHANGED')
        const bytes = Buffer.alloc(file.size + 1); let size = 0
        while (size < bytes.length) {const read = await handle.read(bytes, size, bytes.length - size, size); if (!read.bytesRead) break; size += read.bytesRead}
        const current = await fs.lstat(filename), after = await handle.stat()
        if (size !== file.size || current.ino !== opened.ino || current.dev !== opened.dev
                || after.mtimeMs !== opened.mtimeMs || after.ctimeMs !== opened.ctimeMs
                || !same(await fs.realpath(filename), filename)) throw bad('CHANGED')
        const value = bytes.subarray(0, size)
        if (createHash('sha256').update(value).digest('hex') !== file.sha256) throw bad('CHANGED')
        return value
    } finally {await handle.close()}
}
class PublicationManifestCollector {
    constructor({directory, inputs, scriptPolicy, layoutVersion = 1}) {
        if (![1, 2].includes(layoutVersion)) throw bad('UNSUPPORTED')
        this.directory = path.resolve(directory); this.inputs = inputs; this.scriptPolicy = scriptPolicy; this.layoutVersion = layoutVersion
    }
    async source(snapshot) {
        await this.inputs.verify(snapshot)
        const all = await readTree(path.join(snapshot.workRoot, 'source'))
        const files = new Map([...all].map(([name, bytes]) => ['source/' + name, bytes]))
        for (const file of snapshot.files) {
            if (file.path.startsWith('source/')) continue
            files.set(file.path, await readFrozen(snapshot, file))
        }
        const result = await this.#collect(files, snapshot.sourceDigest, true)
        await this.inputs.verify(snapshot)
        return result
    }
    async output(snapshot, releaseId) {
        await this.inputs.verify(snapshot)
        if (releaseId !== snapshot.releaseId || !/^release-[a-f0-9-]{36}$/.test(releaseId)) throw bad('UNSAFE_PATH')
        const files = await readTree(path.join(this.directory, 'releases', releaseId))
        const digest = fingerprintEntries(files)
        const vueFiles = new Set()
        for (const file of snapshot.files.filter(file => file.path.startsWith('.build/app/'))) {
            const name = file.path.slice('.build/app/'.length)
            if (this.layoutVersion === 2) {
                if (name === '.vite' || name.startsWith('.vite/')) continue
                const expected = await readFrozen(snapshot, file), actual = files.get(name)
                if (!actual || !actual.equals(expected)) throw bad('CHANGED')
                vueFiles.add(name)
            } else files.set(file.path, await readFrozen(snapshot, file))
        }
        if (this.layoutVersion === 2 && [...files.keys()].some(name => !name.startsWith('blog/') && !vueFiles.has(name))) throw bad('CHANGED')
        const result = await this.#collect(files, digest, false)
        await this.inputs.verify(snapshot)
        return result
    }
    async #collect(files, digest, source) {
        const links = new Set(), contents = new Set(), visited = new Set(), active = new Set()
        const add = value => {
            value = browserUrl(value)
            if (typeof value !== 'string' || !value || value.startsWith('#')) return
            if (value.length > 1048576 || links.size >= 10000 && !links.has(value)) throw bad('LIMIT')
            if (/^(?:https?:\/\/|\/\/|\/file\/)/i.test(value)) links.add(value)
            if (/^javascript:/i.test(value) && !/^javascript:(?:void\(0\);?|;?)$/i.test(value)) throw bad('UNSUPPORTED')
        }
        const resolve = (uri, owner) => {
            uri = browserUrl(uri)
            if (typeof uri !== 'string' || /[\\\u0000-\u001f]/.test(uri) || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(uri)) throw bad('UNSUPPORTED')
            let decoded; try {decoded = decodeURIComponent(uri.split(/[?#]/)[0])} catch {throw bad('UNSAFE_PATH')}
            if (/[\\\u0000-\u001f]/.test(decoded) || /%2f|%5c/i.test(uri)) throw bad('UNSAFE_PATH')
            const contained = (value, boundary) => {
                const normalized = path.posix.normalize(value)
                if (!normalized.startsWith(boundary)) throw bad('UNSAFE_PATH')
                return normalized
            }
            if (decoded.startsWith('/assets/')) return !source && this.layoutVersion === 2
                ? contained(decoded.slice(1), 'assets/') : contained('.build/app' + decoded, '.build/app/')
            if (decoded.startsWith('/blog/')) {
                const prefix = source ? 'source/' : this.layoutVersion === 2 ? 'blog/' : ''
                return contained(prefix + decoded.slice(6), prefix)
            }
            if (decoded.startsWith('/')) throw bad('UNSUPPORTED')
            const relative = path.posix.normalize(path.posix.join(path.posix.dirname(owner), decoded))
            const boundary = owner.startsWith('.build/app/') ? '.build/app/' : source ? 'source/' : ''
            if (relative === '..' || relative.startsWith('../') || boundary && !relative.startsWith(boundary)) throw bad('UNSAFE_PATH')
            return relative
        }
        let nodes = 0
        const data = (value, owner, depth = 0) => {
            if (++nodes > 200000 || depth > 64) throw bad('LIMIT')
            if (typeof value === 'string') {add(value); if (/<[a-z][\s\S]*>/i.test(value)) html(value, owner, depth + 1)}
            else if (Array.isArray(value)) for (const item of value) data(item, owner, depth + 1)
            else if (value && typeof value === 'object') for (const item of Object.values(value)) data(item, owner, depth + 1)
        }
        const pending = []
        const script = (code, context, owner) => {
            const result = this.scriptPolicy.inspect(code, context)
            for (const uri of result.links || []) add(uri)
            for (const value of result.texts || []) data(value, owner)
            for (const dep of result.dependencies || []) pending.push([resolve(dep, owner), 'script'])
        }
        const css = (text, owner) => {
            const references = extractCssReferences(text)
            references.links.forEach(add)
            references.imports.forEach(uri => pending.push([resolve(uri, owner), 'css']))
        }
        const html = (text, owner, nesting = 0, xml = false) => {
            if (nesting > 64 || /<!DOCTYPE[^>]*\[|<!ENTITY/i.test(text)) throw bad('UNSUPPORTED')
            const document = parseDocument(text, {xmlMode: xml, recognizeCDATA: true, decodeEntities: true})
            const visit = (node, depth) => {
                if (++nodes > 200000 || depth > 128) throw bad('LIMIT')
                const attrs = node.attribs || {}, body = (node.children || []).map(child => child.data || '').join('')
                // 未适配的二级文档/远端图库能绕过当前 DOM 清单，不能当作零引用。
                if (['iframe', 'object', 'embed', 'base', 'set', 'animate', 'animatetransform'].includes((node.name || '').toLowerCase())
                        || /(?:^|\s)fj-gallery(?:\s|$)/.test(attrs.class || '')) throw bad('UNSUPPORTED_DOCUMENT_LOADER')
                if (node.name === 'script') {
                    if (attrs.src) {
                        if (/^(?:https?:|\/\/)/i.test(attrs.src)) script('', {kind: 'remote', uri: attrs.src}, owner)
                        else pending.push([resolve(attrs.src, owner), 'script'])
                    } else if (/^(?:application\/(?:ld\+)?json)$/i.test(attrs.type || '')) {
                        try {data(JSON.parse(body), owner)} catch {throw bad('UNSUPPORTED')}
                    } else script(body, {kind: 'inline'}, owner)
                    return
                }
                if (node.name === 'style') css(body, owner)
                for (const [name, value] of Object.entries(attrs)) {
                    if (name.startsWith('on')) script(value, {kind: 'event', event: name}, owner)
                    else if (name === 'style') css('.inline{' + value + '}', owner)
                    else if (PRESENTATION_URL.has(name)) css('.presentation{' + name + ':' + value + '}', owner)
                    else if (name.endsWith('srcset')) {
                        if (/data:/i.test(value)) throw bad('UNSUPPORTED')
                        for (const candidate of value.split(',')) {
                            const match = /^\s*(\S+)(?:\s+(?:\d+w|\d+(?:\.\d+)?x))?\s*$/.exec(candidate)
                            if (!match) throw bad('UNSUPPORTED')
                            add(match[1])
                        }
                    } else if (name === 'href' && /^javascript:/i.test(value) && !/^javascript:(?:void\(0\);?|;?)$/i.test(value))
                        script(value.slice(11), {kind: 'event', event: 'onclick'}, owner)
                    else if (name.startsWith('data-') || ['src', 'poster', 'href', 'xlink:href', 'background'].includes(name)) add(value)
                }
                if (node.name === 'link' && /(?:^|\s)stylesheet(?:\s|$)/i.test(attrs.rel || '')) pending.push([resolve(attrs.href, owner), 'css'])
                for (const child of node.children || []) {
                    if (child.type === 'cdata') html((child.children || []).map(c => c.data || '').join(''), owner, nesting + 1)
                    else visit(child, depth + 1)
                }
            }
            visit(document, nesting)
        }
        const scan = async (name, requested, depth = 0) => {
            if (depth > 64 || active.has(name)) throw bad('CYCLE')
            if (visited.has(name)) return
            const bytes = files.get(name)
            if (!bytes) throw bad('MISSING_LOCAL_DEPENDENCY')
            const ext = path.posix.extname(name), text = bytes.toString('utf8')
            active.add(name)
            const start = pending.length
            try {
                if (requested === 'css' || ext === '.css') css(text, name)
                else if (requested === 'script' || ['.js', '.mjs'].includes(ext)) {
                    script(text, {kind: name.startsWith('.build/app/') || !source && this.layoutVersion === 2 && !name.startsWith('blog/') ? 'vite' : 'file', filename: name}, name)
                } else if (['.html', '.htm'].includes(ext)) {const parsed = stripFrontmatter(text); data(parsed.data, name); html(parsed.body, name)}
                else if (['.xml', '.svg'].includes(ext)) html(text, name, 0, true)
                else if (ext === '.json') {try {data(JSON.parse(text), name)} catch {throw bad('UNSUPPORTED')}}
                else if (['.yml', '.yaml'].includes(ext)) {try {data(yaml.load(text), name)} catch {throw bad('UNSUPPORTED')}}
                else if (ext === '.md') {const parsed = stripFrontmatter(text); data(parsed.data, name); contents.add(parsed.body)}
                const dependencies = pending.splice(start)
                for (const [child, kind] of dependencies) await scan(child, kind, depth + 1)
                visited.add(name)
            } finally {active.delete(name)}
        }
        for (const name of files.keys()) {
            // Hexo 服务端插件不是浏览器资源；源配置和完整 Vue 产物属于输入清单。
            if (source && !name.startsWith('source/') && !name.startsWith('.build/app/') && !/^_config.*\.yml$/.test(name)) continue
            if (!TEXT.has(path.extname(name)) || name.endsWith('.map')) continue
            // 上游会复制未启用插件的 JS；浏览器可执行根来自所有 HTML 的 script，
            // 及冻结 Vite 图，而不是目录中每一个未被加载的第三方插件文件。
            if (!source && ['.js', '.mjs'].includes(path.extname(name)) && !name.startsWith('.build/app/')
                    && !(this.layoutVersion === 2 && !name.startsWith('blog/'))) continue
            await scan(name)
        }
        return createManifest({sourceDigest: digest, links: [...links].sort(), contents: [...contents], htmlContents: []})
    }
}
module.exports = {PublicationManifestCollector, publicationOutputDigest, readPublicationTree: readTree,
    readFrozenPublicationFile: readFrozen, publicationEntriesDigest: fingerprintEntries}
