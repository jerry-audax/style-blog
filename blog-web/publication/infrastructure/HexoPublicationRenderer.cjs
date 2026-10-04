'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {randomUUID} = require('node:crypto')
const {spawn} = require('node:child_process')

async function copyTree(source, target) {
    const info = await fs.lstat(source)
    if (info.isSymbolicLink()) throw new Error('Publication inputs cannot be symlinks')
    if (!info.isDirectory()) {await fs.mkdir(path.dirname(target), {recursive: true}); await fs.copyFile(source, target); return}
    await fs.mkdir(target, {recursive: true})
    for (const name of await fs.readdir(source)) await copyTree(path.join(source, name), path.join(target, name))
}

async function validateTree(directory) {
    for (const entry of await fs.readdir(directory, {withFileTypes: true})) {
        if (entry.isSymbolicLink()) throw new Error('Publication outputs cannot be symlinks')
        const filename = path.join(directory, entry.name)
        if (entry.isDirectory()) await validateTree(filename)
        else if (entry.name.endsWith('.html')) {
            const html = await fs.readFile(filename, 'utf8')
            if (!/<!doctype html>/i.test(html) || !/<body[\s>]/i.test(html)) throw new Error('Invalid generated HTML')
        }
    }
}

class HexoPublicationRenderer {
    constructor({root, directory, dev = false, timeoutMs = 120000}) {
        this.root = path.resolve(root); this.directory = path.resolve(directory); this.dev = dev; this.timeoutMs = timeoutMs
    }
    async render(posts) {
        const release = `release-${randomUUID()}`
        const work = path.join(this.directory, `work-${randomUUID()}`)
        await fs.mkdir(work, {recursive: true})
        try {
            for (const name of ['_config.yml', '_config.anzhiyu.yml', 'source', 'scripts', 'licenses',
                'tools/content.cjs', 'tools/site-integration.cjs', 'tools/vendor-assets.cjs'])
                await copyTree(path.join(this.root, name), path.join(work, name))
            const config = JSON.parse(await fs.readFile(path.join(this.root, 'package.json'), 'utf8'))
            delete config.workspaces
            await fs.writeFile(path.join(work, 'package.json'), JSON.stringify(config))
            await fs.symlink(path.join(this.root, 'node_modules'), path.join(work, 'node_modules'), 'junction')
            if (!this.dev) await copyTree(path.join(this.root, '.build/app/.vite/manifest.json'), path.join(work, '.build/app/.vite/manifest.json'))
            const postDirectory = path.join(work, 'source/_posts')
            await fs.mkdir(postDirectory, {recursive: true})
            // Remove only owned database exports in this temporary copy. Never
            // modify the repository's posts or delete handwritten source files.
            for (const name of await fs.readdir(postDirectory)) {
                if (!/^database-article-[1-9]\d{0,18}\.md$/.test(name)) continue
                const filename = path.join(postDirectory, name)
                if (!/^blog_export_owned: true$/m.test(await fs.readFile(filename, 'utf8')))
                    throw new Error('Cannot replace a handwritten post')
                await fs.unlink(filename)
            }
            for (const post of posts) {
                if (!/^database-article-[1-9]\d{0,18}\.md$/.test(post.name)) throw new Error('Invalid export name')
                await fs.writeFile(path.join(postDirectory, post.name), post.text, {flag: 'wx'})
            }
            await new Promise((resolve, reject) => {
                const cli = path.join(path.dirname(require.resolve('hexo-cli/package.json')), 'bin/hexo')
                const child = spawn(process.execPath, [cli, 'generate'], {cwd: work,
                    env: {...process.env, BLOG_DEV: this.dev ? '1' : '0'}, windowsHide: true, stdio: 'ignore'})
                const timer = setTimeout(() => {child.kill(); reject(new Error('Publication build timed out'))}, this.timeoutMs)
                child.on('error', error => {clearTimeout(timer); reject(error)})
                child.on('exit', code => {clearTimeout(timer); code === 0 ? resolve() : reject(new Error('Publication build failed'))})
            })
            const tree = path.join(work, '.build/blog')
            await fs.access(path.join(tree, 'index.html'))
            await validateTree(tree)
            for (const post of posts) {
                const id = post.name.match(/^database-article-(\d+)\.md$/)[1]
                await fs.access(path.join(tree, 'article', id, 'index.html'))
            }
            await fs.mkdir(path.join(this.directory, 'releases'), {recursive: true})
            // The release is not visible until the repository commits its
            // pointer. Copying avoids Windows directory-rename sharing locks.
            await copyTree(tree, path.join(this.directory, 'releases', release))
            return release
        } finally {
            if (path.dirname(work) !== this.directory || !/^work-[a-f0-9-]+$/.test(path.basename(work)))
                throw new Error('Invalid temporary publication path')
            try {await fs.unlink(path.join(work, 'node_modules'))} catch (error) {if (error.code !== 'ENOENT') throw error}
            await fs.rm(work, {recursive: true, force: true})
        }
    }
}
module.exports = {HexoPublicationRenderer, validateTree}
