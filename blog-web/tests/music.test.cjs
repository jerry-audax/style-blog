const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const {JSDOM} = require('jsdom')

const source = fs.readFileSync(path.join(__dirname, '../source/js/site-music.js'), 'utf8')

function fixture(fetcher, {
    realPlayer = false,
    blockedAutoplay = false,
    mobile = false,
    home = false,
    random = () => 0
} = {}) {
    const dom = new JSDOM(`<div id="nav-music"><a id="nav-music-hoverTips"></a><template data-site-meting><meting-js id="939817038" server="netease" type="playlist"></meting-js></template></div>
    <div id="consoleMusic" class="console-btn-item" onclick="anzhiyu.musicToggle()"><a class="music-switch"><i class="anzhiyufont anzhiyu-icon-music"></i></a></div><div id="an_music_bg"></div><button id="menu-music-toggle"></button>
    <div id="body-wrap"><div id="menu-mask"></div><div id="anMusic-page"><div id="anMusicBtnGetSong"></div><div id="anMusicRefreshBtn"></div><div id="anMusicSwitching"></div><div id="anMusic-page-meting"></div></div></div>
    <script id="site-music-config" type="application/json">{"id":"939817038","server":"netease","volume":0.5,"source":"imgbed"}</script>`,
        {runScripts: 'outside-only', url: 'http://localhost/blog/music/'})
    const win = dom.window
    win.Math.random = random
    win.document.body.dataset.type = home ? 'index' : 'music'
    if (home) win.document.getElementById('anMusic-page').remove()
    win.matchMedia = () => ({
        matches: mobile, addEventListener() {
        }
    })
    win.fetch = fetcher
    win.AbortSignal = AbortSignal
    win.GLOBAL_CONFIG = {navMusic: true}
    const notifications = []
    const listeners = new Map()
    const register = win.document.addEventListener.bind(win.document)
    win.document.addEventListener = (name, handler, options) => {
        listeners.set(name, [...(listeners.get(name) || []), handler])
        return register(name, handler, options)
    }
    win.anzhiyu = {
        snackbarShow(text) {
            notifications.push(text)
        }
    }
    let now = 0
    win.Date.now = () => now
    let timerId = 0
    const timers = new Map()
    win.setTimeout = (callback, delay = 0) => {
        const id = ++timerId
        timers.set(id, {callback, at: now + delay})
        return id
    }
    win.clearTimeout = (id) => timers.delete(id)
    const advance = (milliseconds) => {
        const target = now + milliseconds
        for (let steps = 0; steps < 1000; steps += 1) {
            const next = [...timers.entries()].filter(([, timer]) => timer.at <= target)
                .sort((a, b) => a[1].at - b[1].at)[0]
            if (!next) {
                now = target;
                return
            }
            timers.delete(next[0])
            now = next[1].at
            next[1].callback()
        }
        throw new Error('unbounded timer loop')
    }
    if (realPlayer) {
        const playingMedia = new WeakSet()
        Object.defineProperty(win.HTMLMediaElement.prototype, 'paused', {
            configurable: true, get() {
                return !playingMedia.has(this)
            },
        })
        win.HTMLMediaElement.prototype.play = function () {
            playingMedia.add(this)
            this.dispatchEvent(new win.Event('play'))
            return Promise.resolve()
        }
        win.HTMLMediaElement.prototype.pause = function () {
            playingMedia.delete(this)
            this.dispatchEvent(new win.Event('pause'))
        }
        win.HTMLMediaElement.prototype.load = function () {
        }
        vm.runInContext(fs.readFileSync(path.join(__dirname,
            '../node_modules/anzhiyu-blog-static/js/APlayer.min.js'), 'utf8'), dom.getInternalVMContext())
    }

    class Meting extends win.HTMLElement {
        connectedCallback() {
            this.meta = {
                id: this.getAttribute('id'),
                server: this.getAttribute('server'),
                type: this.getAttribute('type')
            }
            this.config = {}
            this.api = 'https://example.test/api?server=:server&type=:type&id=:id&r=:r'
            this.ready = this._parse()
        }

        _loadPlayer(audio) {
            this.audio = audio
            const container = win.document.createElement('div')
            this.append(container)
            if (realPlayer) {
                this.aplayer = new win.APlayer({...this.config, audio, container})
                return
            }
            container.innerHTML = '<div class="aplayer-pic"><div class="aplayer-button"></div></div>'
            const handlers = new Map()
            const emit = (name) => (handlers.get(name) || []).forEach(handler => handler())
            const media = win.document.createElement('audio')
            const player = this.aplayer = {
                audio: media,
                container,
                paused: true,
                vendorErrorCalls: 0,
                switches: [],
                notices: [],
                loads: 0,
                autoplayCalls: 0,
                on: (name, handler) => handlers.set(name, [...(handlers.get(name) || []), handler]),
                notice: (text) => player.notices.push(text),
                list: {
                    index: 0, audios: audio,
                    switch(index) {
                        emit('listswitch') // APlayer emits before updating index and source.
                        this.index = index
                        media.src = audio[index].url
                        player.switches.push(index)
                    },
                    toggle() {
                    },
                },
                play() {
                    player.paused = false;
                    media.dispatchEvent(new win.Event('play'))
                },
                setUIPlaying() {
                    player.paused = false
                },
                setUIPaused() {
                    player.paused = true
                },
                pause() {
                    const wasPaused = player.paused
                    player.paused = true
                    if (!wasPaused) media.dispatchEvent(new win.Event('pause'))
                },
                toggle() {
                    player.paused ? player.play() : player.pause()
                },
                destroy() {
                    player.pause();
                    emit('destroy')
                },
            }
            media.src = audio[0].url
            media.play = () => {
                player.autoplayCalls += 1
                if (blockedAutoplay && player.autoplayCalls === 1)
                    return Promise.reject(new win.DOMException('gesture required', 'NotAllowedError'))
                player.play()
                return Promise.resolve()
            }
            media.load = () => {
                player.loads += 1
            }
            for (const event of ['error', 'play', 'playing', 'waiting', 'pause']) media.addEventListener(event, () => emit(event))
            // Model the vendor's unconditional skip, including when paused, so a
            // regression cannot pass by merely adding another player error listener.
            player.on('error', () => {
                player.vendorErrorCalls += 1
                win.setTimeout(() => player.list.switch((player.list.index + 1) % audio.length), 2000)
            })
            container.querySelector('.aplayer-pic').addEventListener('click', () => player.toggle())
        }
    }

    win.MetingJSElement = Meting
    win.customElements.define('meting-js', Meting)
    let legacyMenuCalls = 0
    win.document.getElementById('menu-music-toggle').addEventListener('click', () => {
        legacyMenuCalls += 1
    })
    vm.runInContext(source, dom.getInternalVMContext())
    win.anzhiyu.getCustomPlayList()
    return {
        dom,
        win,
        players: [...win.document.querySelectorAll('meting-js')],
        advance,
        notifications,
        listeners,
        legacyMenuCalls: () => legacyMenuCalls
    }
}

