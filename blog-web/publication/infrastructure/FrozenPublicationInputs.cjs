'use strict'
const fs = require('node:fs/promises')
const {constants} = require('node:fs')
const path = require('node:path')
const {randomUUID, createHash} = require('node:crypto')
const {validateHexoPaths} = require('./HexoPublicationPaths.cjs')
const {validateDescriptor} = require('../domain/PublicationAttempt.cjs')
const NAMES = ['_config.yml', '_config.anzhiyu.yml', 'source', 'scripts', 'licenses', 'package.json',
    'tools/content.cjs', 'tools/article-summary.cjs', 'tools/media-loading.cjs', 'tools/site-integration.cjs', 'tools/vendor-assets.cjs', 'tools/lightbox-selection.cjs']
const MAX_FILE = 4 * 1024 * 1024, MAX_TOTAL = 64 * 1024 * 1024, MAX_FILES = 10000
const bad = () => new Error('Publication inputs cannot be safely frozen or verified')
const samePath = (left, right) => process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right
async function realDirectory(directory) {
    const info = await fs.lstat(directory)
    if (!info.isDirectory() || info.isSymbolicLink() || !samePath(await fs.realpath(directory), directory)) throw bad()
    return info
}
function sameFile(left, right) {return left.dev === right.dev && left.ino === right.ino}
function checkedPosts(posts) {
    if (!Array.isArray(posts) || posts.length > MAX_FILES) throw bad()
    const fixed = [], names = new Set(); let total = 0
    for (const post of posts) {
        if (!post || typeof post.name !== 'string' || !/^database-article-[1-9]\d{0,18}\.md$/.test(post.name)
                || typeof post.text !== 'string' || names.has(post.name)) throw bad()
        const size = Buffer.byteLength(post.text)
        if (size > MAX_FILE || (total += size) > MAX_TOTAL) throw bad()
        names.add(post.name); fixed.push({name: post.name, text: post.text})
    }
    return {posts: fixed, size: total}
}
async function bytes(filename, boundary) {
    if (!samePath(await fs.realpath(filename), filename) || !filename.startsWith(boundary + path.sep)) throw bad()
    const before = await fs.lstat(filename)
    if (!before.isFile() || before.isSymbolicLink() || before.size > MAX_FILE) throw bad()
    const file = await fs.open(filename, constants.O_RDONLY | (constants.O_NOFOLLOW || 0))
    try {
        const opened = await file.stat()
        if (!sameFile(before, opened) || !opened.isFile() || opened.size > MAX_FILE) throw bad()
        const buffer = Buffer.alloc(opened.size + 1); let size = 0
        while (size < buffer.length) {
            const read = await file.read(buffer, size, buffer.length - size, size)
            if (read.bytesRead === 0) break
            size += read.bytesRead
        }
        const after = await file.stat()
        if (size > MAX_FILE || size !== opened.size || opened.mtimeMs !== after.mtimeMs || opened.ctimeMs !== after.ctimeMs
            || !samePath(await fs.realpath(filename), filename) || !sameFile(opened, await fs.lstat(filename))) throw bad()
        return buffer.subarray(0, size)
    } finally {await file.close()}
}

