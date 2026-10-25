'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const {FrozenPublicationInputs} = require('../../publication/infrastructure/FrozenPublicationInputs.cjs')

async function write(root, name, value) {await fs.mkdir(path.dirname(path.join(root, name)), {recursive: true}); await fs.writeFile(path.join(root, name), value)}
async function fixture(run, {dev = true} = {}) {
    const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'blog-frozen-test-'))
    const root = path.join(temporary, 'root'), directory = path.join(temporary, 'state')
    for (const [name, data] of Object.entries({
        '_config.yml': 'url: https://blog.example\nroot: /blog/\ntheme: false\npublic_dir: .build/blog\ntimezone: Asia/Shanghai\n',
        '_config.anzhiyu.yml': 'runtimeshow:\n  enable: true\n  publish_date: "2026-05-19T10:38:00+08:00"\n',
        'package.json': JSON.stringify({name: 'isolated-hexo-fixture', version: '1.0.0', hexo: {version: '8.1.2'}, workspaces: ['app']}),
        'source/index.html': '---\nlayout: false\n---\n<!doctype html><html><body>冻结正文</body></html>',
        'source/_posts/手写.md': '# 原来手写内容', 'source/图片.bin': Buffer.from([0, 255, 128, 42]),
        'scripts/.keep.js': '// isolated fixture', 'licenses/example.txt': 'isolated fixture',
        'tools/content.cjs': 'module.exports = {}', 'tools/article-summary.cjs': 'module.exports = {}',
        'tools/media-loading.cjs': 'module.exports = {}', 'tools/site-integration.cjs': 'module.exports = {}', 'tools/vendor-assets.cjs': 'module.exports = {}',
        'tools/lightbox-selection.cjs': 'module.exports = {}',
        '.build/app/.vite/manifest.json': JSON.stringify({'src/interaction-widget.js': {file: 'assets/interaction.js', isEntry: true},
            'src/site-background.js': {file: 'assets/background.js', isEntry: true}}),
        '.build/app/assets/interaction.js': 'export const widgetReady = true;', '.build/app/assets/background.js': 'export const backgroundReady = true;'
    })) await write(root, name, data)
    const inputs = new FrozenPublicationInputs({root, directory, dev})
    try {await run({root, directory, inputs, temporary})} finally {
        assert.equal(path.dirname(temporary), os.tmpdir()); assert.match(path.basename(temporary), /^blog-frozen-test-/)
        await fs.rm(temporary, {recursive: true, force: true})
    }
}
module.exports = {fixture, write}
