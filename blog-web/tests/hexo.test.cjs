const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Hexo = require('hexo')
const vm = require('node:vm')
const postcss = require('postcss')
const {load: loadYaml} = require('js-yaml')
const {toPost} = require('../tools/content.cjs')

test('a real Hexo build injects the production Vue entry into a database-ID article', async () => {
    const root = path.resolve(__dirname, '..')
    const sandbox = fs.mkdtempSync(path.join(root, '.test-site-'))
    const hexo = new Hexo(sandbox, {silent: true})
    try {
        // All fixture content stays in this owned temporary site, not source/_posts.
        fs.symlinkSync(path.join(root, 'node_modules'), path.join(sandbox, 'node_modules'), 'junction')
        const config = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
        delete config.workspaces
        fs.writeFileSync(path.join(sandbox, 'package.json'), JSON.stringify(config))
        for (const name of [
            '_config.yml',
            '_config.anzhiyu.yml',
            'scripts/site-loader.js',
            'tools/content.cjs',
            'tools/site-integration.cjs',
            'tools/vendor-assets.cjs',
            'source/music/index.md',
            'source/about/index.md',
            'source/js/site-music.js',
            'source/js/site-bridge.js',
            'source/js/site-click-effect.js',
            'source/css/site.css',
            'source/img/site-logo.jpg',
            'source/img/avatar.jpg',
            'licenses/react-bits.txt',
            'licenses/ogl.txt',
        ]) {
            const dest = path.join(sandbox, name)
            fs.mkdirSync(path.dirname(dest), {recursive: true})
            fs.copyFileSync(path.join(root, name), dest)
        }
        fs.mkdirSync(path.join(sandbox, '.build/app/.vite'), {recursive: true})
        fs.writeFileSync(
            path.join(sandbox, '.build/app/.vite/manifest.json'),
            JSON.stringify({
                'src/interaction-widget.js': {
                    file: 'assets/widget-test.js',
                    css: ['assets/widget-test.css'],
                },
                'src/site-background.js': {file: 'assets/background-test.js'},
            }),
        )
        const post = toPost({
            id: 19,
            status: 1,
            title: '隔离构建测试',
            publishTime: '2026-09-01T12:00:00',
            contentHtml:
                '<h2>HTML 正文</h2><script>alert(1)</script><img src="https://example.com/x" onerror="alert(1)">',
            categoryName: '系统设计',
            tagNames: ['DDD'],
        })
        fs.mkdirSync(path.join(sandbox, 'source/_posts'), {recursive: true})
        fs.writeFileSync(path.join(sandbox, 'source/_posts', post.name), post.text)
        const siteTheme = loadYaml(fs.readFileSync(path.join(sandbox, '_config.anzhiyu.yml'), 'utf8'))
        await hexo.init()
        assert.equal(hexo.extend.filter.list('before_generate').filter(fn => String(fn).includes('reward.QR_code')).length, 1,
            'Hexo must discover and await the site adapter through scripts/site-loader.js')
        assert.equal(typeof hexo.extend.generator.get('blog-vendor-assets'), 'function',
            'local theme assets must be registered by the same Hexo instance')
        await hexo.call('generate')
        assert.deepEqual(hexo.theme.config.reward.QR_code, [], 'disabled donations must not leave a null iterable')
        assert.deepEqual(Object.keys(hexo.theme.config.menu), Object.keys(siteTheme.menu))
        assert.deepEqual(Object.keys(hexo.theme.config.menu.主页), ['归档', '分类', '标签'])
        const home = fs.readFileSync(path.join(sandbox, '.build/blog/index.html'), 'utf8')
        assert.equal(hexo.theme.config.display_mode, 'dark')
        assert.equal(hexo.theme.config.darkmode.autoChangeMode, false)
        assert.equal(hexo.theme.config.theme_color.main, '#4F46E5')
        assert.equal(hexo.theme.config.theme_color.dark_main, '#A5B4FC')
        assert.equal(hexo.theme.config.theme_color.paginator, 'var(--anzhiyu-main)')
        assert.equal(hexo.theme.config.theme_color.toc_color, 'var(--anzhiyu-main)')
        assert.equal(hexo.theme.config.mainTone.enable, false)
        assert.equal(hexo.theme.config.mainTone.cover_change, false)
        assert.match(home, /<html\b[^>]*data-theme="dark"/)
        assert.match(home, /id="consoleMusic"[^>]*onclick="anzhiyu\.musicToggle\(\)"/)
        assert.match(home, /type="module" src="\/assets\/background-test\.js"/)
        assert.match(home, /<script defer src="\/blog\/js\/site-click-effect\.js"><\/script>/)
        assert.equal((home.match(/id="site-click-effect-config"/g) || []).length, 1)
        assert.deepEqual(hexo.theme.config.site_click_effect, {
            enable: true, colors: ['#eb125f', '#6eff8a', '#6386ff', '#f9f383'],
            size: 30, maxCount: 30, duration: 1000, maxActiveBalls: 150,
        })
        for (const effect of ['fireworks', 'click_heart', 'clickShowText'])
            assert.equal(hexo.theme.config[effect].enable, false, 'do not stack multiple click effects')
        assert.ok(fs.existsSync(path.join(sandbox, '.build/blog/js/site-click-effect.js')))
        assert.ok(fs.existsSync(path.join(sandbox, '.build/blog/vendor/licenses/react-bits.txt')))
        assert.ok(fs.existsSync(path.join(sandbox, '.build/blog/vendor/licenses/ogl.txt')))
        assert.equal(hexo.theme.config.favicon, '/img/site-logo.jpg')
        assert.equal(hexo.theme.config.avatar.img, '/img/avatar.jpg')
        assert.equal(hexo.theme.config.error_img.flink, '/img/site-logo.jpg')
        assert.match(home, /<link\b[^>]*rel="(?:shortcut )?icon"[^>]*href="\/blog\/img\/site-logo\.jpg"/)
        assert.doesNotMatch(home, /<link\b[^>]*rel="(?:shortcut )?icon"[^>]*href="[^"]*avatar\.jpg/)
        assert.match(home, /<img\b[^>]*src="\/blog\/img\/avatar\.jpg"/)
        for (const asset of ['site-logo.jpg', 'avatar.jpg']) {
            assert.deepEqual(
                fs.readFileSync(path.join(sandbox, '.build/blog/img', asset)),
                fs.readFileSync(path.join(root, 'source/img', asset)),
            )
        }
        const appHtml = fs.readFileSync(path.join(root, 'app/index.html'), 'utf8')
        assert.match(appHtml, /rel="icon"[^>]*href="\/blog\/img\/site-logo\.jpg"/)
        assert.doesNotMatch(appHtml, /href="[^"]*avatar\.jpg/)
        for (const id of ['console', 'nav-music'])
            assert.match(home, new RegExp(`id="${id}"`))
        // User requested restoration: these must be rendered, not merely
        // retained as hidden configuration or an empty recommendation shell.
        assert.equal(hexo.theme.config.home_top.enable, true)
        assert.equal(hexo.theme.config.home_top.swiper.enable, true)
        for (const id of ['home_top', 'random-banner', 'swiper_container', 'bannerGroup', 'topPostGroup'])
            assert.match(home, new RegExp(`id="${id}"`))
        assert.equal((home.match(/class="categoryButton /g) || []).length, 3)
        assert.match(home, /class="blog-slider__title"[^>]*>隔离构建测试</)
        assert.match(home, /class="card-widget card-info"/)
        assert.match(home, /class="card-webinfo"/)
        assert.match(home, /card-announcement/)
        assert.match(home, /href="\/blog\/#recent-posts"/)
        assert.match(home, /href="\/blog\/music\/"/)
        assert.match(home, /href="\/blog\/about\/"/)
        assert.match(home, /template data-site-meting/)
        assert.match(home, /"id":"939817038"/)
        assert.match(home, /"source":"imgbed"/)
        assert.match(home, /new Pjax\(/)
        assert.match(home, /type="module" src="\/assets\/widget-test\.js"/)
        assert.doesNotMatch(home, /href="https:\/\/(?:hexo\.anheyu\.com|image\.anheyu\.com)/)
        const music = fs.readFileSync(path.join(sandbox, '.build/blog/music/index.html'), 'utf8')
        assert.match(music, /id="anMusic-page"/)
        assert.match(music, /src="\/blog\/js\/site-click-effect\.js"/)
        const musicStyles = postcss.parse(fs.readFileSync(path.join(sandbox, '.build/blog/css/index.css'), 'utf8'))
        for (const [mode, color] of [['light', '#4f46e5'], ['dark', '#a5b4fc']]) {
            let found = false
            musicStyles.walkRules(rule => {
                if (!rule.selector.includes(`[data-theme="${mode}"]`)) return
                rule.walkDecls('--anzhiyu-theme', decl => { if (decl.value.toLowerCase() === color) found = true })
            })
            assert.ok(found, `${mode} accent must reach real compiled theme CSS`)
        }
        let desktopLayout = false, mobileLayout = false
        musicStyles.walkRules(rule => {
            assert.doesNotMatch(rule.selector, /#anMusic-page\s+meting-js\b/,
                'the persistent media owner is outside this page; style its UI mount instead')
            if (rule.selector === '#anMusic-page #anMusic-page-meting .aplayer') {
                desktopLayout = rule.nodes.some(node => node.prop === 'flex-direction' && node.value === 'row-reverse')
            }
            if (rule.selector === '#anMusic-page #anMusic-page-meting .aplayer .aplayer-body'
                && rule.parent.type === 'atrule' && /max-width:\s*768px/.test(rule.parent.params)) {
                mobileLayout = rule.nodes.some(node => node.prop === 'width' && node.value === '100%')
            }
        })
        assert.ok(desktopLayout, 'keep the original theme cover/list split layout')
        assert.ok(mobileLayout, 'keep the original theme mobile player layout')
        const about = fs.readFileSync(path.join(sandbox, '.build/blog/about/index.html'), 'utf8')
        assert.match(about, /关于 Javerry/)
        const html = fs.readFileSync(path.join(sandbox, '.build/blog/article/19/index.html'), 'utf8')
        assert.match(html, /<a class="site-page" href="\/blog\/"><span> 主页<\/span><\/a>/)
        assert.match(html, /<i aria-hidden="true" class="anzhiyufont anzhiyu-icon-box-archive/)
        for (const slug of ['archives', 'categories', 'tags'])
            assert.ok(html.includes(`href="/blog/${slug}/"`), `${slug} 页面入口不能删除`)
        assert.match(html, /data-blog-interactions="19"/)
        assert.match(html, /src="\/blog\/js\/site-click-effect\.js"/)
        assert.match(html, /type="module" src="\/assets\/widget-test\.js"/)
        assert.match(html, /href="\/assets\/widget-test\.css"/)
        assert.match(html, /HTML 正文/)
        assert.match(html, /post-copyright/)
        assert.match(html, /medium-zoom\.min\.js/)
        assert.doesNotMatch(html, /alert\(1\)/)
        assert.match(html, /href="\/blog\/categories\/[^"\s]+/)
        assert.match(html, /src="\/blog\/vendor\/qrcode\.min\.js"/)
        assert.doesNotMatch(html, /qrcode-(weichat|alipay)/)
        assert.ok(
            !html.includes('/blog/blog/'),
            html.match(/.{0,80}\/blog\/blog\/.{0,100}/g)?.join('\n'),
        )
        for (const file of [
            'pluginsSrc/medium-zoom/dist/medium-zoom.min.js',
            'pluginsSrc/anzhiyu-theme-static/swiper/swiper.min.js',
            'pluginsSrc/anzhiyu-blog-static/js/APlayer.min.js',
            'pluginsSrc/hexo-anzhiyu-music/assets/js/Meting2.min.js',
            'pluginsSrc/pjax/pjax.min.js',
            'pluginsSrc/node-snackbar/dist/snackbar.min.js',
        ]) assert.ok(fs.existsSync(path.join(sandbox, '.build/blog', file)), file)
        assert.doesNotMatch(html, /content="(?:code-)?xxx"/)
        assert.doesNotMatch(html, /href="\/(?:blog\/)?(?:login|profile|ai)(?:[/?"])/)
        let randomTarget
        const window = {
            location: {
                assign: (value) => {
                    randomTarget = value
                },
            },
        }
        vm.runInNewContext(fs.readFileSync(path.join(sandbox, '.build/blog/anzhiyu/random.js'), 'utf8'), {
            window,
        })
        window.toRandomPost()
        assert.equal(randomTarget, '/blog/article/19/')
        assert.ok(
            fs.existsSync(
                path.join(sandbox, '.build/blog/pluginsSrc/anzhiyu-theme-static/icon/ali_iconfont_css.css'),
            ),
        )
        // Reprocess an edited article, as after adding an image during development.
        // This fixture URL is rendered only; no network request or real upload occurs.
        fs.writeFileSync(path.join(sandbox, 'source/_posts', post.name),
            post.text + '\n<p>新增图片回归</p><img src="https://img.example.test/file/blog/articles/updated.png">\n')
        await hexo.call('generate')
        const updated = fs.readFileSync(path.join(sandbox, '.build/blog/article/19/index.html'), 'utf8')
        assert.match(updated, /新增图片回归/)
        assert.match(updated, /https:\/\/img\.example\.test\/file\/blog\/articles\/updated\.png/)
        assert.match(updated, /id="console"/)
        assert.deepEqual(hexo.theme.config.reward.QR_code, [])
    } finally {
        await hexo.exit()
        const link = path.join(sandbox, 'node_modules')
        if (fs.existsSync(link)) fs.unlinkSync(link)
        if (path.dirname(sandbox) !== root || !path.basename(sandbox).startsWith('.test-site-'))
            throw new Error('测试清理路径无效')
        fs.rmSync(sandbox, {recursive: true, force: true})
    }
})