const playlist = (length = 8) => Array.from({length}, (_, index) => ({
    title: `歌曲 ${index + 1}`, author: '歌手', url: `https://example.test/file/blog/music/${index}.mp3`,
}))
const hostedBody = songs => ({code: 0, data: {source: 'imgbed', songs}})

test('hosted audio populates one shared player without making any community request', async () => {
    const calls = []
    const f = fixture(async (url, options) => {
        calls.push({url, options})
        return {
            ok: true, json: async () => ({
                code: 0, data: {
                    source: 'imgbed', songs: [
                        {
                            id: 'blog/music/翼.m4a', name: '翼をください', artist: '本站音频',
                            url: 'https://cloudflare-imgbed-6of.pages.dev/file/blog/music/%E7%BF%BC.m4a'
                        },
                        {
                            id: 'blog/music/lover.mp3', name: 'Lover', artist: '本站音频',
                            url: 'https://cloudflare-imgbed-6of.pages.dev/file/blog/music/lover.mp3'
                        },
                    ]
                }
            })
        }
    })
    try {
        await Promise.all(f.players.map(player => player.ready))
        assert.equal(f.players.length, 1, 'one player must serve every page')
        assert.equal(calls.length, 1)
        assert.equal(calls[0].url, '/api/music/hosted-playlist')
        assert.equal(calls[0].options.credentials, 'omit')
        assert.equal(calls[0].options.headers, undefined)
        for (const element of f.players) {
            assert.equal(element.audio.length, 2)
            assert.equal(element.audio[0].name, '翼をください')
            assert.match(element.audio[0].url, /pages\.dev\/file\/blog\/music\//)
            assert.equal(element.config.autoplay, false)
            assert.equal(element.config.preload, 'none')
            assert.equal(element.config.order, 'random')
            assert.equal(element.getAttribute('order'), 'random')
        }
    } finally {
        f.dom.window.close()
    }
})

test('reload updates the shared player once and preserves it on a failed refresh', async () => {
    const calls = []
    let songs = playlist(2), offline = false
    const result = fixture(async (url, options) => {
        calls.push({url, options})
        if (offline) throw new Error('offline')
        return {ok: true, json: async () => hostedBody(songs)}
    })
    try {
        await Promise.all(result.players.map(player => player.ready))
        songs = playlist(3)
        result.win.document.getElementById('anMusicRefreshBtn').click()
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(calls.length, 2)
        assert.ok(result.players.every(element => element.audio.length === 3))
        const old = result.players.map(element => element.aplayer)
        offline = true
        result.win.document.getElementById('anMusicRefreshBtn').click()
        await new Promise(resolve => setImmediate(resolve))
        assert.deepEqual(result.players.map(element => element.aplayer), old)
        assert.match(result.win.document.querySelector('.site-music-state').textContent, /暂时无法加载/)
        offline = false
        result.win.document.querySelector('.site-music-state button').click()
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(calls.length, 4)
        assert.equal(result.win.document.querySelector('.site-music-state').hidden, true)
        assert.ok(calls.every(call => call.options.credentials === 'omit' && !call.options.headers))
    } finally {
        result.dom.window.close()
    }
})
test('empty, malformed or wrong-source hosted lists never fall back to a community API', async () => {
    for (const body of [null, {code: 0, data: null}, hostedBody([]),
        {code: 0, data: {source: 'community', songs: playlist()}}, hostedBody(playlist(2001))]) {
        const calls = []
        const result = fixture(async url => {
            calls.push(url);
            return {ok: true, json: async () => body}
        })
        try {
            await Promise.all(result.players.map(player => player.ready))
            assert.ok(result.players.every(element => !element.aplayer))
            assert.deepEqual(calls, ['/api/music/hosted-playlist'])
        } finally {
            result.dom.window.close()
        }
    }
})

async function playbackFixture(songs = playlist(), options) {
    const result = fixture(async () => ({ok: true, json: async () => hostedBody(songs)}), options)
    await Promise.all(result.players.map(player => player.ready))
    result.element = result.players[0]
    result.player = result.element.aplayer
    result.status = result.win.document.querySelector('.site-music-state')
    result.fail = (player = result.player) => player.audio.dispatchEvent(new result.win.Event('error'))
    return result
}

test('music escapes HTML and rejects unsafe or credentialled audio URLs', async () => {
    const calls = []
    const {dom, win, players} = fixture(async (url, options) => {
        calls.push({url, options})
        return {
            ok: true, json: async () => hostedBody([
                {
                    title: '<script>bad</script>',
                    author: '歌手',
                    url: 'https://example.test/file/a.mp3',
                    pic: 'https://example.test/a.jpg'
                },
                {
                    name: '歌曲',
                    artist: '歌手',
                    url: 'https://example.test/file/b.mp3',
                    cover: 'https://example.test/b.jpg'
                },
                {name: 'unsafe', url: 'javascript:alert(1)'},
                {name: 'credentials', url: 'https://user:secret@example.test/file/a.mp3'},
                {name: 'query-token', url: 'https://example.test/file/a.mp3?token=secret'},
                {name: 'not-a-file', url: 'https://example.test/api/music'},
            ])
        }
    })
    try {
        await Promise.all(players.map(player => player.ready))
        assert.equal(players.length, 1)
        assert.equal(calls.length, 1)
        for (const call of calls) {
            assert.equal(call.url, '/api/music/hosted-playlist')
            assert.equal(call.options.credentials, 'omit')
            assert.ok(call.options.signal)
        }
        for (const player of players) {
            assert.equal(player.audio.length, 2)
            assert.equal(player.audio[0].name, '&lt;script&gt;bad&lt;/script&gt;')
            assert.equal(player.audio[0].title, player.audio[0].name)
            assert.equal(player.config.autoplay, false)
            assert.equal(player.config.preload, 'none')
        }
        assert.equal(win.GLOBAL_CONFIG.navMusic, false)
        assert.equal(win.document.querySelector('.site-music-state').hidden, true)
        assert.equal(win.document.querySelector('#anMusicBtnGetSong').getAttribute('role'), 'button')
        assert.equal(win.document.querySelector('a[href*="music.163.com"]').getAttribute('rel'), 'noopener noreferrer')
    } finally {
        dom.window.close()
    }
})

test('unavailable or empty music has visible retry and does not create a player', async () => {
    for (const fetcher of [
        async () => {
            throw new Error('offline')
        },
        async () => ({ok: false}),
        async () => ({ok: true, json: async () => []}),
        async () => ({ok: true, json: async () => ({error: 'private list'})}),
    ]) {
        const {dom, win, players} = fixture(fetcher)
        try {
            await Promise.all(players.map(player => player.ready))
            assert.ok(players.every(player => !player.aplayer))
            const status = win.document.querySelector('.site-music-state')
            assert.equal(status.hidden, false)
            assert.match(status.textContent, /暂时无法加载/)
            assert.equal(status.querySelector('button').hidden, false)
            assert.ok(players.every(player => !player.siteLoading))
        } finally {
            dom.window.close()
        }
    }
})

test('successful playing clears stale errors; an attempted play alone does not', async () => {
    const f = await playbackFixture()
    try {
        f.player.play()
        f.fail()
        assert.equal(f.status.hidden, false)
        f.player.play()
        assert.equal(f.status.hidden, false)
        f.player.audio.dispatchEvent(new f.win.Event('playing'))
        assert.equal(f.status.hidden, true)
        assert.equal(f.status.querySelector('p').textContent, '')
        assert.equal(f.player.notices.at(-1), '')
        f.advance(5000)
        assert.deepEqual(f.player.switches, [])
        f.fail()
        assert.match(f.status.textContent, /连续失败 1\/5/)
    } finally {
        f.dom.window.close()
    }
})

test('five consecutive audio failures stop recovery and suppress the vendor skip timer', async () => {
    const f = await playbackFixture()
    try {
        f.player.play()
        for (let count = 1; count <= 5; count += 1) {
            f.fail()
            assert.equal(f.player.vendorErrorCalls, 0)
            if (count < 5) f.advance(1500)
        }
        assert.equal(f.player.paused, true)
        assert.equal(f.player.list.index, 4)
        assert.match(f.status.textContent, /连续 5 首.*已暂停自动跳歌/)
        assert.equal(f.status.querySelector('button').hidden, false)
        assert.ok(f.status.querySelector('a[href*="music.163.com"]'))
        f.fail()
        f.advance(60000)
        assert.deepEqual(f.player.switches, [1, 2, 3, 4])
    } finally {
        f.dom.window.close()
    }
})

test('short playlists stop without wrapping around; duplicate URLs are not retried', async () => {
    for (const songs of [playlist(1), playlist(2), [playlist(1)[0], playlist(1)[0]]]) {
        const f = await playbackFixture(songs)
        try {
            f.player.play()
            f.fail()
            if (songs.length === 2 && songs[0].url !== songs[1].url) {
                f.advance(1500);
                f.fail()
            }
            assert.equal(f.player.paused, true)
            const switches = [...f.player.switches]
            f.advance(60000)
            assert.deepEqual(f.player.switches, switches)
        } finally {
            f.dom.window.close()
        }
    }
})

test('duplicate native errors count once and user pause cancels pending skips', async () => {
    const f = await playbackFixture()
    try {
        f.player.play()
        f.fail()
        f.fail()
        assert.match(f.status.textContent, /连续失败 1\/5/)
        f.player.pause()
        f.advance(10000)
        assert.deepEqual(f.player.switches, [])
        assert.equal(f.player.paused, true)
    } finally {
        f.dom.window.close()
    }
})

test('manual song selection resets the budget and cancels an old pending skip', async () => {
    const f = await playbackFixture()
    try {
        f.player.play()
        for (let count = 0; count < 4; count += 1) {
            f.fail();
            f.advance(1500)
        }
        f.fail()
        assert.equal(f.player.paused, true)
        f.player.list.switch(6)
        f.player.play()
        f.fail()
        assert.match(f.status.textContent, /连续失败 1\/5/)
        f.player.list.switch(7)
        f.advance(10000)
        assert.equal(f.player.list.index, 7)
    } finally {
        f.dom.window.close()
    }
})

test('destroy, reload and page hide cancel playback recovery', async () => {
    for (const action of ['destroy', 'reload', 'pagehide']) {
        const f = await playbackFixture()
        try {
            const old = f.player
            old.play()
            f.fail()
            if (action === 'destroy') old.destroy()
            if (action === 'reload') await f.element._parse()
            if (action === 'pagehide') f.win.dispatchEvent(new f.win.Event('pagehide'))
            if (action !== 'pagehide') f.fail(old) // A late native error from the disposed media element.
            f.advance(10000)
            assert.deepEqual(old.switches, [])
            assert.equal(old.paused, true)
            if (action === 'reload') {
                f.element.aplayer.play()
                f.fail(f.element.aplayer)
                assert.match(f.status.textContent, /连续失败 1\/5/)
            }
        } finally {
            f.dom.window.close()
        }
    }
})

test('home music has the same bounded recovery and updates its playing state', async () => {
    const f = await playbackFixture()
    try {
        f.win.document.dispatchEvent(new f.win.Event('pjax:send'))
        f.win.document.getElementById('body-wrap').replaceChildren()
        f.win.document.body.dataset.type = 'anzhiyu'
        f.win.document.dispatchEvent(new f.win.Event('pjax:complete'))
        const nav = f.players[0].aplayer
        nav.play()
        for (let count = 0; count < 5; count += 1) {
            f.fail(nav)
            if (count < 4) f.advance(1500)
        }
        assert.equal(nav.paused, true)
        assert.equal(nav.vendorErrorCalls, 0)
        assert.match(f.win.document.querySelector('#nav-music-hoverTips').textContent, /已暂停自动跳歌/)
        assert.match(f.notifications.at(-1), /歌曲.*重新加载/)
        nav.list.switch(6)
        nav.play()
        nav.audio.dispatchEvent(new f.win.Event('playing'))
        assert.equal(f.win.document.querySelector('#nav-music-hoverTips').textContent, '暂停音乐')
        assert.equal(f.win.document.querySelector('#nav-music').classList.contains('playing'), true)
    } finally {
        f.dom.window.close()
    }
})

test('explicit retry on a failed media element reloads it before attempting playback', async () => {
    const f = await playbackFixture(playlist(1))
    try {
        f.player.play()
        f.fail()
        Object.defineProperty(f.player.audio, 'error', {value: {code: 4}, configurable: true})
        f.player.container.querySelector('.aplayer-pic').click()
        assert.equal(f.player.loads, 1)
        assert.equal(f.player.paused, false)
        f.fail()
        assert.match(f.status.textContent, /连续 1 首/)
    } finally {
        f.dom.window.close()
    }
})

test('real bundled APlayer is intercepted before its automatic skip and clears errors on playing', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true})
    try {
        f.player.play()
        for (let count = 0; count < 5; count += 1) {
            f.fail()
            if (count < 4) f.advance(1500)
        }
        assert.equal(f.player.paused, true)
        assert.equal(f.player.list.index, 4)
        assert.match(f.status.textContent, /连续 5 首/)
        assert.doesNotMatch(f.player.container.textContent, /An audio error/)
        f.advance(60000)
        assert.equal(f.player.list.index, 4)
        f.player.list.switch(6)
        f.player.play()
        f.player.audio.dispatchEvent(new f.win.Event('playing'))
        assert.equal(f.status.hidden, true)
        assert.equal(f.status.querySelector('p').textContent, '')
    } finally {
        for (const element of f.players) element.aplayer?.destroy()
        f.dom.window.close()
    }
})

