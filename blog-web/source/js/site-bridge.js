// Normalize the official theme's root-level links while keeping real PJAX.
// Browsers retain fragments across the root redirect. Preserve former Vue
// hash links without bringing the retired homepage back into the main bundle.
if (window.location.hash.startsWith('#/')) {
    const legacy = new URL(window.location.hash.slice(1), window.location.origin)
    if (legacy.origin === window.location.origin) {
        if (legacy.pathname.startsWith('/article/')) legacy.pathname = '/blog' + legacy.pathname
        else if (legacy.pathname === '/') legacy.pathname = '/blog/'
        window.location.replace(legacy.href)
    }
}
const siteBlogUrl = value => {
    const url = new URL(value, window.location.href)
    if (url.origin === window.location.origin) {
        if (url.pathname === '/') url.pathname = '/blog/'
        else if (/^\/(archives|categories|tags|article|music|surprise|about)(\/|$)/.test(url.pathname))
            url.pathname = '/blog' + url.pathname
        if (url.searchParams.get('from') === 'blog') url.searchParams.delete('from')
    }
    return url.href
}
// The theme installs the real instance later in this document.
window.pjax = {
    loadUrl(value) {
        window.location.assign(siteBlogUrl(value))
    },
    refresh() {
    },
}

document.addEventListener('DOMContentLoaded', () => {
    if (typeof window.Pjax === 'function' && window.pjax instanceof window.Pjax) {
        const navigate = window.pjax.loadUrl.bind(window.pjax)
        window.pjax.loadUrl = (value, options) => navigate(siteBlogUrl(value), options)
    }
})

let clearMotionPreference = () => {
}
const bindMotionPreference = () => {
    clearMotionPreference()
    const slider = document.getElementById('swiper_container')
    if (!slider) return
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
    const applyMotionPreference = () => {
        if (reduced.matches) slider.swiper?.autoplay?.stop()
        else slider.swiper?.autoplay?.start()
    }
    // The theme creates its slider after a short scheduled initialization.
    const observer = new MutationObserver(() => {
        if (!slider.swiper) return
        applyMotionPreference()
        observer.disconnect()
    })
    observer.observe(slider, {attributes: true, subtree: true})
    reduced.addEventListener('change', applyMotionPreference)
    clearMotionPreference = () => {
        observer.disconnect()
        reduced.removeEventListener('change', applyMotionPreference)
    }
}
document.addEventListener('DOMContentLoaded', bindMotionPreference)
document.addEventListener('pjax:send', () => clearMotionPreference())
document.addEventListener('pjax:complete', bindMotionPreference)
window.addEventListener('pagehide', () => clearMotionPreference())
