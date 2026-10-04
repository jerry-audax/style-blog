'use strict'
const fs = require('node:fs')
const path = require('node:path')
hexo.extend.generator.register('blog-vendor-assets', () => {
    const assets = []
    const packageRoot = path.join(hexo.base_dir, 'node_modules/anzhiyu-theme-static')
    for (const dir of ['icon', 'waterfall', 'countup', 'swiper', 'aplayer']) {
        for (const filename of fs.readdirSync(path.join(packageRoot, dir))) {
            const source = path.join(packageRoot, dir, filename)
            if (fs.statSync(source).isFile())
                assets.push({
                    path: `pluginsSrc/anzhiyu-theme-static/${dir}/${filename}`,
                    data: () => fs.createReadStream(source),
                })
        }
    }
    for (const [name, file] of [
        ['medium-zoom', 'dist/medium-zoom.min.js'],
        ['node-snackbar', 'dist/snackbar.min.js'],
        ['node-snackbar', 'dist/snackbar.min.css'],
        ['anzhiyu-blog-static', 'js/APlayer.min.js'],
        ['hexo-anzhiyu-music', 'assets/js/Meting2.min.js'],
        ['pjax', 'pjax.min.js'],
    ]) {
        const source = path.join(hexo.base_dir, 'node_modules', name, file)
        assets.push({path: `pluginsSrc/${name}/${file}`, data: () => fs.createReadStream(source)})
    }
    assets.push({
        path: 'vendor/qrcode.min.js',
        data: () =>
            fs.createReadStream(path.join(hexo.base_dir, 'node_modules/qrcodejs/qrcode.min.js')),
    })
    for (const name of ['hexo-theme-anzhiyu', 'anzhiyu-theme-static', 'qrcodejs', 'medium-zoom', 'node-snackbar', 'anzhiyu-blog-static', 'hexo-anzhiyu-music', 'pjax']) {
        const license = path.join(hexo.base_dir, 'node_modules', name, 'LICENSE')
        if (fs.existsSync(license))
            assets.push({path: `vendor/licenses/${name}.txt`, data: () => fs.createReadStream(license)})
    }
    assets.push({
        path: 'vendor/licenses/react-bits.txt',
        data: () => fs.createReadStream(path.join(hexo.base_dir, 'licenses/react-bits.txt')),
    })
    assets.push({
        path: 'vendor/licenses/ogl.txt',
        data: () => fs.createReadStream(path.join(hexo.base_dir, 'licenses/ogl.txt')),
    })
    return assets
})