test('new visits select across the entire playlist rather than fixing the initial song at zero', async () => {
    for (const [size, value, index] of [[8, 0, 0], [8, 0.49, 3], [8, 0.999999, 7], [1, 0.999999, 0]]) {
        const f = await playbackFixture(playlist(size), {home: true, random: () => value})
        try {
            assert.equal(f.player.list.index, index)
            assert.equal(f.player.audio.src, playlist(size)[index].url)
            assert.equal(f.element.config.order, 'random')
        } finally {
            f.dom.window.close()
        }
    }
})

test('home play starts the selected random track; pause, resume and PJAX never draw another one', async () => {
    let draws = 0
    const f = await playbackFixture(playlist(), {
        home: true, blockedAutoplay: true, random: () => {
            draws += 1;
            return 0.625
        },
    })
    try {
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(f.player.list.index, 5)
        assert.equal(f.player.paused, true, 'blocked autoplay must still require a gesture')
        const disc = f.win.document.getElementById('site-music-disc')
        disc.click()
        assert.equal(f.player.paused, false)
        assert.equal(f.player.audio.src, playlist()[5].url)
        f.player.audio.currentTime = 37
        disc.click()
        assert.equal(f.player.paused, true)
        disc.click()
        assert.equal(f.player.paused, false)
        f.win.document.dispatchEvent(new f.win.Event('pjax:send'))
        f.win.document.getElementById('body-wrap').innerHTML = '<div id="anMusic-page"><div id="anMusic-page-meting"></div></div>'
        f.win.document.dispatchEvent(new f.win.Event('pjax:complete'))
        assert.equal(f.element.aplayer, f.player)
        assert.equal(f.player.list.index, 5)
        assert.equal(f.player.audio.currentTime, 37)
        f.player.list.switch(2)
        f.player.pause()
        disc.click()
        assert.equal(f.player.list.index, 2, 'manual selection must be kept when resuming')
        assert.equal(draws, 1, 'only the initial song is randomly selected by the site adapter')
    } finally {
        f.dom.window.close()
    }
})

