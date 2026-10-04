/* Site-owned implementation of colorful click feedback, inspired by
   https://github.com/ColdDay/click-colorful (visual reference only).
   That repository declares no license; its implementation is not vendored.
   Only CSS transform/opacity animate. No canvas, frame loop or remote code. */
;(function (view, document) {
    'use strict'
    if (view.siteClickEffect) return
    let config
    try {
        config = JSON.parse(document.getElementById('site-click-effect-config')?.textContent || 'null')
    } catch {
        return
    }
    if (config?.enable !== true) return

    const limit = (value, fallback, min, max) =>
        Math.max(min, Math.min(max, Math.trunc(Number.isFinite(value) ? value : fallback)))
    const count = limit(config.maxCount, 30, 1, 50)
    const size = limit(config.size, 30, 4, 60)
    const duration = limit(config.duration, 1000, 200, 2000)
    const capacity = Math.max(count, limit(config.maxActiveBalls, 150, 1, 300))
    const validColors = Array.isArray(config.colors)
        ? config.colors.filter(color => typeof color === 'string' && /^#[\da-f]{3}(?:[\da-f]{3})?$/i.test(color)).slice(0, 16)
        : []
    const colors = validColors.length ? validColors : ['#eb125f', '#6eff8a', '#6386ff', '#f9f383']
    const reduced = view.matchMedia?.('(prefers-reduced-motion: reduce)')
    const layer = document.createElement('div')
    layer.id = 'site-click-effect'
    layer.setAttribute('aria-hidden', 'true')
    layer.setAttribute('inert', '')
    document.body.append(layer) // Outside #body-wrap; keep one owner across PJAX.
    const bursts = new Set()
    let total = 0, destroyed = false

    const remove = burst => {
        if (!bursts.delete(burst)) return
        view.clearTimeout(burst.timer)
        burst.element.remove()
        total -= count
    }
    const clear = () => {
        for (const burst of bursts) remove(burst)
    }
    const onClick = event => {
        // One click handles both mouse and the browser's synthesized tap. No
        // touchstart handler: scrolling must never burst or be prevented.
        if (destroyed || reduced?.matches || document.visibilityState === 'hidden'
            || event.button !== 0 || event.detail === 0
            || !Number.isFinite(event.clientX) || !Number.isFinite(event.clientY)) return
        const target = event.target?.closest ? event.target : event.target?.parentElement
        if (target?.closest('input, textarea, select, audio, video, [contenteditable]:not([contenteditable="false"]), [role="slider"], .aplayer-bar-wrap, .aplayer-volume-bar-wrap, [data-no-click-effect]')) return
        while (total + count > capacity) remove(bursts.values().next().value)
        const element = document.createElement('div')
        element.className = 'site-click-burst'
        element.style.left = event.clientX + 'px'
        element.style.top = event.clientY + 'px'
        element.style.setProperty('--ball-size', size + 'px')
        element.style.setProperty('--burst-duration', duration + 'ms')
        for (let i = 0; i < count; i++) {
            const ball = document.createElement('span')
            ball.className = 'site-click-ball'
            const angle = Math.random() * Math.PI * 2
            const distance = 40 + Math.random() * 120
            ball.style.setProperty('--ball-x', Math.cos(angle) * distance + 'px')
            ball.style.setProperty('--ball-y', Math.sin(angle) * distance + 'px')
            ball.style.backgroundColor = colors[Math.floor(Math.random() * colors.length)]
            element.append(ball)
        }
        const burst = {element, timer: null}
        bursts.add(burst)
        total += count
        element.addEventListener('animationend', event => {
            if (event.animationName === 'site-click-ball-burst') remove(burst)
        })
        layer.append(element)
        // Also clean up if CSS is absent or animationend is never delivered.
        burst.timer = view.setTimeout(() => remove(burst), duration + 100)
    }
    const onVisibility = () => {
        if (document.visibilityState === 'hidden') clear()
    }
    const onPreference = () => {
        if (reduced.matches) clear()
    }
    const listenerOptions = {capture: true, passive: true}
    document.addEventListener('click', onClick, listenerOptions)
    document.addEventListener('visibilitychange', onVisibility)
    view.addEventListener('pagehide', clear)
    reduced?.addEventListener('change', onPreference)
    const instance = {
        destroy() {
            if (destroyed) return
            destroyed = true
            clear()
            document.removeEventListener('click', onClick, listenerOptions)
            document.removeEventListener('visibilitychange', onVisibility)
            view.removeEventListener('pagehide', clear)
            reduced?.removeEventListener('change', onPreference)
            layer.remove()
            if (view.siteClickEffect === instance) delete view.siteClickEffect
        },
    }
    view.siteClickEffect = instance
})(window, document)
