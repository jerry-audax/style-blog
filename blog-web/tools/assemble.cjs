'use strict'
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const target = path.join(root, 'dist')

function validateTree(source) {
    for (const entry of fs.readdirSync(source, {withFileTypes: true})) {
        if (entry.name === '.vite') continue
        if (entry.isSymbolicLink()) throw new Error('构建产物不能包含符号链接')
        const filename = path.join(source, entry.name)
        if (entry.isDirectory()) validateTree(filename)
        else if (entry.name === 'index.html' || entry.name === '404.html') {
            const html = fs.readFileSync(filename, 'utf8')
            // Hexo can exit successfully after a template failure while writing an
            // empty page. Never assemble or deploy those broken generated pages.
            if (!/<!doctype html>/i.test(html) || !/<body[\s>]/i.test(html))
                throw new Error(`HTML 构建失败：${path.relative(root, filename)}`)
        }
    }
}

for (const dir of ['.build/app', '.build/blog']) {
    if (!fs.existsSync(path.join(root, dir, 'index.html'))) throw new Error(`${dir} 未构建，不能组装`)
    validateTree(path.join(root, dir))
}
if (path.dirname(target) !== root || path.basename(target) !== 'dist')
    throw new Error('构建目标无效')
if (fs.existsSync(target) && fs.lstatSync(target).isSymbolicLink())
    throw new Error('构建目标不能是符号链接')
fs.rmSync(target, {recursive: true, force: true})

function copyTree(source, destination) {
    fs.mkdirSync(destination, {recursive: true})
    for (const entry of fs.readdirSync(source, {withFileTypes: true})) {
        if (entry.name === '.vite') continue
        if (entry.isSymbolicLink()) throw new Error('构建产物不能包含符号链接')
        const from = path.join(source, entry.name),
            to = path.join(destination, entry.name)
        if (entry.isDirectory()) copyTree(from, to)
        else fs.copyFileSync(from, to)
    }
}

copyTree(path.join(root, '.build/app'), target)
copyTree(path.join(root, '.build/blog'), path.join(target, 'blog'))
console.log('构建完成：dist/ → Vue，dist/blog/ → Hexo')
