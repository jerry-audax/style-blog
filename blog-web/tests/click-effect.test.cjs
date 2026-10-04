const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const {JSDOM} = require('jsdom')
const postcss = require('postcss')

const source = fs.readFileSync(path.join(__dirname, '../source/js/site-click-effect.js'), 'utf8')
const css = fs.readFileSync(path.join(__dirname, '../source/css/site.css'), 'utf8')
const defaults = {
    enable: true, colors: ['#eb125f', '#6eff8a', '#6386ff', '#f9f383'],
    size: 30, maxCount: 30, duration: 1000, maxActiveBalls: 150,
}

function fixture(config = defaults, prefersReduced = false) {
    const dom = new JSDOM(`<style>${css}</style><div id="body-wrap">
    <main><button id="play">播放</button><a id="link" href="/blog/music/">音乐</a>
    <input id="seek" type="range"><textarea></textarea><select></select>
    <div contenteditable="true"><span id="editor">编辑</span></div>
    <div class="aplayer-bar-wrap"><span id="progress"></span></div>
    <div role="slider" id="slider"></div><button data-no-click-effect>跳过动画</button></main>
    </div>`, {runScripts: 'outside-only', url: 'http://localhost/blog/'})
    const view = dom.window, doc = view.document
    const tag = doc.createElement('script')
    tag.id = 'site-click-effect-config'
    tag.type = 'application/json'
    tag.textContent = typeof config === 'string' ? config : JSON.stringify(config)
    doc.body.append(tag)
    Object.defineProperty(doc, 'visibilityState', {value: 'visible', writable: true, configurable: true})
    const reduced = Object.assign(new view.EventTarget(), {matches: prefersReduced})
    view.matchMedia = () => reduced
    const timers = new Map()
    let timerId = 0
    view.setTimeout = (fn, delay) => {
        timers.set(++timerId, {fn, delay});
        return timerId
    }
    view.clearTimeout = id => timers.delete(id)
    view.requestAnimationFrame = () => {
        throw new Error('click feedback must not start a frame loop')
    }
    view.fetch = () => {
        throw new Error('click feedback must not request external resources')
    }
    const run = () => vm.runInContext(source, dom.getInternalVMContext())
    const click = (target = doc.body, overrides = {}) => {
        const event = new view.MouseEvent('click', {
            bubbles: true, cancelable: true, button: 0, detail: 1, clientX: 200, clientY: 180, ...overrides,
        })
        target.dispatchEvent(event)
        return event
    }
    const balls = () => [...doc.querySelectorAll('.site-click-ball')]
    const flush = () => {
        for (const {fn} of [...timers.values()]) fn()
    }
    const close = () => {
        view.siteClickEffect?.destroy();
        dom.window.close()
    }
    run()
    return {view, doc, reduced, timers, run, click, balls, flush, close}
}

test('colorful click has no startup burst, then uses the source-reference defaults at the pointer', () => {
    const f = fixture()
    try {
        assert.equal(f.balls().length, 0)
        assert.equal(f.timers.size, 0)
        const layer = f.doc.getElementById('site-click-effect')
        assert.equal(layer.parentNode, f.doc.body)
        assert.equal(layer.getAttribute('aria-hidden'), 'true')
        assert.ok(layer.hasAttribute('inert'))
        assert.equal(f.view.getComputedStyle(layer).pointerEvents, 'none')
        assert.equal(f.view.getComputedStyle(layer).position, 'fixed')
        assert.equal(f.click().defaultPrevented, false)
        assert.equal(f.balls().length, 30)
        const burst = layer.firstElementChild
        assert.equal(burst.style.left, '200px')
        assert.equal(burst.style.top, '180px')
        assert.equal(burst.style.getPropertyValue('--ball-size'), '30px')
        assert.equal(burst.style.getPropertyValue('--burst-duration'), '1000ms')
        assert.deepEqual([...f.timers.values()].map(timer => timer.delay), [1100])
        const palette = defaults.colors.map(value => {
            const span = f.doc.createElement('span')
            span.style.backgroundColor = value
            return span.style.backgroundColor
        })
        for (const ball of f.balls()) {
            assert.ok(palette.includes(ball.style.backgroundColor))
            assert.equal(f.view.getComputedStyle(ball).pointerEvents, 'none')
            const x = parseFloat(ball.style.getPropertyValue('--ball-x'))
            const y = parseFloat(ball.style.getPropertyValue('--ball-y'))
            assert.ok(Math.hypot(x, y) >= 40 && Math.hypot(x, y) <= 160)
        }
    } finally {
        f.close()
    }
})

