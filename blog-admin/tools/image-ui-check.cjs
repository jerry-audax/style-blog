'use strict'
// Repository acceptance test: isolated headless browser and fixture APIs only.
// No user browser profile, real account, backend or provider is accessed.
const {chromium} = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'),
    assert = require('node:assert/strict')
const dist = path.resolve(__dirname, '../dist')
const artifacts = path.resolve(__dirname, '../../.artifacts/imgbed-qa')
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH1sAAAAASUVORK5CYII=', 'base64')
const types = {'.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.png': 'image/png'}

async function run() {
    let uploadCalls = 0, deleteCalls = 0
    const server = http.createServer((req, res) => {
        const pathname = new URL(req.url, 'http://127.0.0.1').pathname
        const file = path.resolve(dist, '.' + pathname)
        if (!file.startsWith(dist + path.sep) && file !== dist) {
            res.writeHead(403);
            res.end();
            return
        }
        const target = pathname === '/' ? path.join(dist, 'index.html') : file
        if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
            res.writeHead(404);
            res.end();
            return
        }
        res.writeHead(200, {'Content-Type': types[path.extname(target)] || 'application/octet-stream'})
        fs.createReadStream(target).pipe(res)
    })
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
    const origin = `http://127.0.0.1:${server.address().port}`
    const browser = await chromium.launch({
        headless: true,
        executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH || chromium.executablePath()
    })
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}, reducedMotion: 'reduce'})
    const fixture = {
        path: 'blog/articles/fixture.png',
        url: origin + '/fixture.png',
        mediaType: 'image/png',
        size: png.length
    }
    const errors = []
    const page = await context.newPage()
    page.on('pageerror', (e) => errors.push(e.message))
    await context.addInitScript(() => localStorage.setItem('admin_token', 'isolated-fixture-session'))
    await context.route('**/fixture.png', (route) => route.fulfill({status: 200, contentType: 'image/png', body: png}))
    await context.route('**/api/**', async (route) => {
        const req = route.request(), pathname = new URL(req.url()).pathname
        let data
        if (pathname === '/api/auth/me') data = {userId: 1, username: 'qa-owner', nickname: '验收博主', owner: true}
        else if (pathname === '/api/images' && req.method() === 'GET') data = {images: [fixture], total: 1} // deliberately stale after deletion
        else if (pathname === '/api/images' && req.method() === 'DELETE') {
            assert.deepEqual(req.postDataJSON(), {path: fixture.path});
            deleteCalls++;
            data = null
        } else if (pathname.startsWith('/api/images/')) {
            uploadCalls++;
            data = fixture
        } else if (pathname === '/api/content/category/list' || pathname === '/api/content/tag/list') data = []
        else if (pathname === '/api/content/article/list') data = {records: [], total: 0}
        else throw new Error('Unexpected fixture API: ' + pathname)
        await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({code: 0, data})})
    })
    try {
        await page.goto(origin + '/#/images', {waitUntil: 'networkidle'})
        await page.getByRole('heading', {name: '图片管理'}).waitFor()
        assert.equal(await page.locator('.image-card').count(), 1)
        await page.screenshot({path: path.join(artifacts, 'images-desktop.png'), fullPage: true})
        await page.getByRole('button', {name: '删除图片', exact: true}).click()
        await page.getByRole('button', {name: '保留图片', exact: true}).click()
        assert.equal(deleteCalls, 0)
        await page.getByRole('button', {name: '删除图片', exact: true}).click()
        await page.getByRole('button', {name: '确认永久删除', exact: true}).click()
        await page.waitForFunction(() => !document.querySelector('.image-card'))
        assert.equal(deleteCalls, 1)
        await page.getByRole('button', {name: '刷新', exact: true}).click()
        await page.waitForFunction(() => !document.querySelector('.image-card') && !document.querySelector('[aria-busy="true"]'))
        assert.equal(deleteCalls, 1)
        await page.locator('input[type="file"]').setInputFiles({name: 'qa.png', mimeType: 'image/png', buffer: png})
        await page.getByText('图片已上传', {exact: true}).waitFor()
        assert.equal(uploadCalls, 1)
        await page.goto(origin + '/#/article/new', {waitUntil: 'networkidle'})
        await page.getByRole('button', {name: '上传正文图片', exact: true}).waitFor()
        await page.locator('.editor-body [contenteditable="true"]').waitFor()
        await page.locator('input[type="file"]').first().setInputFiles({
            name: 'qa.png',
            mimeType: 'image/png',
            buffer: png
        })
        await page.locator('.editor-body img.resizable-image').waitFor()
        await page.locator('input[type="file"]').last().setInputFiles({
            name: 'cover.png',
            mimeType: 'image/png',
            buffer: png
        })
        await page.locator('img[alt="封面预览"]').waitFor()
        assert.equal(uploadCalls, 3)
        await page.getByRole('button', {name: '取消', exact: true}).first().click()
        assert.equal(deleteCalls, 1, 'Cancel must never delete uploaded images')
        await page.goto(origin + '/#/images', {waitUntil: 'networkidle'})
        await page.getByRole('heading', {name: '图片管理'}).waitFor()
        await page.waitForFunction(() => !document.querySelector('.editor-body'))
        await page.setViewportSize({width: 390, height: 844})
        await page.waitForFunction(() => getComputedStyle(document.querySelector('.page')).opacity === '1')
        await page.screenshot({path: path.join(artifacts, 'images-mobile.png'), fullPage: true})
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        assert.deepEqual(errors, [])
        console.log(JSON.stringify({
            passed: ['image-list', 'confirm-delete', 'cancel-delete', 'stale-list-no-retry', 'image-upload', 'article-image', 'cover-upload', 'cancel-no-cleanup', 'responsive-mobile'],
            uploadCalls,
            deleteCalls,
            errors
        }))
    } catch (error) {
        console.error(JSON.stringify({
            errors, uploadCalls, deleteCalls, url: page.url(),
            editor: await page.locator('.editor-body').evaluateAll(nodes => nodes.map(node => ({
                images: node.querySelectorAll('img').length,
                text: node.innerText.slice(0, 120)
            }))),
            messages: await page.locator('.el-message__content').allTextContents()
        }))
        throw error
    } finally {
        await browser.close();
        await new Promise((resolve) => server.close(resolve))
    }
}

run().catch((e) => {
    console.error(e);
    process.exitCode = 1
})
