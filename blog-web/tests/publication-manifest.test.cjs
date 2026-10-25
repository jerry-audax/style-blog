const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const {fixture, write} = require('./support/publication-site-fixture.cjs')
const {PublicationManifestCollector} = require('../publication/infrastructure/PublicationManifestCollector.cjs')
const {AnzhiyuScriptPolicy} = require('../publication/infrastructure/AnzhiyuScriptPolicy.cjs')
const {extractCssReferences} = require('../publication/infrastructure/PublicationCssReferences.cjs')
const {createManifest} = require('../publication/domain/PublicationManifest.cjs')

async function source(html, files, run) {
    return fixture(async ({root, directory, inputs}) => {
        await write(root, 'source/index.html', '---\nlayout: false\n---\n<!doctype html><html><body>' + html + '</body></html>')
        for (const [name, bytes] of Object.entries(files || {})) await write(root, name, bytes)
        const snapshot = await inputs.freeze([])
        const collector = new PublicationManifestCollector({directory, inputs, scriptPolicy: new AnzhiyuScriptPolicy()})
        try {await run({collector, snapshot, root, directory, inputs})} finally {await inputs.dispose(snapshot)}
    }, {dev: false})
}
const managed = manifest => [...new Set(manifest.links.filter(value => value.startsWith('/file/')))].sort()

test('browser-normalized URL whitespace is protected in both source and output manifests', async () => {
    const html = '<img src=" /file/blog/padded.png "><img src="&#10;https://media.example/file/blog/remote.png&#9;">'
    await source(html, {}, async ({collector, snapshot, directory}) => {
        await write(directory, `releases/${snapshot.releaseId}/index.html`, html)
        for (const manifest of [await collector.source(snapshot), await collector.output(snapshot, snapshot.releaseId)]) {
            assert.ok(manifest.links.includes('/file/blog/padded.png'))
            assert.ok(manifest.links.includes('https://media.example/file/blog/remote.png'))
        }
    })
})

test('inline and standalone SVG presentation URLs and HTML backgrounds join protected manifests', async () => {
    const html = '<body background="/file/blog/background.png"><svg><rect fill="url(/file/blog/paint.svg#pattern)" stroke="url(/file/blog/stroke.svg)" /></svg></body>'
    const svg = '<svg xmlns="http://www.w3.org/2000/svg"><rect filter="url(/file/blog/filter.svg#blur)" clip-path="url(/file/blog/clip.svg#shape)" /></svg>'
    await source(html, {'source/standalone.svg': svg}, async ({collector, snapshot, directory}) => {
        await write(directory, `releases/${snapshot.releaseId}/index.html`, html)
        await write(directory, `releases/${snapshot.releaseId}/standalone.svg`, svg)
        for (const manifest of [await collector.source(snapshot), await collector.output(snapshot, snapshot.releaseId)])
            assert.deepEqual(managed(manifest), ['/file/blog/background.png', '/file/blog/clip.svg#shape',
                '/file/blog/filter.svg#blur', '/file/blog/paint.svg#pattern', '/file/blog/stroke.svg'])
    })
})

test('manifest values are immutable and enforce candidate and byte budgets before transport', () => {
    const input = {sourceDigest: 'a'.repeat(64), links: ['/file/blog/a.png'], contents: [], htmlContents: []}
    const value = createManifest(input)
    input.links.push('/file/blog/later.png')
    assert.deepEqual(value.links, ['/file/blog/a.png'])
    assert.ok(Object.isFrozen(value)); assert.ok(Object.isFrozen(value.links))
    for (const extra of [{sourceDigest: 'wrong'}, {links: Array(10001).fill('')}, {contents: ['中'.repeat(349526)]},
        {contents: Array(5).fill('x'.repeat(1048576))}, {links: [null]}])
        assert.throws(() => createManifest({...input, ...extra}))
})

test('CSS string image-set candidates remain resources while glyph escapes are not image URLs', () => {
    const refs = extractCssReferences(".x{background-image:image-set('/file/blog/one.png' 1x,url('/file/blog/two.png') 2x)}.icon:before{content:'\\e600'}")
    assert.deepEqual([...refs.links].sort(), ['/file/blog/one.png', '/file/blog/two.png'])
    assert.throws(() => extractCssReferences(".x{background:url('/fi\\6c e/blog/hidden.png')}"))
    assert.throws(() => extractCssReferences(".x{background:u\\72l('/file/blog/hidden.png')}"))
    assert.throws(() => extractCssReferences("@\\69mport './hidden.css';"))
    assert.throws(() => extractCssReferences(".x{background:url('/file/blog/unclosed.png')} /* unclosed"))
    assert.deepEqual(extractCssReferences('.tip:after{content:attr(title)}').links, [])
})

