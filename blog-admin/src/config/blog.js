export function resolvePublicBlogUrl(configured, {origin = '', development = false} = {}) {
    const value = configured?.trim() || (development ? 'http://127.0.0.1:5173' : origin)
    const url = new URL(value)
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash)
        throw new Error('公开站点地址须为不含凭据、查询或片段的 HTTP/HTTPS 地址')
    if (!['/', '/blog', '/blog/'].includes(url.pathname))
        throw new Error('公开博客固定使用 /blog/ 路径')
    return `${url.origin}/blog/`
}

export function articlePublicUrl(base, id) {
    if (!base || !/^[1-9]\d*$/.test(String(id)) || !Number.isSafeInteger(Number(id))) return ''
    return `${base}article/${id}/`
}

export const publicBlogUrl = (() => {
    try {
        return resolvePublicBlogUrl(import.meta.env.VITE_BLOG_SITE_URL, {
            origin: typeof window === 'undefined' ? '' : window.location.origin,
            development: import.meta.env.DEV,
        })
    } catch {
        // Do not render a malformed, credential-bearing or script URL.
        return ''
    }
})()