class FrozenPublicationInputs {
    #owned = new WeakMap()
    constructor({root, directory, dev = false}) {
        this.root = path.resolve(root); this.directory = path.resolve(directory); this.dev = dev
        if (samePath(this.root, this.directory) || ['source', 'scripts', 'licenses', 'tools'].some(name =>
            this.directory === path.join(this.root, name) || this.directory.startsWith(path.join(this.root, name) + path.sep))) throw bad()
        // 生产发布必须冻结编译后的完整图，不能只冻结指向可变文件的 manifest。
        this.names = Object.freeze([...NAMES, ...(dev ? [] : ['.build/app'])])
    }
    async freeze(posts) {
        // Validate bounded exports before any whole-input hashing, filesystem creation or copying.
        const checked = checkedPosts(posts), fixedPosts = checked.posts
        const rootStamp = await realDirectory(this.root)
        await fs.mkdir(this.directory, {recursive: true}); await realDirectory(this.directory)
        const workRoot = path.join(this.directory, 'work-' + randomUUID())
        await fs.mkdir(workRoot)
        const stamp = await realDirectory(workRoot)
        try {
            const budget = {size: 0, count: 0}
            for (const name of this.names) await this.#copy(path.join(this.root, name), path.join(workRoot, name), budget)
            if (!sameFile(rootStamp, await realDirectory(this.root))) throw bad()
            const config = JSON.parse(await fs.readFile(path.join(workRoot, 'package.json'), 'utf8'))
            delete config.workspaces
            await fs.writeFile(path.join(workRoot, 'package.json'), JSON.stringify(config))
            const postDirectory = path.join(workRoot, 'source/_posts')
            await fs.mkdir(postDirectory, {recursive: true})
            for (const name of await fs.readdir(postDirectory)) {
                if (!/^database-article-[1-9]\d{0,18}\.md$/.test(name)) continue
                const filename = path.join(postDirectory, name)
                if (!/^blog_export_owned: true$/m.test((await bytes(filename, workRoot)).toString('utf8'))) throw bad()
                await fs.unlink(filename)
            }
            await validateHexoPaths(workRoot)
            const retained = await this.#manifest(workRoot)
            if (retained.length + fixedPosts.length > MAX_FILES
                    || retained.reduce((total, file) => total + file.size, 0) + checked.size > MAX_TOTAL) throw bad()
            for (const post of fixedPosts) {
                await fs.writeFile(path.join(postDirectory, post.name), post.text, {flag: 'wx'})
            }
            const files = await this.#manifest(workRoot)
            const sourceDigest = createHash('sha256').update(JSON.stringify({protocolVersion: 1, files})).digest('hex')
            const snapshot = Object.freeze({releaseId: 'release-' + randomUUID(), workRoot, sourceDigest,
                files: Object.freeze(files), postNames: Object.freeze(fixedPosts.map(post => post.name))})
            const inventory = await this.#inventory(workRoot)
            this.#owned.set(snapshot, {stamp, phase: 'FROZEN', inventory, initialInventory: inventory})
            return snapshot
        } catch (error) {await this.#cleanup(workRoot, stamp); throw error}
    }
    async #copy(source, target, budget) {
        const info = await fs.lstat(source)
        if (info.isSymbolicLink() || !samePath(await fs.realpath(source), source)) throw bad()
        if (info.isDirectory()) {
            await fs.mkdir(target, {recursive: true})
            for (const name of await fs.readdir(source)) await this.#copy(path.join(source, name), path.join(target, name), budget)
            if (!sameFile(info, await fs.lstat(source))) throw bad()
        } else {
            const content = await bytes(source, this.root)
            if (++budget.count > MAX_FILES || (budget.size += content.length) > MAX_TOTAL) throw bad()
            await fs.mkdir(path.dirname(target), {recursive: true})
            await fs.writeFile(target, content, {flag: 'wx'})
        }
    }
    async #manifest(workRoot, ignoreDerived = false) {
        const files = []; let total = 0, entries = 0
        const visit = async (filename, depth = 0) => {
            if (++entries > 30000 || depth > 64) throw bad()
            const info = await fs.lstat(filename)
            if (info.isSymbolicLink() || !samePath(await fs.realpath(filename), filename)) throw bad()
            if (info.isDirectory()) {
                for (const name of await fs.readdir(filename)) await visit(path.join(filename, name), depth + 1)
            } else {
                const content = await bytes(filename, workRoot)
                if (files.length >= MAX_FILES || (total += content.length) > MAX_TOTAL) throw bad()
                const relative = path.relative(workRoot, filename).split(path.sep).join('/')
                if (ignoreDerived && /^source\/_posts\/database-article-[1-9]\d{0,18}\.md$/.test(relative)) {
                    if (!/^blog_export_owned: true$/m.test(content.toString('utf8'))) throw bad()
                    return
                }
                files.push(Object.freeze({path: relative,
                    sha256: createHash('sha256').update(content).digest('hex'), size: content.length}))
            }
        }
        for (const name of this.names) await visit(path.join(workRoot, name))
        return files.sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)))
    }
    async probe() {
        await realDirectory(this.root)
        // 每次只读有界摘要，真正构建才复制；数据库派生文件由业务语义摘要判断变化。
        const files = await this.#manifest(this.root, true)
        return createHash('sha256').update(JSON.stringify({protocolVersion: 1, files})).digest('hex')
    }
    async verify(snapshot) {
        const owned = this.#owned.get(snapshot)
        if (!owned || owned.phase === 'BUILDING') throw bad()
        await this.#verifySources(snapshot, owned)
        const inventory = await this.#inventory(snapshot.workRoot, owned.modules)
        if (JSON.stringify(inventory) !== JSON.stringify(owned.inventory)) throw bad()
    }
    async #verifySources(snapshot, owned) {
        if (!sameFile(owned.stamp, await realDirectory(snapshot.workRoot))) throw bad()
        const files = await this.#manifest(snapshot.workRoot)
        const digest = createHash('sha256').update(JSON.stringify({protocolVersion: 1, files})).digest('hex')
        if (digest !== snapshot.sourceDigest) throw bad()
    }
    async #inventory(workRoot, modules) {
        const entries = []
        const visit = async directory => {
            for (const name of await fs.readdir(directory)) {
                const filename = path.join(directory, name), relative = path.relative(workRoot, filename).split(path.sep).join('/')
                const info = await fs.lstat(filename)
                if (relative === 'node_modules' && modules && info.isSymbolicLink() && samePath(await fs.realpath(filename), modules)) {
                    entries.push({path: relative, type: 'dependency'}); continue
                }
                if (info.isSymbolicLink() || !samePath(await fs.realpath(filename), filename)) throw bad()
                if (!info.isDirectory() && !info.isFile()) throw bad()
                entries.push({path: relative, type: info.isDirectory() ? 'directory' : 'file'})
                if (info.isDirectory()) await visit(filename)
            }
        }
        await visit(workRoot)
        return entries.sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)))
    }
    async beginBuild(snapshot) {
        await this.verify(snapshot)
        const owned = this.#owned.get(snapshot)
        if (owned.phase !== 'FROZEN') throw bad() // Never consume a later cache as an unrecorded input.
        await validateHexoPaths(snapshot.workRoot)
        const build = path.join(snapshot.workRoot, '.build')
        await fs.mkdir(build, {recursive: true}); await realDirectory(build)
        await fs.mkdir(path.join(build, 'blog')); await realDirectory(path.join(build, 'blog'))
        const modules = await fs.realpath(path.join(this.root, 'node_modules'))
        await fs.symlink(modules, path.join(snapshot.workRoot, 'node_modules'), 'junction')
        owned.modules = modules; owned.phase = 'BUILDING'
    }
    async exportDescriptor(snapshot) {
        await this.verify(snapshot)
        const owned = this.#owned.get(snapshot), info = await fs.lstat(snapshot.workRoot, {bigint: true})
        return validateDescriptor({schemaVersion: 1, releaseId: snapshot.releaseId, sourceDigest: snapshot.sourceDigest,
            workName: path.basename(snapshot.workRoot), workDevice: String(info.dev), workInode: String(info.ino),
            files: snapshot.files, initialInventory: owned.initialInventory})
    }
    async restoreClone(input) {
        const descriptor = validateDescriptor(input)
        await realDirectory(this.directory)
        const previous = path.join(this.directory, descriptor.workName)
        await realDirectory(previous)
        const previousIdentity = await fs.lstat(previous, {bigint: true})
        if (String(previousIdentity.dev) !== descriptor.workDevice || String(previousIdentity.ino) !== descriptor.workInode) throw bad()
        const workRoot = path.join(this.directory, 'work-' + randomUUID())
        await fs.mkdir(workRoot)
        const stamp = await realDirectory(workRoot)
        const allowed = (relative, type) => this.names.some(name => relative === name || relative.startsWith(name + '/')
            || type === 'directory' && name.startsWith(relative + '/'))
        try {
            // 旧 work 可能包含 db.json、输出或遗留 junction，只复制登记的输入及初始空目录。
            for (const entry of descriptor.initialInventory) {
                if (!allowed(entry.path, entry.type)) throw bad()
                if (entry.type === 'directory') await fs.mkdir(path.join(workRoot, entry.path), {recursive: true})
            }
            for (const file of descriptor.files) {
                if (!allowed(file.path, 'file')) throw bad()
                const content = await bytes(path.join(previous, file.path), previous)
                if (content.length !== file.size || createHash('sha256').update(content).digest('hex') !== file.sha256) throw bad()
                const target = path.join(workRoot, file.path)
                await fs.mkdir(path.dirname(target), {recursive: true})
                await fs.writeFile(target, content, {flag: 'wx'})
            }
            const currentIdentity = await fs.lstat(previous, {bigint: true})
            if (currentIdentity.dev !== previousIdentity.dev || currentIdentity.ino !== previousIdentity.ino) throw bad()
            await validateHexoPaths(workRoot)
            const files = await this.#manifest(workRoot)
            const sourceDigest = createHash('sha256').update(JSON.stringify({protocolVersion: 1, files})).digest('hex')
            const inventory = await this.#inventory(workRoot)
            if (sourceDigest !== descriptor.sourceDigest || JSON.stringify(inventory) !== JSON.stringify(descriptor.initialInventory)) throw bad()
            const postNames = files.map(file => file.path).filter(name => /^source\/_posts\/database-article-[1-9]\d{0,18}\.md$/.test(name))
                .map(name => path.basename(name))
            const snapshot = Object.freeze({releaseId: descriptor.releaseId, workRoot, sourceDigest,
                files: Object.freeze(files), postNames: Object.freeze(postNames)})
            this.#owned.set(snapshot, {stamp, phase: 'FROZEN', inventory, initialInventory: inventory})
            return snapshot
        } catch (error) {await this.#cleanup(workRoot, stamp); throw error}
    }
    async completeBuild(snapshot) {
        const owned = this.#owned.get(snapshot)
        if (!owned || owned.phase !== 'BUILDING') throw bad()
        await this.#verifySources(snapshot, owned)
        const inventory = await this.#inventory(snapshot.workRoot, owned.modules)
        const original = new Map(owned.inventory.map(entry => [entry.path, entry.type]))
        for (const entry of inventory) {
            if (original.get(entry.path) === entry.type) continue
            if (entry.path === 'node_modules' && entry.type === 'dependency'
                    || entry.path === 'db.json' && entry.type === 'file'
                    || entry.path === '.build' && entry.type === 'directory'
                    || entry.path === '.build/blog' && entry.type === 'directory'
                    || entry.path.startsWith('.build/blog/') && ['directory', 'file'].includes(entry.type)) continue
            throw bad()
        }
        const current = new Map(inventory.map(entry => [entry.path, entry.type]))
        if (owned.inventory.some(entry => current.get(entry.path) !== entry.type)) throw bad()
        owned.inventory = inventory; owned.phase = 'BUILT'
    }
    async dispose(snapshot) {
        const owned = this.#owned.get(snapshot)
        if (!owned) throw bad()
        await this.#cleanup(snapshot.workRoot, owned.stamp)
        this.#owned.delete(snapshot)
    }
    async #cleanup(workRoot, stamp) {
        await realDirectory(this.directory)
        if (path.dirname(workRoot) !== this.directory || !/^work-[a-f0-9-]{36}$/.test(path.basename(workRoot))
            || !sameFile(stamp, await realDirectory(workRoot))) throw bad()
        const modules = path.join(workRoot, 'node_modules')
        try {
            if (!(await fs.lstat(modules)).isSymbolicLink()) throw bad()
            await fs.unlink(modules)
        } catch (error) {if (error.code !== 'ENOENT') throw error}
        await fs.rm(workRoot, {recursive: true, force: false})
    }
}
module.exports = {FrozenPublicationInputs}