test('real APlayer starts at a random track with random subsequent playback', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true, home: true, random: () => 0.625})
    try {
        assert.equal(f.player.list.index, 5)
        assert.equal(f.player.audio.src, playlist()[5].url)
        assert.equal(f.player.options.order, 'random')
        assert.equal(f.player.paused, false)
        f.player.audio.dispatchEvent(new f.win.Event('ended'))
        assert.notEqual(f.player.list.index, 5, 'random order must advance on track end')
        assert.equal(f.player.paused, false)
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('first entry attempts playback, blocked autoplay waits for a real gesture and never resumes a user pause', async () => {
    const f = await playbackFixture(playlist(), {blockedAutoplay: true})
    try {
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(f.player.autoplayCalls, 1)
        assert.equal(f.player.paused, true)
        assert.match(f.win.document.getElementById('nav-music-hoverTips').textContent, /点击页面/)
        f.win.document.body.click() // A synthetic click cannot bypass browser policy.
        await Promise.resolve()
        assert.equal(f.player.autoplayCalls, 1)
        const gesture = {type: 'click', isTrusted: true, target: f.win.document.body}
        for (const handler of f.listeners.get('click')) handler(gesture)
        assert.equal(f.player.autoplayCalls, 2)
        assert.equal(f.player.paused, false)
        f.player.pause()
        f.win.document.dispatchEvent(new f.win.Event('pjax:send'))
        f.win.document.getElementById('body-wrap').replaceChildren()
        f.win.document.dispatchEvent(new f.win.Event('pjax:complete'))
        for (const handler of f.listeners.get('click')) handler(gesture)
        assert.equal(f.player.autoplayCalls, 2)
        assert.equal(f.player.paused, true)
    } finally {
        f.dom.window.close()
    }
})

test('real PJAX navigation and history keep the same real APlayer, media, song and position', async () => {
    let fetches = 0
    const f = fixture(async () => {
        fetches += 1;
        return {ok: true, json: async () => hostedBody(playlist())}
    }, {realPlayer: true})
    try {
        await Promise.all(f.players.map(p => p.ready))
        f.win.document.title = '音乐'
        const player = f.players[0].aplayer, media = player.audio
        player.list.switch(3)
        player.play()
        media.currentTime = 42
        const music = f.win.document.getElementById('body-wrap').innerHTML
        const requests = []
        f.win.scrollTo = () => {
        }

        class XHR {
            open(method, url) {
                this.url = new URL(url, f.win.location.href).href
            }

            setRequestHeader() {
            }

            getResponseHeader() {
                return null
            }

            abort() {
            }

            send() {
                requests.push(this.url)
                queueMicrotask(() => {
                    this.status = 200;
                    this.readyState = 4;
                    this.responseURL = this.url
                    const content = this.url.includes('/music/') ? music : '<a href="/blog/music/">音乐</a><article>正文</article>'
                    this.responseText = `<html><head><title>新页面</title></head><body><div id="body-wrap">${content}</div></body></html>`
                    this.onreadystatechange()
                })
            }
        }

        f.win.XMLHttpRequest = XHR
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../node_modules/pjax/pjax.min.js'), 'utf8'), f.dom.getInternalVMContext())
        const pjax = new f.win.Pjax({
            selectors: ['title', '#body-wrap'],
            elements: 'a[href]',
            cacheBust: false,
            analytics: false
        })
        const assertPersistent = () => {
            assert.equal(f.players[0].aplayer, player)
            assert.equal(player.audio, media)
            assert.equal(player.list.index, 3)
            assert.equal(media.currentTime, 42)
            assert.equal(f.win.document.querySelectorAll('meting-js').length, 1)
            assert.equal(fetches, 1)
        }
        pjax.loadUrl('/blog/article/19/')
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(f.win.location.pathname, '/blog/article/19/')
        assert.equal(player.container.parentNode, f.players[0])
        assert.equal(player.paused, false)
        assertPersistent()
        f.win.document.querySelector('#body-wrap a').click()
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(f.win.location.pathname, '/blog/music/')
        assert.equal(player.container.parentNode.id, 'anMusic-page-meting')
        assertPersistent()
        player.pause()
        f.win.dispatchEvent(new f.win.PopStateEvent('popstate', {
            state: {
                url: 'http://localhost/blog/article/19/',
                uid: 1,
                scrollPos: [0, 0]
            }
        }))
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(player.paused, true)
        assertPersistent()
        assert.equal(requests.length, 3)
        player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('cover and artist are kept while an unmatched song has no storage-source label', async () => {
    const f = await playbackFixture([
        {
            name: 'Blank Space',
            artist: 'Taylor Swift',
            cover: 'https://p1.music.126.net/album.jpg',
            url: 'https://example.test/file/a.m4a'
        },
        {name: 'Unknown', artist: '', cover: '', url: 'https://example.test/file/b.mp3'},
    ], {realPlayer: true})
    try {
        assert.equal(f.player.list.audios[0].artist, 'Taylor Swift')
        assert.equal(f.player.list.audios[0].cover, 'https://p1.music.126.net/album.jpg')
        assert.equal(f.player.list.audios[1].artist, '')
        assert.doesNotMatch(f.player.container.textContent, /本站音频|Audio artist/)
        assert.match(f.win.document.getElementById('an_music_bg').style.backgroundImage, /album.jpg/)
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('right-menu controls the shared player instead of its captured legacy handler', async () => {
    const f = await playbackFixture()
    try {
        assert.equal(f.player.paused, false, 'accepted entry playback should start')
        const menu = f.win.document.getElementById('menu-music-toggle')
        assert.match(menu.textContent, /暂停音乐/)
        menu.click()
        assert.equal(f.player.paused, true)
        assert.match(menu.textContent, /播放音乐/)
        menu.click()
        assert.equal(f.player.paused, false)
        assert.equal(f.legacyMenuCalls(), 0)
    } finally {
        f.dom.window.close()
    }
})

test('real play button toggles once even with the original theme state-only callback', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true})
    try {
        const button = f.player.container.querySelector('.aplayer-button')
        // The theme binds this to the child button, while APlayer also handles
        // its parent's click. false means refresh UI, not toggle playback again.
        button.addEventListener('click', () => f.win.anzhiyu.musicToggle(false))
        f.player.pause()
        button.click()
        assert.equal(f.player.paused, false)
        button.click()
        assert.equal(f.player.paused, true)
        f.win.anzhiyu.musicToggle(false)
        assert.equal(f.player.paused, true, 'state-only notifications cannot resume music')
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('mini disc exposes only play/pause and leaves seek controls inert outside music', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true})
    try {
        const player = f.player, media = player.audio
        player.list.switch(2)
        media.currentTime = 42
        f.win.document.dispatchEvent(new f.win.Event('pjax:send'))
        f.win.document.getElementById('body-wrap').replaceChildren()
        f.win.document.dispatchEvent(new f.win.Event('pjax:complete'))
        const disc = f.win.document.getElementById('site-music-disc')
        assert.ok(disc, 'a dedicated mini button replaces the expandable APlayer strip')
        assert.equal(disc.tagName, 'BUTTON')
        assert.equal(disc.getAttribute('aria-pressed'), 'true')
        assert.equal(disc.querySelectorAll('.aplayer-bar, input, .aplayer-controller').length, 0)
        assert.equal(player.container.hasAttribute('inert'), true)
        disc.click()
        assert.equal(player.paused, true)
        assert.match(disc.getAttribute('aria-label'), /播放音乐.*歌曲 3/)
        disc.querySelector('i').click()
        assert.equal(player.paused, false)
        disc.click()
        assert.equal(player.paused, true)
        assert.equal(media.currentTime, 42)
        f.win.document.getElementById('body-wrap').innerHTML = '<div id="anMusic-page"><div id="anMusic-page-meting"></div></div>'
        f.win.document.dispatchEvent(new f.win.Event('pjax:complete'))
        assert.equal(player.container.hasAttribute('inert'), false)
        assert.equal(player.container.parentNode.id, 'anMusic-page-meting')
        assert.equal(player.paused, true, 'returning to music cannot undo the disc pause')
        assert.equal(player.audio, media)
        assert.equal(f.win.document.querySelectorAll('#site-music-disc').length, 1)
        player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('mini disc hides full controls even with the theme console open, in both color modes', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true})
    try {
        const style = f.win.document.createElement('style')
        style.textContent = fs.readFileSync(path.join(__dirname, '../source/css/site.css'), 'utf8')
        f.win.document.head.append(style)
        const console = f.win.document.createElement('div')
        console.id = 'console'
        console.className = 'show'
        f.win.document.body.append(console)
        f.win.document.dispatchEvent(new f.win.Event('pjax:send'))
        f.win.document.getElementById('body-wrap').replaceChildren()
        f.win.document.body.dataset.type = 'anzhiyu'
        f.win.document.dispatchEvent(new f.win.Event('pjax:complete'))
        for (const theme of ['light', 'dark']) {
            f.win.document.documentElement.dataset.theme = theme
            const disc = f.win.document.getElementById('site-music-disc')
            assert.equal(f.win.getComputedStyle(f.player.container).display, 'none')
            assert.equal(f.win.getComputedStyle(disc).borderRadius, '50%')
            assert.equal(f.win.getComputedStyle(f.win.document.getElementById('nav-music')).width, '60px')
            disc.click()
            assert.equal(f.player.paused, true)
            disc.click()
            assert.equal(f.player.paused, false)
        }
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('a second click cancels pending playback; late rejection does not re-arm entry autoplay', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true})
    try {
        f.player.pause()
        let rejectPlay
        f.player.audio.play = () => new Promise((resolve, reject) => {
            rejectPlay = reject
        })
        const disc = f.win.document.getElementById('site-music-disc')
        disc.click()
        assert.equal(f.player.paused, false, 'pending playback can be cancelled')
        disc.click()
        assert.equal(f.player.paused, true)
        rejectPlay(new f.win.DOMException('gesture required', 'NotAllowedError'))
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(f.player.paused, true)
        assert.equal(disc.getAttribute('aria-pressed'), 'false')
        const gesture = {type: 'click', isTrusted: true, target: f.win.document.body}
        for (const handler of f.listeners.get('click')) handler(gesture)
        assert.equal(f.player.paused, true)
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('music picture supports keyboard pause and mini disc resumes a failed track only once', async () => {
    const f = await playbackFixture(playlist(1), {realPlayer: true})
    try {
        const picture = f.player.container.querySelector('.aplayer-pic')
        assert.equal(picture.getAttribute('role'), 'button')
        assert.equal(picture.getAttribute('tabindex'), '0')
        picture.dispatchEvent(new f.win.KeyboardEvent('keydown', {key: ' ', bubbles: true, cancelable: true}))
        assert.equal(f.player.paused, true)
        picture.dispatchEvent(new f.win.KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}))
        assert.equal(f.player.paused, false)
        f.fail()
        assert.equal(f.player.paused, true)
        Object.defineProperty(f.player.audio, 'error', {value: {code: 4}, configurable: true})
        let loads = 0
        f.player.audio.load = () => {
            loads += 1
        }
        f.win.document.getElementById('site-music-disc').click()
        assert.equal(loads, 1)
        assert.equal(f.player.paused, false)
        f.win.document.getElementById('site-music-disc').click()
        assert.equal(f.player.paused, true)
        assert.equal(loads, 1)
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('mobile music drawer starts folded, opens from the real menu and closes via backdrop or Escape', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true, mobile: true})
    try {
        const list = f.player.container.querySelector('.aplayer-list')
        const menu = f.player.container.querySelector('.aplayer-icon-menu')
        const mask = f.win.document.getElementById('menu-mask')
        assert.equal(list.classList.contains('aplayer-list-hide'), true)
        menu.click()
        assert.equal(list.classList.contains('aplayer-list-hide'), false)
        assert.equal(mask.classList.contains('site-music-drawer-open'), true)
        mask.click()
        assert.equal(list.classList.contains('aplayer-list-hide'), true)
        assert.equal(mask.classList.contains('site-music-drawer-open'), false)
        menu.click()
        f.win.document.dispatchEvent(new f.win.KeyboardEvent('keydown', {key: 'Escape', bubbles: true}))
        assert.equal(list.classList.contains('aplayer-list-hide'), true)
        menu.click()
        f.win.document.dispatchEvent(new f.win.Event('pjax:send'))
        assert.equal(mask.classList.contains('site-music-drawer-open'), false)
        assert.equal(f.player.paused, false, 'closing the drawer cannot stop playback')
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('console music changes icon, caption and pressed state on one click and external playback events', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true, home: true})
    try {
        await new Promise(resolve => setImmediate(resolve))
        const button = f.win.document.getElementById('consoleMusic')
        const icon = button.querySelector('i'), caption = button.querySelector('[role="status"]')
        let legacyCalls = 0
        button.addEventListener('click', () => {
            legacyCalls += 1;
            f.win.anzhiyu.musicToggle()
        })
        assert.equal(button.dataset.state, 'playing')
        assert.equal(caption.textContent, '播放中')
        assert.equal(button.getAttribute('aria-pressed'), 'true')
        assert.match(icon.className, /icon-pause/)
        icon.click()
        assert.equal(f.player.paused, true)
        assert.equal(button.dataset.state, 'paused')
        assert.equal(caption.textContent, '已暂停')
        assert.equal(button.getAttribute('aria-pressed'), 'false')
        assert.match(icon.className, /icon-play/)
        button.click()
        assert.equal(button.dataset.state, 'loading', 'immediate pending feedback without disabling cancel')
        assert.equal(caption.textContent, '加载中')
        assert.equal(button.getAttribute('aria-busy'), 'true')
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(button.dataset.state, 'playing')
        f.player.audio.dispatchEvent(new f.win.Event('waiting'))
        assert.equal(button.dataset.state, 'loading')
        f.player.audio.dispatchEvent(new f.win.Event('playing'))
        assert.equal(button.dataset.state, 'playing')
        f.win.document.getElementById('site-music-disc').click()
        assert.equal(button.dataset.state, 'paused', 'disc pause is reflected in the console too')
        assert.equal(legacyCalls, 0, 'do not also invoke the captured upstream toggle')
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('console keyboard toggle works once and preserves state across themes and PJAX', async () => {
    const f = await playbackFixture(playlist(), {realPlayer: true, home: true})
    try {
        await new Promise(resolve => setImmediate(resolve))
        const button = f.win.document.getElementById('consoleMusic')
        assert.equal(button.getAttribute('role'), 'button')
        assert.equal(button.tabIndex, 0)
        for (const theme of ['light', 'dark']) {
            f.win.document.documentElement.dataset.theme = theme
            button.dispatchEvent(new f.win.KeyboardEvent('keydown', {key: ' ', bubbles: true, cancelable: true}))
            assert.equal(f.player.paused, true)
            assert.equal(button.dataset.state, 'paused')
            button.dispatchEvent(new f.win.KeyboardEvent('keydown', {key: 'Enter', repeat: true, bubbles: true}))
            assert.equal(f.player.paused, true, 'held key must not toggle repeatedly')
            button.dispatchEvent(new f.win.KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}))
            await new Promise(resolve => setImmediate(resolve))
            assert.equal(button.dataset.state, 'playing')
        }
        f.player.audio.currentTime = 37
        f.win.document.dispatchEvent(new f.win.Event('pjax:send'))
        f.win.document.getElementById('body-wrap').replaceChildren()
        f.win.document.dispatchEvent(new f.win.Event('pjax:complete'))
        assert.equal(button.dataset.state, 'playing')
        assert.equal(f.player.audio.currentTime, 37)
        assert.equal(button.querySelectorAll('.site-console-music-status').length, 1)
        f.player.destroy()
    } finally {
        f.dom.window.close()
    }
})

test('console pending play can be cancelled and late promises cannot change its paused feedback', async () => {
    for (const rejected of [false, true]) {
        const f = await playbackFixture(playlist(), {realPlayer: true})
        try {
            f.player.pause()
            let finish
            f.player.audio.play = () => new Promise((resolve, reject) => {
                finish = () => rejected ? reject(new f.win.DOMException('blocked', 'NotAllowedError')) : resolve()
            })
            const button = f.win.document.getElementById('consoleMusic')
            button.click()
            assert.equal(button.dataset.state, 'loading')
            button.click()
            assert.equal(button.dataset.state, 'paused')
            finish()
            await new Promise(resolve => setImmediate(resolve))
            assert.equal(button.dataset.state, 'paused')
            assert.equal(button.getAttribute('aria-busy'), 'false')
            assert.equal(f.player.paused, true)
            f.player.destroy()
        } finally {
            f.dom.window.close()
        }
    }
})

test('console does not show playing when entry autoplay is blocked or media fails', async () => {
    const f = await playbackFixture(playlist(1), {blockedAutoplay: true})
    try {
        await new Promise(resolve => setImmediate(resolve))
        const button = f.win.document.getElementById('consoleMusic')
        assert.equal(button.dataset.state, 'paused')
        assert.equal(button.classList.contains('on'), false)
        button.click()
        await new Promise(resolve => setImmediate(resolve))
        assert.equal(button.dataset.state, 'playing')
        f.fail()
        assert.equal(button.dataset.state, 'error')
        assert.equal(button.querySelector('[role="status"]').textContent, '暂不可用')
        assert.equal(button.getAttribute('aria-pressed'), 'false')
        assert.match(button.querySelector('i').className, /icon-play/)
        button.click()
        assert.equal(button.dataset.state, 'loading')
        f.player.audio.dispatchEvent(new f.win.Event('playing'))
        assert.equal(button.dataset.state, 'playing')
    } finally {
        f.dom.window.close()
    }
})