test('music pause and navigation handlers still execute even when they stop propagation', () => {
    const f = fixture()
    try {
        const audio = f.doc.createElement('audio')
        audio.currentTime = 37
        f.doc.body.append(audio)
        let playing = false, visited
        const play = f.doc.getElementById('play')
        play.addEventListener('click', event => {
            playing = !playing;
            event.stopPropagation()
        })
        f.click(play)
        assert.equal(playing, true)
        f.click(play)
        assert.equal(playing, false)
        assert.equal(f.balls().length, 60)
        const link = f.doc.getElementById('link')
        link.addEventListener('click', event => {
            visited = link.getAttribute('href')
            event.preventDefault() // Emulate the theme's real PJAX interception.
        })
        f.click(link)
        assert.equal(visited, '/blog/music/')
        assert.equal(f.balls().length, 90)
        assert.equal(audio.currentTime, 37)
        assert.ok(audio.isConnected)
        f.click(play, {detail: 0}) // Keyboard activation still works, without a fake burst at 0,0.
        assert.equal(playing, true)
        assert.equal(f.balls().length, 90)
    } finally {
        f.close()
    }
})

test('form controls, sliders, right clicks and keyboard clicks do not create decorative balls', () => {
    const f = fixture()
    try {
        for (const selector of ['#seek', 'textarea', 'select', '#editor', '#progress', '#slider', '[data-no-click-effect]'])
            assert.equal(f.click(f.doc.querySelector(selector)).defaultPrevented, false)
        f.click(f.doc.body, {button: 2})
        f.click(f.doc.body, {button: 1})
        f.click(f.doc.body, {detail: 0, clientX: 0, clientY: 0})
        assert.equal(f.balls().length, 0)
        assert.equal(f.timers.size, 0)
    } finally {
        f.close()
    }
})

test('a tap creates one burst without preventing touch scrolling or adding a scroll offset', () => {
    const f = fixture()
    try {
        Object.defineProperty(f.view, 'scrollY', {value: 700})
        for (const type of ['touchstart', 'touchmove', 'touchend']) {
            const event = new f.view.Event(type, {bubbles: true, cancelable: true})
            f.doc.body.dispatchEvent(event)
            assert.equal(event.defaultPrevented, false)
        }
        assert.equal(f.balls().length, 0)
        f.click(f.doc.body, {clientX: 28, clientY: 60}) // One synthesized mobile click.
        assert.equal(f.balls().length, 30)
        const burst = f.doc.querySelector('.site-click-burst')
        assert.equal(burst.style.left, '28px')
        assert.equal(burst.style.top, '60px')
    } finally {
        f.close()
    }
})

test('PJAX and repeated script execution preserve one instance and one click listener', () => {
    const f = fixture()
    try {
        const owner = f.view.siteClickEffect, layer = f.doc.getElementById('site-click-effect')
        for (let i = 0; i < 8; i++) {
            f.doc.dispatchEvent(new f.view.Event('pjax:send'))
            f.doc.getElementById('body-wrap').innerHTML = '<main>文章正文</main>'
            f.doc.dispatchEvent(new f.view.Event('pjax:complete'))
            f.run()
        }
        assert.equal(f.view.siteClickEffect, owner)
        assert.equal(f.doc.getElementById('site-click-effect'), layer)
        assert.equal(f.doc.querySelectorAll('#site-click-effect').length, 1)
        f.click()
        assert.equal(f.balls().length, 30)
        assert.equal(f.timers.size, 1)
    } finally {
        f.close()
    }
})

test('rapid clicks evict old bursts and never exceed the configured DOM or timer budget', () => {
    const f = fixture()
    try {
        f.click()
        const oldest = f.doc.querySelector('.site-click-burst')
        for (let i = 0; i < 100; i++) {
            f.click()
            assert.ok(f.balls().length <= 150)
            assert.ok(f.timers.size <= 5)
        }
        assert.equal(oldest.isConnected, false)
        f.flush()
        assert.equal(f.balls().length, 0)
        assert.equal(f.timers.size, 0)
    } finally {
        f.close()
    }
})

