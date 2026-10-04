'use strict'
// Optional local acceptance harness. Isolated browser and fixture API only;
// never logs into real accounts or writes to the actual Spring Boot service.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const root = path.resolve(__dirname, '..')
const artifacts = path.join(root, '.artifacts')
const origin = process.env.BLOG_PREVIEW_URL || 'http://127.0.0.1:5173'
if (!['127.0.0.1', 'localhost'].includes(new URL(origin).hostname))
    throw new Error('QA 仅允许本地预览地址')

async function run() {
    fs.mkdirSync(artifacts, {recursive: true})
    const browser = await chromium.launch({
        headless: true,
        executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH || chromium.executablePath(),
    })
    const context = await browser.newContext({
        viewport: {width: 1440, height: 1000},
        reducedMotion: 'reduce',
    })
    const errors = [],
        failed = [],
        result = []
    const page = await context.newPage()
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('response', (response) => {
        if (response.status() >= 400 && new URL(response.url()).origin === origin)
            failed.push(`${response.status()} ${response.url()}`)
    })
    let liked = false,
        comments = []
    await context.route('**/api/**', async (route) => {
        const req = route.request(),
            pathname = new URL(req.url()).pathname
        let data
        if (pathname === '/api/auth/me')
            data = {
                id: 2,
                nickname: '验收用户',
                phone: '13800138000',
                username: 'qa',
                email: '',
                avatar: null,
            }
        else if (pathname === '/api/auth/login/password') data = {token: 'isolated-qa-token'}
        else if (pathname === '/api/images/avatar') data = {
            path: 'blog/avatars/2/qa.png',
            url: origin + '/qa-avatar.png',
            mediaType: 'image/png',
            size: 68
        }
        else if (pathname === '/api/auth/profile') data = {
            id: 2,
            nickname: '验收用户',
            phone: '13800138000',
            username: 'qa', ...req.postDataJSON()
        }
        else if (pathname === '/api/content/article/19')
            data = {id: 19, viewCount: 11, likeCount: 3, isCommentEnabled: 1}
        else if (pathname === '/api/content/article/19/like') data = {liked: (liked = !liked)}
        else if (pathname === '/api/comment/article/19') data = comments
        else if (pathname === '/api/comment' && req.method() === 'POST') {
            const payload = req.postDataJSON()
            comments.push({
                id: 5,
                nickname: '验收用户',
                userId: 2,
                articleId: 19,
                content: payload.content,
                createdAt: '2026-10-02 12:00:00',
            })
            data = {id: 5}
        } else if (pathname === '/api/ai/chat') {
            await route.fulfill({
                status: 200,
                contentType: 'text/event-stream',
                body: 'data: 第一行\ndata: 第二行\n\n',
            })
            return
        } else if (pathname.startsWith('/api/ai/memory/')) data = null
        else {
            await route.fulfill({
                status: 500,
                contentType: 'application/json',
                body: JSON.stringify({code: 500, message: '验收错误状态'}),
            })
            return
        }
        await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({code: 0, data}),
        })
    })

    try {
        const entry = await context.request.get(origin + '/', {maxRedirects: 0})
        assert.equal(entry.status(), 302)
        assert.equal(entry.headers().location, '/blog/')
        for (const [url, name] of [
            ['/', 'blog-desktop'],
            ['/login', 'login-desktop'],
        ]) {
            await page.goto(origin + url, {waitUntil: 'networkidle'})
            await page.screenshot({path: path.join(artifacts, name + '.png'), fullPage: true})
            assert.ok(
                await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
                name + ' 横向溢出',
            )
            if (url === '/') {
                assert.equal(page.url(), origin + '/blog/')
                assert.equal(await page.locator('.portfolio-page').count(), 0)
                assert.ok(
                    (await page.locator('#recent-posts').boundingBox()).y < 140,
                    '博客顶部不能保留宣传区空白',
                )
                assert.ok(
                    !(await page
                        .locator('body')
                        .innerText()
                        .then((text) => /作品集|个人主页/.test(text))),
                )
                assert.ok(
                    !(await page.evaluate(() =>
                        performance
                            .getEntriesByType('resource')
                            .some((entry) => /\/assets\/app-.*\.js/.test(entry.name)),
                    )),
                )
                assert.equal(await page.locator('#menus .menus_item > .site-page').count(), 2)
                const homeGroup = page.locator('#menus .menus_item').filter({
                    has: page.getByRole('link', {name: '主页', exact: true}),
                })
                await homeGroup.getByRole('link', {name: '主页', exact: true}).focus()
                assert.equal(
                    await homeGroup.locator('.menus_item_child').evaluate((node) => getComputedStyle(node).opacity),
                    '1',
                )
                await page.screenshot({path: path.join(artifacts, 'blog-navigation-desktop.png'), fullPage: true})
                for (const [label, slug] of [
                    ['归档', 'archives'],
                    ['分类', 'categories'],
                    ['标签', 'tags'],
                ]) {
                    await page.locator('#menus').getByRole('link', {name: '主页', exact: true}).hover()
                    await page.locator('#menus').getByRole('link', {name: label, exact: true}).click()
                    await page.waitForURL(origin + `/blog/${slug}/`)
                }
                await page.goto(origin + '/blog/', {waitUntil: 'networkidle'})
                await page.evaluate(() => window.toRandomPost())
                await page.waitForURL(origin + '/blog/archives/')
                await page.goto(origin + url, {waitUntil: 'networkidle'})
            }
            result.push(name)
        }
        const vueHome = page.locator('.nav-links .blog-navigation')
        await vueHome.locator('summary').click()
        assert.equal(await vueHome.getByRole('link').count(), 4)
        await page.screenshot({path: path.join(artifacts, 'vue-navigation-desktop.png'), fullPage: true})
        await page.keyboard.press('Escape')
        assert.equal(await vueHome.getAttribute('open'), null)
        await page.getByRole('button', {name: '密码登录', exact: true}).click()
        await page.getByRole('link', {name: '忘记密码？'}).click()
        assert.equal(await page.locator('dialog[open]').count(), 1)
        await page.keyboard.press('Escape')
        assert.equal(await page.locator('dialog[open]').count(), 0)
        await page.getByLabel('手机号', {exact: true}).first().fill('13800138000')
        await page.getByLabel('密码', {exact: true}).fill('fixture-password')
        await page.locator('.submit-btn').first().click()
        await page.waitForURL(origin + '/blog/')
        await page.goto(origin + '/profile', {waitUntil: 'networkidle'})
        assert.equal(await page.getByLabel('昵称').inputValue(), '验收用户')
        assert.equal(await page.locator('input[type="file"]').count(), 1)
        await page.route('**/qa-avatar.png', (route) => route.fulfill({
            status: 200,
            contentType: 'image/png',
            body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH1sAAAAASUVORK5CYII=', 'base64')
        }))
        await page.locator('#profile-avatar').setInputFiles({
            name: 'qa.png',
            mimeType: 'image/png',
            buffer: Buffer.from('fixture-image')
        })
        await page.getByText('头像已上传，请保存修改；原头像不会自动删除', {exact: true}).waitFor()
        await page.getByRole('button', {name: '保存修改', exact: true}).click()
        await page.getByText('个人信息已更新', {exact: true}).waitFor()
        await page.screenshot({path: path.join(artifacts, 'profile-desktop.png'), fullPage: true})
        await page.goto(origin + '/ai', {waitUntil: 'networkidle'})
        await page.getByLabel('技术问题').fill('本地协议验收')
        await page.getByRole('button', {name: '发送', exact: true}).click()
        await page.waitForFunction(() =>
            document.querySelector('.msg.assistant .msg-text')?.textContent.includes('第二行'),
        )
        await page.getByRole('button', {name: '清除记忆', exact: true}).click()
        await page.getByRole('heading', {name: '你好，我是小J'}).waitFor()
        await page.screenshot({path: path.join(artifacts, 'ai-desktop.png'), fullPage: true})
        result.push('login/profile/SSE/reset-memory')
        await page.goto(origin + '/#/profile', {waitUntil: 'networkidle'})
        await page.waitForURL(origin + '/profile')
        assert.equal(await page.getByLabel('昵称').inputValue(), '验收用户')
        result.push('legacy-hash-links')

        // Test the shipped widget in a temporary response, not a fake published post.
        const manifest = JSON.parse(
            fs.readFileSync(path.join(root, '.build/app/.vite/manifest.json'), 'utf8'),
        )
        const widget = manifest['src/interaction-widget.js']
        const assets =
            (widget.css || []).map((file) => `<link rel="stylesheet" href="/${file}">`).join('') +
            `<script type="module" src="/${widget.file}"></script>`
        const html = fs
            .readFileSync(path.join(root, 'dist/blog/index.html'), 'utf8')
            .replace(
                '</body>',
                `<section style="margin:40px" data-blog-interactions="19"></section>${assets}</body>`,
            )
        await context.route('**/blog/__qa-widget/', (route) =>
            route.fulfill({status: 200, contentType: 'text/html', body: html}),
        )
        await page.goto(origin + '/blog/__qa-widget/', {waitUntil: 'networkidle'})
        await page.getByRole('button', {name: '赞同这篇文章'}).click()
        await page.getByRole('button', {name: '取消赞同'}).waitFor()
        await page.getByLabel('写下你的想法').fill('<script>文本不能执行</script>')
        await page.getByRole('button', {name: '发表评论'}).click()
        await page
            .locator('.comment-content')
            .filter({hasText: '<script>文本不能执行</script>'})
            .waitFor()
        assert.equal(await page.locator('.comment-content script').count(), 0)
        await page.screenshot({path: path.join(artifacts, 'blog-widget.png'), fullPage: true})
        result.push('widget/like/comment/XSS-text')

        await page.evaluate(() => localStorage.removeItem('blog_token'))
        await page.setViewportSize({width: 390, height: 844})
        for (const [url, name] of [
            ['/', 'blog-mobile'],
            ['/login', 'login-mobile'],
        ]) {
            await page.goto(origin + url, {waitUntil: 'networkidle'})
            assert.ok(
                await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
                name + ' 横向溢出',
            )
            await page.screenshot({path: path.join(artifacts, name + '.png'), fullPage: true})
            if (url === '/') {
                await page.locator('#toggle-menu a').click()
                await page.locator('#sidebar-menus.open').waitFor()
                assert.equal(await page.locator('#sidebar-menus .menus_item > .site-page').count(), 2)
                const mobileGroup = page.locator('#sidebar-menus .menus_item').filter({
                    has: page.getByRole('link', {name: '主页', exact: true}),
                })
                for (const label of ['归档', '分类', '标签'])
                    assert.ok(await mobileGroup.getByRole('link', {name: label, exact: true}).isVisible())
                await page.screenshot({path: path.join(artifacts, 'blog-navigation-mobile.png'), fullPage: true})
                await mobileGroup.getByRole('link', {name: '分类', exact: true}).click()
                await page.waitForURL(origin + '/blog/categories/')
            }
            result.push(name)
        }
        await page.getByRole('button', {name: '打开导航菜单'}).click()
        await page.locator('dialog[open]').waitFor()
        const mobileHome = page.locator('dialog[open] .blog-navigation')
        await mobileHome.locator('summary').click()
        assert.equal(await mobileHome.getByRole('link').count(), 4)
        await page.screenshot({path: path.join(artifacts, 'vue-navigation-mobile.png'), fullPage: true})
        await page.keyboard.press('Escape')
        assert.equal(await mobileHome.getAttribute('open'), null)
        assert.equal(await page.locator('dialog[open]').count(), 1)
        await page.keyboard.press('Escape')
        await page.getByRole('button', {name: '切换深色模式'}).click()
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
        await page.screenshot({path: path.join(artifacts, 'login-mobile-dark.png'), fullPage: true})
        await page.goto(origin + '/profile', {waitUntil: 'networkidle'})
        await page.waitForURL(/\/login\?redirect=/)
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
        await page.screenshot({
            path: path.join(artifacts, 'account-mobile-dark.png'),
            fullPage: true,
        })
        await page.emulateMedia({colorScheme: 'dark'})
        await page.goto(origin + '/blog/', {waitUntil: 'networkidle'})
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
        await page.screenshot({path: path.join(artifacts, 'blog-mobile-dark.png'), fullPage: true})
        result.push('blog-dark-mode')
        const missing = await context.request.get(origin + '/blog/missing-static-page/')
        assert.equal(missing.status(), 404)
        assert.deepEqual(errors, [], '浏览器脚本异常')
        assert.deepEqual(failed, [], '本地资源缺失')
        console.log(JSON.stringify({passed: result, errors, failed, screenshots: artifacts}, null, 2))
    } finally {
        await browser.close()
    }
}

run().catch((error) => {
    console.error(error)
    process.exitCode = 1
})
