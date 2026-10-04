'use strict'
// Isolated visual-QA origin. No database, CLI, real password, token or provider requests.
const fs = require('node:fs'), path = require('node:path')

async function main() {
    const root = path.resolve(__dirname, '..')
    let authorized = false, songs = null
    const {createServer} = await import('vite')
    const server = await createServer({
        root, configFile: path.join(root, 'vite.config.js'),
        server: {host: '127.0.0.1', port: 5194, strictPort: true},
        plugins: [{
            name: 'isolated-music-preview', configureServer(vite) {
                vite.middlewares.use(async (req, res, next) => {
                    if (req.url === '/' || req.url === '/index.html') {
                        const original = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
                        const html = original.replace('</head>', `<script>localStorage.setItem('admin_token','isolated-music-preview-only')</script><style>body:before{content:'音乐管理 · 隔离验收数据，不连接网易云';display:block;background:#e7eff8;color:#2e3852;padding:8px;text-align:center;font:13px sans-serif}</style></head>`)
                        res.setHeader('Content-Type', 'text/html; charset=utf-8');
                        res.end(await vite.transformIndexHtml('/', html));
                        return
                    }
                    if (!req.url?.startsWith('/api/')) return next()
                    let data
                    if (req.url === '/api/auth/me') data = {
                        userId: 1,
                        owner: true,
                        nickname: '验收博主',
                        phone: 'fixture-owner',
                        username: 'fixture'
                    }
                    else if (req.url === '/api/admin/music/status') data = {
                        configured: true,
                        authorized,
                        playlistId: '939817038'
                    }
                    else if (req.url === '/api/admin/music/playlist') data = songs
                    else if (req.url === '/api/admin/music/authorize') {
                        data = {
                            authorized: false,
                            authorizationUrl: 'https://163cn.tv/fixture',
                            expiresAt: new Date(Date.now() + 240000).toISOString()
                        }
                        // Demo grant, not a real account login. No external authorization is performed.
                        setTimeout(() => {
                            authorized = true
                        }, 12000)
                    } else if (req.url === '/api/admin/music/sync') data = songs = {
                        playlistId: '939817038', name: '博客歌单（验收数据）', syncedAt: new Date().toISOString(),
                        songs: [{id: '1', name: '验收歌曲', artist: '验收歌手', ownerPlayable: true, preview: false}],
                    }
                    else {
                        res.statusCode = 404;
                        res.end();
                        return
                    }
                    res.setHeader('Content-Type', 'application/json');
                    res.end(JSON.stringify({code: 0, data}))
                })
            }
        }]
    })
    await server.listen();
    console.log('Isolated music visual preview: http://127.0.0.1:5194/#/music')
}

main().catch(() => {
    console.error('Preview failed');
    process.exitCode = 1
})
