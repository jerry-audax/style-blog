'use strict'
const fs = require('node:fs')
const path = require('node:path')
const {sanitizeContent, articleId} = require('../tools/content.cjs')

// The single Meting/media owner stays outside PJAX's replacement region.
// Re-target only the upstream music-page selectors to the UI mount. Keep all
// original desktop/mobile declarations rather than copying/forking the theme.
hexo.extend.filter.register('after_render:css', css =>
    css.replace(/#anMusic-page\s+meting-js\b/g, '#anMusic-page #anMusic-page-meting'),
)

// Hexo's standard index/archive generators emit no page for an empty blog.
// Keep working navigation without inventing placeholder articles.
hexo.extend.generator.register('empty-blog-pages', () => {
    const posts = hexo.locals.get('posts')
    if (posts.length) return []
    const data = {
        posts,
        current: 1,
        total: 1,
        prev: 0,
        next: 0,
        prev_link: '',
        next_link: '',
        base: '',
        path: 'index.html',
    }
    return [
        {path: 'index.html', layout: 'index', data},
        {
            path: 'archives/index.html',
            layout: 'archive',
            data: {...data, title: '归档', archive: true},
        },
    ]
})

hexo.extend.filter.register('before_generate', () => {
    // The upstream console iterates rewards even when donations are disabled;
    // normalize after theme merging, which otherwise retains default array items.
    if (!hexo.theme.config.reward.enable) hexo.theme.config.reward.QR_code = []
    // Hexo merges arrays with the theme defaults; an empty override otherwise
    // leaves the theme author's demonstration project links in the mobile menu.
    hexo.theme.config.nav.menu = []
    if (process.env.BLOG_SITE_URL) {
        const url = new URL(process.env.BLOG_SITE_URL)
        if (!['https:', 'http:'].includes(url.protocol))
            throw new Error('BLOG_SITE_URL 必须是站点公开 HTTP 地址')
        hexo.config.url = url.origin + '/blog'
        hexo.config.root = '/blog/'
    }
})

// The upstream non-PJAX random link assumes a root-level blog. Keep its public
// function, but produce correct subdirectory URLs and a real empty fallback.
hexo.extend.filter.register('after_generate', () => {
    const paths = hexo.locals
        .get('posts')
        .data.filter((post) => post.random !== false)
        .map((post) => '/blog/' + post.path.replace(/^\/+/, ''))
    hexo.route.set(
        hexo.config.random?.path || 'anzhiyu/random.js',
        `window.toRandomPost = function () {
      const paths = ${JSON.stringify(paths)};
      const target = paths.length ? paths[Math.floor(Math.random() * paths.length)] : '/blog/archives/';
      if (window.pjax) window.pjax.loadUrl(target); else window.location.assign(target);
    };`,
    )
})

hexo.extend.filter.register(
    'after_post_render',
    (data) => {
        data.content = sanitizeContent(data.content)
        if (data.blog_article_id) {
            const id = articleId(data.blog_article_id)
            data.content += `<section data-blog-interactions="${id}" aria-label="文章互动"></section>`
        }
        return data
    },
    20,
)

function widgetAssets() {
    if (process.env.BLOG_DEV === '1')
        return '<script type="module" src="/src/interaction-widget.js"></script><script type="module" src="/src/site-background.js"></script>'
    const manifest = JSON.parse(
        fs.readFileSync(path.join(hexo.base_dir, '.build/app/.vite/manifest.json'), 'utf8'),
    )
    const entries = ['src/interaction-widget.js', 'src/site-background.js'].map(key => manifest[key])
    if (entries.some(entry => !entry)) throw new Error('缺少互动或粒子背景组件，请先运行 npm run build:app')
    const css = new Set()

    function collect(item) {
        ;(item.css || []).forEach((file) => css.add(file))
        ;(item.imports || []).forEach((key) => collect(manifest[key]))
    }

    entries.forEach(collect)
    return (
        [...css].map((file) => `<link rel="stylesheet" href="/${file}">`).join('') +
        entries.map(entry => `<script type="module" src="/${entry.file}"></script>`).join('')
    )
}

hexo.extend.filter.register('after_render:html', (html) => {
    // Icon-font glyphs are decorative; the visible text supplies link names.
    html = html.replaceAll('<i class="anzhiyufont ', '<i aria-hidden="true" class="anzhiyufont ')
    // Preserve the official nested menu component; its parent is also a working
    // home link. The archive/category/tag pages keep their existing URLs.
    html = html.replace(
        /(<a class="site-page" href=")javascript:void\(0\);("><span> 主页<\/span><\/a>)/g,
        '$1/blog/$2',
    )
    // Hexo's Open Graph helper prefixes config.url to an already rooted cover.
    const origin = new URL(hexo.config.url).origin
    html = html.replaceAll(origin + '/blog/blog/', origin + '/blog/')
    html = html.replace(
        'width=device-width, initial-scale=1.0, user-scalable=no',
        'width=device-width, initial-scale=1.0',
    )
    html = html.replace(/(href|src)=(['"])([^'"]*)\2/g, (match, attr, quote, value) => {
        value = value.replaceAll('/blog/blog/', '/blog/')
        if (/^\/(?:blog\/)?\?from=blog$/.test(value)) value = '/blog/'
        else if (/^\/(?:blog\/)?(ai|login|profile)(\/?(?:\?.*)?)$/.test(value))
            value = '/blog/'
        else if (
            value === '/' ||
            value.startsWith('/#') ||
            /^\/(archives|categories|tags|article|music|surprise|about|img|anzhiyu|pluginsSrc)(\/|$)/.test(value)
        )
            value = '/blog' + value
        return `${attr}=${quote}${value}${quote}`
    })
    html = html.replace(
        'https://cdn.cbd.int/qrcodejs@1.0.0/qrcode.min.js',
        '/blog/vendor/qrcode.min.js',
    )
    // Defer the original Meting elements until our subdirectory/error adapter is
    // ready. Templates are inert, so they cannot fetch the author's default list.
    html = html.replace(/<meting-js\b[\s\S]*?<\/meting-js>/g, (element) =>
        `<template data-site-meting>${element}</template>`,
    )
    const music = hexo.theme.config.nav_music
    const musicConfig = JSON.stringify({
        id: String(music.id), server: music.server, volume: music.volume,
        playlist: music.all_playlist,
        source: 'imgbed',
    }).replaceAll('<', '\\u003c')
    html = html.replace('</body>', `<script id="site-music-config" type="application/json">${musicConfig}</script></body>`)
    const click = hexo.theme.config.site_click_effect
    if (click?.enable === true) {
        const config = JSON.stringify(click).replaceAll('<', '\\u003c')
        html = html.replace('</body>', `<script id="site-click-effect-config" type="application/json">${config}</script><script defer src="/blog/js/site-click-effect.js"></script></body>`)
    }
    // Load once even on the homepage: PJAX may later enter an article without
    // executing that document's module/link tags outside the replacement regions.
    html = html.replace('</body>', widgetAssets() + '</body>')
    html = html.replace('if (!window.aplayers[i].options.fixed)',
        'if (!window.aplayers[i].options.fixed && !window.aplayers[i].sitePersistent)')
    if (html.includes('id="recent-posts"') && !hexo.locals.get('posts').length) {
        html = html.replace(
            /(<div[^>]*id="recent-posts"[^>]*>)/,
            '$1<div class="blog-empty"><h2>暂无文章</h2><p>这里记录技术学习、系统设计与开发实践。文章发布后会显示在这里。</p></div>',
        )
    }
    return html
})

// The upstream recommendation helper fills from collection insertion order.
// Keep its component contract, but fill from genuinely newest published posts.
hexo.extend.filter.register('before_generate', () => {
    hexo.extend.helper.register('sort_attr_post', (type) => {
        const attribute = type === 'swiper_list' ? 'swiper_index' : 'top_group_index'
        const posts = hexo.locals.get('posts').sort('date', -1).data
        const pinned = posts.filter(post => Number(post[attribute]) > 0)
            .sort((a, b) => Number(b[attribute]) - Number(a[attribute]))
        const limit = hexo.theme.config.home_top.swiper.enable ? 4 : 6
        return [...pinned, ...posts.filter(post => !pinned.includes(post))].slice(0, limit)
    })
})
