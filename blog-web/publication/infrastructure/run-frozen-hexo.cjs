'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const Hexo = require('hexo')

async function buildFrozen() {
    const work = process.cwd(), source = path.join(work, 'source'), output = path.join(work, '.build/blog')
    const same = (left, right) => process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right
    const hexo = new Hexo(work, {silent: true, _: ['generate']})
    const directories = async () => {
        if (!same(path.resolve(hexo.source_dir), source) || !same(path.resolve(hexo.public_dir), output)
                || !same(path.resolve(hexo.database.options.path), path.join(work, 'db.json'))
                || !same(await fs.realpath(source), source) || !same(await fs.realpath(output), output))
            throw new Error('Unsafe effective Hexo paths')
    }
    try {
        await hexo.init()
        // 继承当前系统时区；站点 timezone 留空，归档与主题展示使用同一时钟。
        await directories()
        // Public Hexo lifecycle: routes exist here, but the generate console has not written them yet.
        hexo.extend.filter.register('after_generate', async () => {
            await directories()
            for (const route of hexo.route.list()) {
                const target = path.resolve(output, route)
                if (!target.startsWith(output + path.sep)) throw new Error('Unsafe generated route')
            }
        }, Number.MAX_SAFE_INTEGER)
        await hexo.call('generate', {bail: true})
        await hexo.exit()
    } catch {
        try {await hexo.exit()} catch {}
        throw new Error('Frozen Hexo build rejected')
    }
}
if (require.main === module) buildFrozen().catch(() => {process.exitCode = 1})
