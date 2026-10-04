'use strict'
// Isolated repository browser test, fixture APIs only. Never accesses the user's browser or real backend.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'),
    assert = require('node:assert/strict')
const adminDist = path.resolve(__dirname, '../dist'), publicDist = path.resolve(__dirname, '../../blog-web/dist')
const types = {
    '.html': 'text/html',
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.webp': 'image/webp',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.woff2': 'font/woff2'
}

async function serve(root) {
    const server = http.createServer((req, res) => {
        const pathname = new URL(req.url, 'http://127.0.0.1').pathname
        const requested = path.resolve(root, '.' + pathname)
        if (!requested.startsWith(root + path.sep) && requested !== root) {
            res.writeHead(403);
            res.end();
            return
        }
        const target = fs.existsSync(requested) && fs.statSync(requested).isDirectory() ? path.join(requested, 'index.html') : requested
        const file = fs.existsSync(target) && fs.statSync(target).isFile() ? target : path.join(root, 'index.html')
        res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'application/octet-stream'});
        fs.createReadStream(file).pipe(res)
    })
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    return {server, origin: `http://127.0.0.1:${server.address().port}`}
}

async function run() {
    const admin = await serve(adminDist), site = await serve(publicDist)
    const browser = await chromium.launch({
        headless: true,
        executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH || chromium.executablePath()
    })
    const errors = [], passed = []
    try {
        const context = await browser.newContext({viewport: {width: 1440, height: 1000}, reducedMotion: 'reduce'})
        let loginCalls = 0, passwordCalls = 0, logoutCalls = 0, avatarUploads = 0, profileSaves = 0,
            failNextProfile = false
        const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aKTkAAAAASUVORK5CYII=', 'base64')
        const owner = {
            userId: 42,
            phone: '19140838906',
            username: 'fixture-owner',
            nickname: '验收博主',
            email: 'fixture@example.test',
            avatar: admin.origin + '/file/fixture-avatar-original.png',
            owner: true
        }
        await context.route('**/file/fixture-avatar-*.png', route => route.fulfill({
            status: 200,
            contentType: 'image/png',
            body: png
        }))
        await context.route('**/api/**', async route => {
            const req = route.request(), pathname = new URL(req.url()).pathname
            let data
            if (pathname === '/api/auth/login/password') {
                assert.equal(req.postDataJSON().phone, owner.phone);
                loginCalls++;
                data = {...owner, token: 'isolated-owner-fixture'}
            } else if (pathname === '/api/auth/me') data = owner
            else if (pathname === '/api/admin/dashboard') data = {
                totalArticles: 0,
                publishedArticles: 0,
                totalComments: 0,
                pendingComments: 0,
                totalUsers: 1,
                recentArticles: []
            }
            else if (pathname === '/api/auth/password') {
                passwordCalls++;
                data = null
            } else if (pathname === '/api/auth/logout') {
                logoutCalls++;
                data = null
            } else if (pathname === '/api/images/avatar') {
                assert.equal(req.method(), 'POST');
                assert.ok(req.headers()['content-type'].startsWith('multipart/form-data;'))
                assert.equal(req.headers().authorization, 'isolated-owner-fixture');
                avatarUploads++
                data = {
                    url: admin.origin + '/file/fixture-avatar-' + avatarUploads + '.png',
                    path: 'blog/avatars/42/fixture-' + avatarUploads + '.png',
                    mediaType: 'image/png',
                    size: png.length
                }
            } else if (pathname === '/api/auth/profile') {
                assert.equal(req.method(), 'PUT');
                profileSaves++
                if (failNextProfile) {
                    failNextProfile = false
                    await route.fulfill({
                        status: 503,
                        contentType: 'application/json',
                        body: JSON.stringify({code: 503, message: '资料保存暂时失败'})
                    });
                    return
                }
                Object.assign(owner, req.postDataJSON());
                data = owner
            } else throw Error('Unexpected owner fixture API: ' + pathname)
            await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({code: 0, data})})
        })
        const page = await context.newPage();
        page.on('pageerror', e => errors.push(e.message))
        await page.goto(admin.origin + '/#/login', {waitUntil: 'networkidle'})
        const account = page.locator('input[autocomplete="username"]')
        assert.equal(await account.inputValue(), owner.phone);
        assert.ok(await account.getAttribute('readonly') !== null)
        assert.equal(await page.getByText('短信登录', {exact: true}).count(), 0);
        assert.equal(await page.getByText('获取验证码', {exact: true}).count(), 0)
        await page.getByPlaceholder('输入博主密码').fill('isolated-fixture-password')
        await page.getByRole('button', {name: '登录控制台'}).click()
        await page.getByRole('heading', {name: '仪表盘', exact: true}).waitFor();
        assert.equal(loginCalls, 1)
        passed.push('password-only-login')
        await page.goto(admin.origin + '/#/users', {waitUntil: 'networkidle'})
        await page.getByRole('heading', {name: '博主设置', exact: true}).waitFor()
        assert.ok(page.url().endsWith('#/account'));
        assert.equal(await page.locator('#profile-phone').inputValue(), owner.phone)
        assert.equal(await page.locator('#profile-avatar').count(), 1);
        passed.push('owner-settings-and-retired-users-redirect')
        assert.equal(await page.locator('#profile-nickname').inputValue(), owner.nickname)
        assert.equal(await page.locator('#profile-email').inputValue(), owner.email)
        await page.locator('#profile-avatar').setInputFiles({
            name: 'empty.png',
            mimeType: 'image/png',
            buffer: Buffer.alloc(0)
        })
        await page.getByText('请选择非空且 2 MiB 以内的 JPEG / PNG / GIF / WebP 图片', {exact: true}).waitFor()
        assert.equal(avatarUploads, 0)
        await page.locator('#profile-avatar').setInputFiles({name: 'avatar.png', mimeType: 'image/png', buffer: png})
        await page.getByText('头像已上传，请保存修改；原头像不会自动删除', {exact: true}).waitFor()
        assert.equal(profileSaves, 0);
        assert.equal(avatarUploads, 1)
        assert.ok((await page.locator('.avatar-img').getAttribute('src')).endsWith('/fixture-avatar-1.png'))
        assert.ok((await page.locator('.sidebar-user img').getAttribute('src')).endsWith('/fixture-avatar-original.png'))
        await page.locator('#profile-nickname').fill('新博主昵称');
        await page.locator('#profile-email').fill('updated@example.test')
        failNextProfile = true;
        await page.getByRole('button', {name: '保存修改', exact: true}).click()
        await page.locator('.profile-card .msg.error').filter({hasText: '资料保存暂时失败'}).waitFor();
        assert.equal(profileSaves, 1);
        assert.equal(avatarUploads, 1)
        await page.getByRole('button', {name: '保存修改', exact: true}).click()
        await page.getByText('博主资料已更新', {exact: true}).waitFor();
        assert.equal(profileSaves, 2);
        assert.equal(avatarUploads, 1)
        assert.equal(owner.nickname, '新博主昵称');
        assert.equal(owner.email, 'updated@example.test')
        assert.ok((await page.locator('.sidebar-user img').getAttribute('src')).endsWith('/fixture-avatar-1.png'))
        await page.locator('#profile-avatar').setInputFiles({
            name: 'another-avatar.png',
            mimeType: 'image/png',
            buffer: png
        })
        await page.getByRole('button', {name: '撤销更换', exact: true}).waitFor();
        await page.getByRole('button', {name: '撤销更换', exact: true}).click()
        await page.getByText('已撤销头像更换；已上传图片不会自动删除', {exact: true}).waitFor()
        assert.ok((await page.locator('.avatar-img').getAttribute('src')).endsWith('/fixture-avatar-1.png'));
        assert.equal(profileSaves, 2)
        await page.goto(admin.origin + '/#/dashboard', {waitUntil: 'networkidle'})
        await page.locator('.sidebar-user[title="编辑博主资料"]').click();
        await page.getByRole('heading', {name: '博主设置', exact: true}).waitFor()
        assert.equal(await page.locator('#profile-nickname').inputValue(), owner.nickname)
        assert.equal(await page.locator('#profile-email').inputValue(), owner.email)
        await page.setViewportSize({width: 390, height: 844})
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        await page.locator('.hamburger').click();
        await page.locator('.mobile-nav-item').filter({hasText: '博主设置'}).click()
        assert.equal(await page.locator('#profile-nickname').inputValue(), owner.nickname)
        await page.setViewportSize({width: 1440, height: 1000})
        passed.push('admin-avatar-profile-save-retry-and-cancel')
        await page.locator('#profile-old-password').fill('old-fixture-password');
        await page.locator('#profile-new-password').fill('new-fixture-password')
        await page.getByRole('button', {name: '修改密码', exact: true}).click()
        await page.getByRole('button', {name: '登录控制台'}).waitFor();
        assert.equal(passwordCalls, 1)
        assert.equal(await page.evaluate(() => localStorage.getItem('admin_token')), null);
        passed.push('password-change-reauthentication')
        await page.getByPlaceholder('输入博主密码').fill('isolated-fixture-password');
        await page.getByRole('button', {name: '登录控制台'}).click()
        await page.getByRole('heading', {name: '仪表盘', exact: true}).waitFor();
        await page.getByRole('button', {name: '退出', exact: true}).first().click()
        await page.getByRole('button', {name: '登录控制台'}).waitFor();
        assert.equal(logoutCalls, 1);
        passed.push('server-logout')
        await context.close()
        const expired = await browser.newContext();
        await expired.addInitScript(() => localStorage.setItem('admin_token', 'expired-fixture'))
        await expired.route('**/api/auth/me', r => r.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({code: 401, message: '请先登录'})
        }))
        const expiredPage = await expired.newPage();
        expiredPage.on('pageerror', e => errors.push(e.message))
        await expiredPage.goto(admin.origin + '/#/logs', {waitUntil: 'networkidle'});
        await expiredPage.getByRole('button', {name: '登录控制台'}).waitFor()
        assert.equal(await expiredPage.evaluate(() => localStorage.getItem('admin_token')), null);
        passed.push('legacy-business-401-rejected');
        await expired.close()
        const visitors = await browser.newContext({viewport: {width: 390, height: 844}})
        const publicAccountCalls = []
        await visitors.route('**/api/**', async route => {
            const req = route.request(), pathname = new URL(req.url()).pathname
            if (pathname.startsWith('/api/auth/') || pathname === '/api/images/avatar' || req.method() !== 'GET') publicAccountCalls.push(pathname)
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({code: 0, data: []})
            })
        })
        await visitors.addInitScript(() => localStorage.setItem('blog_token', 'former-visitor-fixture'))
        const publicPage = await visitors.newPage();
        publicPage.on('pageerror', e => errors.push(e.message))
        for (const route of ['/login', '/profile', '/ai']) {
            await publicPage.goto(site.origin + route, {waitUntil: 'networkidle'});
            await publicPage.waitForURL('**/blog/')
            assert.equal(await publicPage.locator('a[href="/login"],a[href="/profile"],a[href="/ai"]').count(), 0)
            assert.equal(await publicPage.locator('#profile-avatar,#profile-nickname,#profile-email').count(), 0)
            assert.ok(await publicPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        }
        assert.deepEqual(publicAccountCalls, []);
        passed.push('public-retired-routes-and-mobile');
        await visitors.close()
        assert.deepEqual(errors, []);
        console.log(JSON.stringify({passed, errors}))
    } finally {
        await browser.close();
        await Promise.all([admin, site].map(item => new Promise(resolve => item.server.close(resolve))))
    }
}

run().catch(error => {
    console.error(error);
    process.exitCode = 1
})
