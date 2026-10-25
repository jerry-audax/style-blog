// The surprise feature deliberately lives outside the theme package so it can
// keep the original AnZhiYu layout while adding a blog-owned interaction.
(() => {
    if (window.siteSurpriseInitialized) return
    window.siteSurpriseInitialized = true
    let prepared = null
    let overlay = null

    const pauseMusic = () => window.siteMusicPause?.()

    const loadVideo = async () => {
        if (!prepared) {
            const request = fetch('/api/surprise/random', {headers: {Accept: 'application/json'}, cache: 'no-store'})
                .then(response => response.ok ? response.json() : response.json().then(body => Promise.reject(new Error(body.message || '暂无可播放视频'))))
                .then(body => body.data)
                .catch(error => {
                    // A closed experience must not discard a newer request.
                    if (prepared === request) prepared = null
                    throw error
                })
            prepared = request
        }
        return prepared
    }

    const close = () => {
        const current = overlay
        overlay = null
        prepared = null
        const video = current?.querySelector('video')
        if (video) {
            video.pause()
            video.removeAttribute('src')
            video.load()
        }
        current?.remove()
        document.body.classList.remove('site-surprise-open')
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
        const current = overlay
        const active = () => overlay === current && current.isConnected
        const video = current.querySelector('.site-surprise-video')
        const error = current.querySelector('.site-surprise-error')
        const choices = current.querySelectorAll('[data-surprise-choice]')
        let starting = false
        current.querySelector('.site-surprise-close').addEventListener('click', close)
        current.addEventListener('click', event => { if (event.target === current) close() })
        video.addEventListener('play', () => {
            if (active()) pauseMusic()
            else video.pause()
        })
        choices.forEach(button => button.addEventListener('click', async () => {
            if (!active() || starting) return
            starting = true
            choices.forEach(choice => { choice.disabled = true })
            error.hidden = true
            pauseMusic()
            try {
                const source = await loadVideo()
                if (!active()) return
                pauseMusic()
                video.src = source.url
                video.hidden = false
                video.muted = false
                video.volume = 1
                // Keep native controls full-screen even if autoplay is denied.
                current.classList.add('is-fullscreen', 'is-playing')
                current.querySelector('.site-surprise-actions').hidden = true
                await video.play()
                if (!active()) video.pause()
            } catch (reason) {
                if (!active()) return
                // Autoplay policy is not a broken resource: leave native controls
                // available for a second user gesture without fetching again.
                if (reason?.name === 'NotAllowedError') return
                current.classList.remove('is-fullscreen', 'is-playing')
                current.querySelector('.site-surprise-actions').hidden = false
                video.pause()
                video.removeAttribute('src')
                video.load()
                video.hidden = true
                prepared = null
                error.textContent = '视频暂时无法播放，请重新选择重试。'
                error.hidden = false
            } finally {
                starting = false
                if (active()) choices.forEach(choice => { choice.disabled = false })
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
        pauseMusic()
        // Preload while the navigation click is still fresh. The choice click
        // itself then starts the video with sound on the same document.
        loadVideo().catch(() => {})
        render()
    }

    document.addEventListener('click', open, true)
    document.addEventListener('pjax:send', close)
    const openOnSurprisePage = () => {
        if (document.querySelector('[data-surprise-page]')) {
            pauseMusic()
            loadVideo().catch(() => {})
            render()
        }
    }
    document.addEventListener('DOMContentLoaded', openOnSurprisePage)
    document.addEventListener('pjax:complete', openOnSurprisePage)
    window.addEventListener('pagehide', close)
})()