test('animationend and the fallback timer remove complete bursts without stale nodes', () => {
    const f = fixture()
    try {
        f.click()
        const event = new f.view.Event('animationend', {bubbles: true})
        Object.defineProperty(event, 'animationName', {value: 'site-click-ball-burst'})
        f.balls()[0].dispatchEvent(event)
        assert.equal(f.balls().length, 0)
        assert.equal(f.timers.size, 0)
        f.click()
        f.flush()
        assert.equal(f.doc.querySelectorAll('.site-click-burst').length, 0)
        assert.equal(f.timers.size, 0)
    } finally {
        f.close()
    }
})

test('reduced motion, page hide and hidden tabs clear pending bursts and allow a later normal click', () => {
    const f = fixture(defaults, true)
    try {
        f.click()
        assert.equal(f.balls().length, 0)
        f.reduced.matches = false
        f.reduced.dispatchEvent(new f.view.Event('change'))
        f.click()
        assert.equal(f.balls().length, 30)
        f.reduced.matches = true
        f.reduced.dispatchEvent(new f.view.Event('change'))
        assert.equal(f.balls().length, 0)
        assert.equal(f.timers.size, 0)
        f.reduced.matches = false
        f.click()
        f.doc.visibilityState = 'hidden'
        f.doc.dispatchEvent(new f.view.Event('visibilitychange'))
        f.click()
        assert.equal(f.balls().length, 0)
        f.doc.visibilityState = 'visible'
        f.doc.dispatchEvent(new f.view.Event('visibilitychange'))
        f.click()
        assert.equal(f.balls().length, 30)
        f.view.dispatchEvent(new f.view.Event('pagehide'))
        assert.equal(f.balls().length, 0)
        assert.equal(f.timers.size, 0)
        f.view.dispatchEvent(new f.view.Event('pageshow'))
        f.click()
        assert.equal(f.balls().length, 30)
    } finally {
        f.close()
    }
})

test('destroy is idempotent and removes every timer, listener and overlay before reinitialization', () => {
    const f = fixture()
    try {
        f.click()
        const owner = f.view.siteClickEffect
        owner.destroy()
        owner.destroy()
        assert.equal(f.view.siteClickEffect, undefined)
        assert.equal(f.doc.getElementById('site-click-effect'), null)
        assert.equal(f.timers.size, 0)
        f.click()
        assert.equal(f.balls().length, 0)
        f.run()
        assert.notEqual(f.view.siteClickEffect, owner)
        f.click()
        assert.equal(f.balls().length, 30)
    } finally {
        f.close()
    }
})

test('invalid or disabled configuration is inert; untrusted colors and excessive budgets are constrained', () => {
    for (const config of ['{broken', null, {enable: false}, {enable: 'true'}]) {
        const f = fixture(config)
        try {
            f.click()
            assert.equal(f.view.siteClickEffect, undefined)
            assert.equal(f.balls().length, 0)
        } finally {
            f.close()
        }
    }
    const f = fixture({
        enable: true, maxCount: 1e9, size: 1e9, duration: 1e9,
        maxActiveBalls: 1, colors: ['</script><script>alert(1)</script>', 'url(https://invalid.test/)']
    })
    try {
        f.click()
        assert.equal(f.balls().length, 50)
        const burst = f.doc.querySelector('.site-click-burst')
        assert.equal(burst.style.getPropertyValue('--ball-size'), '60px')
        assert.equal(burst.style.getPropertyValue('--burst-duration'), '2000ms')
        for (let i = 0; i < 20; i++) f.click()
        assert.equal(f.balls().length, 50)
        assert.equal(f.doc.querySelectorAll('script').length, 1)
    } finally {
        f.close()
    }
})

test('click CSS animates only transform and opacity, with reduced-motion and non-interactive overlay rules', () => {
    const styles = postcss.parse(css)
    const keyframes = styles.nodes.find(node => node.type === 'atrule' && node.name === 'keyframes' && node.params === 'site-click-ball-burst')
    assert.ok(keyframes)
    keyframes.walkDecls(declaration => assert.ok(['transform', 'opacity'].includes(declaration.prop)))
    assert.ok(styles.nodes.some(node => node.type === 'atrule' && node.params === '(prefers-reduced-motion: reduce)'
        && node.nodes.some(rule => rule.selector === '#site-click-effect')))
})
