// The surprise feature deliberately lives outside the theme package so it can
// keep the original AnZhiYu layout while adding a blog-owned interaction.
(() => {
    if (window.siteSurpriseInitialized) return
    window.siteSurpriseInitialized = true
    let prepared = null
    let overlay = null

    const blogPath = value => {
        const url = new URL(value, window.location.href)
        if (url.origin === window.location.origin && url.pathname === '/surprise/') url.pathname = '/blog/surprise/'
        return url.href
    }

    const loadVideo = async () => {
        if (!prepared) {
            prepared = fetch('/api/surprise/random', {headers: {Accept: 'application/json'}, cache: 'no-store'})
                .then(response => response.ok ? response.json() : response.json().then(body => Promise.reject(new Error(body.message || '暂无可播放视频'))))
                .then(body => body.data)
                .catch(error => { prepared = null; throw error })
        }
        return prepared
    }

    const close = () => {
        overlay?.remove()
        overlay = null
        document.body.classList.remove('site-surprise-open')
        prepared = null
    }

    const render = () => {
        if (overlay) return
        overlay = document.createElement('div')
        overlay.className = 'site-surprise-overlay'
        overlay.innerHTML = `<div class="site-surprise-dialog" role="dialog" aria-modal="true" aria-labelledby="site-surprise-title">
            <button class="site-surprise-close" type="button" aria-label="关闭">×</button>
            <p class="site-surprise-kicker">准备好了吗？</p>
            <h2 id="site-surprise-title">你是否已经成年？</h2>
            <div class="site-surprise-actions"><button type="button" data-surprise-choice>已满18岁</button><button type="button" data-surprise-choice>未满18岁</button></div>
            <p class="site-surprise-error" role="alert" hidden></p>
            <video class="site-surprise-video" controls playsinline preload="auto" hidden></video>
        </div>`
        document.body.append(overlay)
        document.body.classList.add('site-surprise-open')
        overlay.querySelector('.site-surprise-close').addEventListener('click', close)
        overlay.addEventListener('click', event => { if (event.target === overlay) close() })
        overlay.querySelectorAll('[data-surprise-choice]').forEach(button => button.addEventListener('click', async () => {
            const video = overlay.querySelector('.site-surprise-video')
            const error = overlay.querySelector('.site-surprise-error')
            try {
                const source = await loadVideo()
                video.src = source.url
                video.hidden = false
                video.muted = false
                video.volume = 1
                overlay.classList.add('is-fullscreen')
                await video.play()
                overlay.classList.add('is-playing')
                overlay.querySelector('.site-surprise-actions').hidden = true
                overlay.querySelector('.site-surprise-copy').textContent = source.title || '随机惊喜视频'
            } catch (reason) {
                error.textContent = reason.message || '暂时没有可播放的惊喜视频'
                error.hidden = false
            }
        }))
    }

    const open = event => {
        const link = event.target.closest('a[href]')
        if (!link || !/\/blog\/surprise\/?(?:#.*)?$/.test(new URL(link.href, window.location.href).pathname)) return
        event.preventDefault()
        event.stopPropagation()
        // The persistent APlayer lives outside PJAX, so navigating to the
        // surprise experience does not destroy it. Pause it explicitly before
        // showing the age-choice overlay to prevent music and video overlap.
        window.siteMusicPause?.()
        // Preload while the navigation click is still fresh. The choice click
        // itself then starts the video with sound on the same document.
        loadVideo().catch(() => {})
        render()
    }

    document.addEventListener('click', open, true)
    document.addEventListener('pjax:send', close)
    const openOnSurprisePage = () => {
        if (document.querySelector('[data-surprise-page]')) {
            window.siteMusicPause?.()
            loadVideo().catch(() => {})
            render()
        }
    }
    document.addEventListener('DOMContentLoaded', openOnSurprisePage)
    document.addEventListener('pjax:complete', openOnSurprisePage)
    window.addEventListener('pagehide', close)
})()
