(() => {
    if (window.siteMediaLoadingInitialized) return
    window.siteMediaLoadingInitialized = true
    let observer
    const show = node => {
        const value = node.dataset.siteLazyBackground
        try {
            const url = new URL(value,location.href)
            if (!['http:','https:'].includes(url.protocol) || url.username || url.password) return
            node.style.setProperty('background-image',`url(${JSON.stringify(url.href)})`,
                node.dataset.siteLazyBackgroundPriority === 'important' ? 'important' : '')
            delete node.dataset.siteLazyBackground
            delete node.dataset.siteLazyBackgroundPriority
        } catch { /* Invalid decorative sources must not break page navigation. */ }
    }
    const disconnect = () => {observer?.disconnect(); observer=undefined}
    const mount = () => {
        if (!('IntersectionObserver' in window)) {
            document.querySelectorAll('[data-site-lazy-background]').forEach(show)
            return
        }
        observer ||= new IntersectionObserver(entries => {
            for (const entry of entries) if (entry.isIntersecting) {
                show(entry.target); observer?.unobserve(entry.target)
            }
        },{rootMargin:'300px 0px'})
        document.querySelectorAll('[data-site-lazy-background]').forEach(node=>observer.observe(node))
    }
    document.addEventListener('DOMContentLoaded',mount)
    document.addEventListener('pjax:send',disconnect)
    document.addEventListener('pjax:complete',mount)
    window.addEventListener('pagehide',disconnect)
    window.addEventListener('pageshow',event=>{if(event.persisted)mount()})
    if (document.readyState !== 'loading') mount()
})()