test('unknown executable script and event handlers cannot become a successful empty manifest', async () => {
    for (const html of ["<script>fetch('/file/blog/hidden.png')</script>", "<img src='/file/blog/good.png' onerror=\"fetch('/file/blog/hidden.png')\">"])
        await source(html, {}, async ({collector, snapshot}) => {await assert.rejects(collector.source(snapshot))})
})

test('reviewed navigation actions remain valid but unknown javascript links fail', async () => {
    await source('<a href="javascript:anzhiyu.scrollToDest(0, 500)">回顶</a>', {}, async ({collector, snapshot}) => {
        assert.deepEqual(managed(await collector.source(snapshot)), [])
    })
    await source('<a href="javascript:fetch(\'/file/blog/hidden.png\')">未知</a>', {}, async ({collector, snapshot}) => {
        await assert.rejects(collector.source(snapshot))
    })
})

test('nested local CSS and controlled image fallback all contribute exact managed references', async () => {
    await source("<link rel='stylesheet' href='/blog/css/outer.css'><img src='/file/blog/main.png' onerror=\"this.onerror=null;this.src='/file/blog/fallback.png'\">", {
        'source/css/outer.css': "@import './inner.css'; .outer{background:url('/file/blog/outer.png')}",
        'source/css/inner.css': ".inner{background-image:image-set('/file/blog/inner.png' 1x)}"
    }, async ({collector, snapshot}) => {
        const manifest = await collector.source(snapshot)
        assert.deepEqual(managed(manifest), ['/file/blog/fallback.png', '/file/blog/inner.png', '/file/blog/main.png', '/file/blog/outer.png'])
    })
})

test('local CSS parent imports stay inside their owned tree', async () => {
    await source("<link rel='stylesheet' href='/blog/css/nested/outer.css'>", {
        'source/css/nested/outer.css': "@import '../shared.css';",
        'source/css/shared.css': ".x{background:url('/file/blog/shared.png')}"
    }, async ({collector, snapshot}) => {assert.deepEqual(managed(await collector.source(snapshot)), ['/file/blog/shared.png'])})
})

test('theme fallback assignment order and implicit element properties preserve the literal image', async () => {
    for (const handler of ["this.src='/file/blog/fallback.png'; this.onerror = null;", "onerror=null;src='/file/blog/fallback.png'", 'this.src=undefined; this.onerror=null;'])
        await source(`<img src='/file/blog/main.png' onerror="${handler}">`, {}, async ({collector, snapshot}) => {
            assert.deepEqual(managed(await collector.source(snapshot)), handler.includes('undefined') ? ['/file/blog/main.png'] : ['/file/blog/fallback.png', '/file/blog/main.png'])
        })
})

test('JSON XML SVG srcset and lazy images retain all managed candidates', async () => {
    await source("<img data-src='/file/blog/lazy.png' srcset='/file/blog/one.png 1x, /file/blog/two.png 2x'>", {
        'source/gallery.json': JSON.stringify({cover: '/file/blog/json.png'}),
        'source/feed.xml': "<feed><content><![CDATA[<img src='/file/blog/xml.png'>]]></content></feed>",
        'source/vector.svg': "<svg><image href='/file/blog/svg.png'/></svg>"
    }, async ({collector, snapshot}) => {
        assert.deepEqual(managed(await collector.source(snapshot)), ['/file/blog/json.png', '/file/blog/lazy.png', '/file/blog/one.png', '/file/blog/svg.png', '/file/blog/two.png', '/file/blog/xml.png'])
    })
})

test('frozen Vite graph includes compiled widget bytes independently of later original changes', async () => {
    await source('<p>widget</p>', {'.build/app/assets/interaction.js': "export const cover = '/file/blog/widget.png';"}, async ({collector, snapshot, root}) => {
        assert.ok(snapshot.files.some(file => file.path === '.build/app/assets/interaction.js'))
        await write(root, '.build/app/assets/interaction.js', "export const cover = '/file/blog/later.png';")
        assert.ok(managed(await collector.source(snapshot)).includes('/file/blog/widget.png'))
        assert.ok(!managed(await collector.source(snapshot)).includes('/file/blog/later.png'))
    })
})

test('compiled static template URIs are collected while constructed managed template addresses fail', async () => {
    await source('<p>widget</p>', {'.build/app/assets/interaction.js': 'export const cover = `/file/blog/template.png`;'}, async ({collector, snapshot}) => {
        assert.ok(managed(await collector.source(snapshot)).includes('/file/blog/template.png'))
    })
    await source('<p>widget</p>', {'.build/app/assets/interaction.js': 'export const cover = `/file/blog/${key}.png`;'}, async ({collector, snapshot}) => {
        await assert.rejects(collector.source(snapshot))
    })
})

test('Markdown is forwarded with its code boundary and HTML is not treated as indented Markdown', async () => {
    const markdown = "# code\n\n```html\n<img src='/file/blog/code-only.png'>\n```\n\n![real](/file/blog/real.png)"
    await source("    <img src='/file/blog/indented.png'>", {'source/_posts/example.md': markdown}, async ({collector, snapshot}) => {
        const manifest = await collector.source(snapshot)
        assert.ok(manifest.contents.includes(markdown))
        assert.ok(!manifest.links.includes('/file/blog/code-only.png'))
        assert.ok(managed(manifest).includes('/file/blog/indented.png'))
    })
})

test('rendered pre elements still load real child images while escaped examples remain text', async () => {
    await source("<pre><img src='/file/blog/live.png'></pre><code>&lt;img src='/file/blog/text.png'&gt;</code>", {}, async ({collector, snapshot}) => {
        assert.deepEqual(managed(await collector.source(snapshot)), ['/file/blog/live.png'])
    })
})

test('unfrozen remote styles, import cycles and local path escapes are rejected', async () => {
    for (const [html, files] of [
        ["<link rel='stylesheet' href='https://foreign.example/style.css'>", {}],
        ["<link rel='stylesheet' href='/blog/missing.css'>", {}],
        ["<link rel='stylesheet' href='/blog/css/loop.css'>", {'source/css/loop.css': "@import './loop.css'"}],
        ["<link rel='stylesheet' href='/blog/../../outside.css'>", {}]
    ]) await source(html, files, async ({collector, snapshot}) => {await assert.rejects(collector.source(snapshot))})
})

test('unreviewed active document loaders and dynamic gallery cannot hide secondary resource fetches', async () => {
    for (const html of ["<iframe srcdoc=\"<img src='/file/blog/hidden.png'>\"></iframe>",
        "<base href='https://foreign.example/'>", "<div class='fj-gallery' data-type='url'></div>",
        "<svg><set attributeName='href' to='/file/blog/hidden.png'/></svg>"])
        await source(html, {}, async ({collector, snapshot}) => {await assert.rejects(collector.source(snapshot))})
})

test('output fingerprints notice byte changes after a real frozen Hexo render', async () => {
    await source("<img src='/file/blog/output.png'>", {}, async ({collector, snapshot, root, directory, inputs}) => {
        const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
        await fs.symlink(path.resolve(__dirname, '../node_modules'), path.join(root, 'node_modules'), 'junction')
        const renderer = new HexoPublicationRenderer({root, directory, inputs, dev: false})
        const release = await renderer.renderFrozen(snapshot)
        const first = await collector.output(snapshot, release)
        assert.deepEqual(managed(first), ['/file/blog/output.png'])
        const filename = path.join(directory, 'releases', release, 'index.html')
        await fs.appendFile(filename, '<p>changed bytes</p>')
        const changed = await collector.output(snapshot, release)
        assert.notEqual(first.sourceDigest, changed.sourceDigest)
    })
})

for (const layoutVersion of [1, 2]) test(`real Anzhiyu frozen home article tag and music keep components and collect managed media (layout ${layoutVersion})`, async () => {
    await fixture(async ({root, directory, inputs}) => {
        const project = path.resolve(__dirname, '..')
        for (const name of ['package.json', '_config.yml', '_config.anzhiyu.yml', 'scripts/site-loader.js', 'tools/content.cjs',
            'tools/article-summary.cjs', 'tools/media-loading.cjs', 'tools/site-integration.cjs', 'tools/vendor-assets.cjs', 'tools/lightbox-selection.cjs',
            'source/music/index.md', 'source/tags/index.md', 'source/about/index.md', 'source/css/site.css',
            'source/js/site-music.js', 'source/js/site-media-loading.js', 'source/js/site-bridge.js',
            'source/js/site-surprise.js', 'source/js/site-click-effect.js', 'source/img/site-logo.jpg', 'source/img/avatar.jpg',
            'licenses/react-bits.txt', 'licenses/ogl.txt']) await write(root, name, await fs.readFile(path.join(project, name)))
        const themeFile = path.join(root, '_config.anzhiyu.yml')
        // 此用例刻意同时开启两项，验证 mediumZoom 优先；不依赖开发机的主题配置。
        const themeFixture = (await fs.readFile(themeFile, 'utf8')).replace(/^fancybox: (?:true|false)\r?$/m, 'fancybox: true')
        await fs.writeFile(themeFile, themeFixture)
        const themeBefore = await fs.readFile(themeFile)
        assert.match(themeBefore.toString('utf8'), /^medium_zoom: true$/m)
        assert.match(themeBefore.toString('utf8'), /^fancybox: true$/m)
        if (layoutVersion === 2) {
            const fixtureApp = path.join(root, '.build/app')
            assert.equal(path.dirname(fixtureApp), path.join(root, '.build'))
            await (await import('vite')).build({configFile: path.join(project, 'app/vite.config.js'),
                root: path.join(project, 'app'), configLoader: 'runner', logLevel: 'silent',
                plugins: [{name: 'isolated-test-entry-root', configResolved(config) {
                    // CLI 原本在 app 目录执行；测试只规范化同一入口，避免全局 chdir 污染并行用例。
                    const entries = config.build.rollupOptions.input
                    assert.ok(entries && typeof entries === 'object' && !Array.isArray(entries))
                    for (const name of Object.keys(entries)) entries[name] = path.resolve(config.root, entries[name])
                }}],
                build: {outDir: fixtureApp, emptyOutDir: true}})
        }
        await fs.symlink(path.join(project, 'node_modules'), path.join(root, 'node_modules'), 'junction')
        const {toPost} = require('../tools/content.cjs')
        const post = toPost({id: 19, status: 1, title: '清单真实主题测试', publishTime: '2026-09-01T12:00:00',
            contentHtml: '<p>正文</p><img src="/file/blog/theme-body.png">', coverImage: '/file/blog/theme-cover.png',
            categoryName: '系统设计', tagNames: ['DDD']})
        const snapshot = await inputs.freeze([post])
        try {
            const policy = new AnzhiyuScriptPolicy()
            const collector = new PublicationManifestCollector({directory, inputs, layoutVersion, scriptPolicy: {inspect(code, context) {
                try {return policy.inspect(code, context)} catch (error) {
                    // 只记录技术角色与摘要，不输出配置值或脚本正文。
                    error.message += ` [${context.kind}:${context.filename || require('node:crypto').createHash('sha256').update(code).digest('hex')}]`
                    if (context.kind === 'event') {
                        const names = new Set()
                        const inspect = value => {
                            if (!value || typeof value !== 'object') return
                            if (value.type === 'Identifier') names.add(value.name)
                            for (const child of Object.values(value)) if (Array.isArray(child)) child.forEach(inspect); else inspect(child)
                        }
                        try {inspect(require('acorn').parse(code, {ecmaVersion: 2020})); error.message += ' identifiers=' + [...names].join(',')} catch {}
                    }
                    throw error
                }
            }}})
            const before = await collector.source(snapshot)
            assert.ok(before.contents.some(value => value.includes('/file/blog/theme-body.png')))
            const {HexoPublicationRenderer} = require('../publication/infrastructure/HexoPublicationRenderer.cjs')
            const release = await new HexoPublicationRenderer({root, directory, inputs, layoutVersion}).renderFrozen(snapshot)
            for (const route of ['index.html', 'article/19/index.html', 'tags/DDD/index.html', 'music/index.html']) {
                const text = await fs.readFile(path.join(directory, 'releases', release, layoutVersion === 2 ? 'blog' : '', route), 'utf8')
                assert.match(text, /id="nav"/)
                assert.match(text, /site-music/)
                assert.match(text, /pluginsSrc\/medium-zoom\/dist\/medium-zoom\.min\.js/)
                assert.ok(!/pluginsSrc\/@fancyapps\/ui\/dist\/fancybox\//.test(text), 'unselected Fancybox resources must not be emitted')
            }
            const output = await collector.output(snapshot, release)
            assert.ok(managed(output).includes('/file/blog/theme-body.png'))
            assert.deepEqual(await fs.readFile(themeFile), themeBefore)
            await fs.appendFile(path.join(directory, 'releases', release, layoutVersion === 2 ? 'blog' : '', 'index.html'), "<script>fetch('/file/blog/unknown.png')</script>")
            await assert.rejects(collector.output(snapshot, release))
        } finally {await inputs.dispose(snapshot)}
    }, {dev: false})
})

test('pure global configuration is data while executable initializers and modified theme handlers fail closed', () => {
    const policy = new AnzhiyuScriptPolicy()
    assert.deepEqual(policy.inspect("const GLOBAL_CONFIG={cover:'/file/blog/config.png'}", {kind: 'inline'}).texts[0].cover, '/file/blog/config.png')
    assert.equal(policy.inspect('var GLOBAL_CONFIG={disabled:undefined}', {kind: 'inline'}).texts[0].disabled, null)
    for (const code of ["const GLOBAL_CONFIG=fetch('/file/blog/x')", "const GLOBAL_CONFIG={get cover(){return '/file/blog/x'}}",
        "const GLOBAL_CONFIG={['cover']: '/file/blog/x'}", "anzhiyu.musicToggle();fetch('/file/blog/x')"])
        assert.throws(() => policy.inspect(code, {kind: code.startsWith('const') ? 'inline' : 'event', event: 'onclick'}))
})
