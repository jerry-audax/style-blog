// Keep the official music page, Meting and APlayer components. Use only the
// site's hosted audio catalog, adapt /blog/ URLs and bound playback failures.
(() => {
    if (window.siteMusicInitialized) return
    const configNode = document.getElementById('site-music-config')
    if (!configNode || !window.MetingJSElement || typeof anzhiyu === 'undefined') return
    const config = JSON.parse(configNode.textContent)
    if (!/^[1-9]\d*$/.test(config.id) || config.server !== 'netease') return
    window.siteMusicInitialized = true
    let page = document.getElementById('anMusic-page')
    const sourceLink = `https://music.163.com/#/playlist?id=${config.id}`
    const escape = (value) => String(value || '').replace(/[&<>"']/g, (char) =>
        ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'})[char],
    )
    const safeUrl = (value) => {
        try {
            const url = new URL(value)
            return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
                ? url.href : ''
        } catch {
            return ''
        }
    }
    const status = document.createElement('div')
    const safeAudioUrl = value => {
        const safe = safeUrl(value)
        if (!safe) return ''
        const url = new URL(safe)
        return url.pathname.startsWith('/file/') && !url.search && !url.hash ? safe : ''
    }
    status.className = 'site-music-state'
    status.setAttribute('role', 'status')
    status.setAttribute('aria-live', 'polite')
    const message = document.createElement('p')
    const retry = document.createElement('button')
    retry.type = 'button'
    retry.textContent = '重新加载'
    retry.hidden = true
    const link = document.createElement('a')
    link.href = sourceLink
    link.target = '_blank'
    link.rel = 'noopener noreferrer'
    link.textContent = '在网易云查看原歌单'
    status.append(message, retry, link)
    if (page) page.append(status)
    let playerElement
    const playbackPreparations = new WeakMap()
    const playbackAttempts = new WeakMap()
    let playbackState = 'loading'
    const consoleMusic = document.getElementById('consoleMusic')
    const consoleStatus = document.createElement('span')
    consoleStatus.className = 'site-console-music-status'
    consoleStatus.setAttribute('role', 'status')
    consoleStatus.setAttribute('aria-live', 'polite')
    if (consoleMusic) {
        consoleMusic.append(consoleStatus)
        consoleMusic.setAttribute('role', 'button')
        consoleMusic.tabIndex = 0
        consoleMusic.querySelector('.music-switch')?.setAttribute('aria-hidden', 'true')
        // Own one activation, rather than letting the theme's inline handler and
        // keyboard handling toggle the same player twice.
        consoleMusic.addEventListener('click', event => {
            event.preventDefault()
            event.stopImmediatePropagation()
            togglePlayback()
        }, true)
        consoleMusic.addEventListener('keydown', event => {
            if (!['Enter', ' '].includes(event.key) || event.repeat) return
            event.preventDefault()
            event.stopImmediatePropagation()
            togglePlayback()
        })
    }
    // A dedicated presentation button, not a second player. The real APlayer
    // remains alive in the persistent host and exposes its controls only on music.
    const disc = document.createElement('button')
    disc.id = 'site-music-disc'
    disc.type = 'button'
    disc.accessKey = 'm'
    disc.disabled = true
    disc.setAttribute('aria-label', '正在加载音乐')
    disc.setAttribute('aria-pressed', 'false')
    disc.innerHTML = '<span class="site-music-disc-art" aria-hidden="true"></span><span class="site-music-disc-control" aria-hidden="true"><i class="anzhiyufont anzhiyu-icon-play"></i></span>'
    document.getElementById('nav-music')?.append(disc)
    const oldTips = document.getElementById('nav-music-hoverTips')
    oldTips?.removeAttribute('accesskey')
    oldTips?.setAttribute('tabindex', '-1')
    oldTips?.setAttribute('aria-hidden', 'true')
    disc.addEventListener('click', event => {
        event.stopPropagation()
        togglePlayback()
    })
    let autoplayAttempted = false
    let awaitingGesture = false
    let automaticIntent = true
    let gestureAttempted = false
    syncConsoleMusic()
    const mobileMusic = window.matchMedia?.('(max-width: 768px)')
    const setStatus = (text, failed = false) => {
        message.textContent = text
        retry.hidden = !failed
        status.hidden = false
    }
    const notify = (text) => anzhiyu.snackbarShow(text)
    const MAX_PLAYBACK_FAILURES = 5
    const SKIP_DELAY_MS = 1500
    let playlistRequest
    const hostedPlaylist = () => {
        if (!playlistRequest) playlistRequest = fetch('/api/music/hosted-playlist', {
            signal: AbortSignal.timeout(12000), credentials: 'omit', cache: 'no-store',
        }).then(async response => {
            if (!response.ok) throw new Error('hosted playlist unavailable')
            const body = await response.json(), data = body?.data
            if (body?.code !== 0 || data?.source !== 'imgbed' || !Array.isArray(data.songs) || data.songs.length > 2000)
                throw new Error('invalid hosted playlist')
            return data.songs
        })
        return playlistRequest
    }
    const originalLoad = MetingJSElement.prototype._loadPlayer
    // Bound the site's read-only catalog request. Never use Meting's public API
    // as a fallback when our hosted playlist is empty or unavailable.
    MetingJSElement.prototype._parse = async function () {
        if (this.siteLoading) return
        this.siteLoading = true
        if (page) setStatus('正在加载歌单…')
        try {
            // The existing theme element only hosts APlayer; its Meting API is never called.
            const data = await hostedPlaylist()
            const audio = data.filter((song) => song && safeAudioUrl(song.url)).map((song) => ({
                name: escape(song.name || song.title || '未命名歌曲'),
                // APlayer substitutes "Audio artist" for a falsy artist on creation.
                // A blank sentinel keeps its rendered list empty; restore the data below.
                artist: escape(song.artist || song.author || '\u00a0'),
                title: escape(song.name || song.title || '未命名歌曲'),
                author: escape(song.artist || song.author || ''),
                url: safeAudioUrl(song.url), cover: safeUrl(song.cover || song.pic),
                pic: safeUrl(song.cover || song.pic),
                lrc: safeAudioUrl(song.lyrics),
            }))
            if (!audio.length) throw new Error('empty playlist')
            if (this.aplayer) {
                this.aplayer.destroy();
                this.replaceChildren()
            }
            // Attempt autoplay ourselves so a browser rejection has an explicit fallback.
            Object.assign(this.config, {autoplay: false, preload: 'none', lrcType: 3, order: 'random'})
            originalLoad.call(this, audio)
            for (const song of this.aplayer.list.audios) if (song.artist === '\u00a0') song.artist = ''
            // Random order only controls subsequent tracks in APlayer: its initial
            // index is still zero. Choose once before any playback attempt, never on
            // pause/resume or PJAX mounts so the shared media and position stay intact.
            if (this.aplayer.list.audios.length > 1) {
                const startIndex = Math.floor(Math.random() * this.aplayer.list.audios.length)
                if (startIndex !== this.aplayer.list.index) this.aplayer.list.switch(startIndex)
            }
            bindPlayer(this)
        } catch {
            if (!this.aplayer) {
                playbackState = 'error';
                syncConsoleMusic()
            }
            if (page)
                setStatus('歌单暂时无法加载或尚未配置音频，请重新加载。', true)
            else {
                const tips = document.getElementById('nav-music-hoverTips')
                if (tips) tips.textContent = '歌单暂时不可用，点击进入音乐页'
                disc.title = '音乐暂不可用，请进入音乐页重试'
                disc.setAttribute('aria-label', disc.title)
            }
        } finally {
            this.siteLoading = false
        }
    }
    const bindControl = (id, label, action) => {
        const node = document.getElementById(id)
        if (!node) return
        node.title = label
        node.setAttribute('aria-label', label)
        node.setAttribute('role', 'button')
        node.tabIndex = 0
        node.addEventListener('click', action)
        node.addEventListener('keydown', (event) => {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                action()
            }
        })
    }
    const reloadPlaylist = () => {
        playlistRequest = null
        playerElement?._parse()
    }

    function bindPlayer(element) {
        const player = element.aplayer
        if (!player) return
        player.sitePersistent = true
        bindPlaybackRecovery(element, player)
        player.on('listswitch', () => queueMicrotask(changeCover))
        // APlayer emits these before it changes the list class.
        player.on('listshow', () => syncMusicDrawer(true))
        player.on('listhide', () => syncMusicDrawer(false))
        player.on('play', () => musicState(true, 'loading'))
        player.on('waiting', () => {
            if (!player.paused) musicState(true, 'loading')
        })
        player.on('pause', () => {
            musicState(false, 'paused');
            automaticIntent = false;
            awaitingGesture = false
        })
        message.textContent = ''
        retry.hidden = true
        status.hidden = true
        mountPage()
        if (!autoplayAttempted && automaticIntent) {
            autoplayAttempted = true
            attemptAutoplay(player)
        }
    }

    function changeCover() {
        if (!playerElement?.isConnected) return
        const player = playerElement?.aplayer
        const cover = player?.list.audios[player.list.index]?.cover
        const background = document.getElementById('an_music_bg')
        if (background) background.style.backgroundImage = page && cover ? `url(${JSON.stringify(cover)})` : ''
        disc.querySelector('.site-music-disc-art').style.backgroundImage = cover ? `url(${JSON.stringify(cover)})` : ''
        musicState(player ? !player.paused : false)
    }

    function mountPage() {
        page = document.getElementById('anMusic-page')
        const player = playerElement?.aplayer
        if (page) {
            page.append(status)
            if (player) {
                player.container.removeAttribute('inert')
                const mount = document.getElementById('anMusic-page-meting')
                if (mobileMusic?.matches && player.container.parentNode !== mount) player.list.hide()
                mount.append(player.container)
                if (!message.textContent) status.hidden = true
            }
            if (!page.dataset.siteControls) {
                page.dataset.siteControls = 'true'
                bindControl('anMusicBtnGetSong', '随机播放歌单中的歌曲', () => {
                    const current = playerElement?.aplayer
                    if (current) current.list.switch(Math.floor(Math.random() * current.list.audios.length))
                })
                bindControl('anMusicRefreshBtn', '重新加载歌单', reloadPlaylist)
                bindControl('anMusicSwitching', '展开或收起歌单', () => playerElement?.aplayer?.list.toggle())
                document.getElementById('menu-mask')?.addEventListener('click', event => {
                    if (!mobileMusic?.matches || !event.currentTarget.classList.contains('site-music-drawer-open')) return
                    event.stopImmediatePropagation()
                    playerElement?.aplayer?.list.hide()
                }, true)
            }
        } else if (player) {
            // CSS hides the full control surface here; inert also prevents keyboard
            // access to the hidden seek bar, volume, song list and other controls.
            player.container.setAttribute('inert', '')
            if (player.container.parentNode !== playerElement) playerElement.append(player.container)
        }
        changeCover()
        syncMusicDrawer()
        musicState(player ? !player.paused : false)
    }

    function syncMusicDrawer(show) {
        const list = playerElement?.aplayer?.container.querySelector('.aplayer-list')
        const open = typeof show === 'boolean' ? show : list && !list.classList.contains('aplayer-list-hide')
        document.getElementById('menu-mask')?.classList.toggle('site-music-drawer-open',
            Boolean(page && mobileMusic?.matches && list && open))
    }

    mobileMusic?.addEventListener('change', () => {
        const player = playerElement?.aplayer
        if (page && player) mobileMusic.matches ? player.list.hide() : player.list.show()
        syncMusicDrawer()
    })
    document.addEventListener('keydown', event => {
        if (event.key === 'Escape' && page && mobileMusic?.matches) playerElement?.aplayer?.list.hide()
    })

    function attemptAutoplay(player) {
        if (!automaticIntent || !player.paused) return
        requestPlayback(player, true)
    }

    function requestPlayback(player, allowGesture = false) {
        awaitingGesture = false
        const attempt = (playbackAttempts.get(player) || 0) + 1
        playbackAttempts.set(player, attempt)
        player.setUIPlaying?.()
        musicState(true, 'loading')
        try {
            Promise.resolve(player.audio.play()).then(() => {
                if (playerElement?.aplayer !== player || !playerElement.isConnected || player.paused || playbackAttempts.get(player) !== attempt) return
                musicState(true, 'playing')
            }).catch(error => {
                if (playerElement?.aplayer !== player || !playerElement.isConnected || player.paused || playbackAttempts.get(player) !== attempt) return
                player.setUIPaused?.()
                musicState(false, error?.name === 'NotAllowedError' ? 'paused' : 'error')
                if (error?.name !== 'NotAllowedError') return // Native errors retain bounded recovery.
                if (allowGesture && automaticIntent && !gestureAttempted) {
                    awaitingGesture = true
                    const tips = document.getElementById('nav-music-hoverTips')
                    if (tips) tips.textContent = '点击页面开启音乐'
                    if (page) setStatus('浏览器已阻止自动播放，点击页面或播放按钮开启音乐。')
                }
            })
        } catch {
            player.setUIPaused?.();
            musicState(false, 'error')
        }
    }

    function togglePlayback() {
        automaticIntent = false
        awaitingGesture = false
        const player = playerElement?.aplayer
        if (!player) {
            notify(playbackState === 'error' ? '音乐暂不可用，请进入音乐页重新加载。' : '歌单正在加载，请稍候。')
            window.pjax?.loadUrl('/blog/music/')
            return
        }
        // Use playback state, not the vendor button's CSS classes. A pending play
        // attempt must also be cancellable by the user's second click.
        if (!player.paused || !player.audio.paused) {
            playbackAttempts.set(player, (playbackAttempts.get(player) || 0) + 1)
            player.pause()
            musicState(false, 'paused')
        } else {
            playbackPreparations.get(player)?.()
            requestPlayback(player)
        }
    }

    function bindPlaybackRecovery(element, player) {
        const failedUrls = new Set()
        let failures = 0
        let skipTimer = null
        let handledUrl = null
        let autoSwitching = false
        let stopped = false
        let destroyed = false
        const cancelSkip = () => {
            if (skipTimer !== null) clearTimeout(skipTimer)
            skipTimer = null
        }
        const resetFailures = () => {
            cancelSkip()
            failures = 0
            failedUrls.clear()
            handledUrl = null
            stopped = false
        }
        const showFailure = (text, allowRetry = false) => {
            musicState(!player.paused, 'error')
            if (page) setStatus(text, allowRetry)
            else {
                notify(text)
                const tips = document.getElementById('nav-music-hoverTips')
                if (tips) tips.textContent = text
            }
        }
        const onAudioError = (event) => {
            // APlayer 1.10.1 otherwise queues an unbounded two-second skip. Capture
            // the native media error before its event bridge, without changing the
            // vendor bundle or reaching into its private event-handler collection.
            event.stopImmediatePropagation()
            if (destroyed || stopped) return
            const audios = player.list.audios
            const url = audios[player.list.index]?.url
            if (!url || handledUrl === url) return
            handledUrl = url
            // An earlier play() promise must not overwrite a later media error.
            playbackAttempts.set(player, (playbackAttempts.get(player) || 0) + 1)
            cancelSkip()
            failedUrls.add(url)
            failures += 1
            let nextIndex = -1
            for (let step = 1; step < audios.length; step += 1) {
                const index = (player.list.index + step) % audios.length
                if (!failedUrls.has(audios[index].url)) {
                    nextIndex = index;
                    break
                }
            }
            if (failures >= MAX_PLAYBACK_FAILURES || nextIndex === -1) {
                stopped = true
                player.pause()
                showFailure(`连续 ${failures} 首歌曲无法播放，已暂停自动跳歌。请手动换歌或重新加载。`, true)
                return
            }
            if (player.paused) {
                showFailure('这首歌曲暂时无法播放，请手动换歌或重新加载。')
                return
            }
            showFailure(`这首歌曲暂时无法播放，即将跳过（连续失败 ${failures}/${MAX_PLAYBACK_FAILURES}）。`)
            skipTimer = setTimeout(() => {
                skipTimer = null
                if (destroyed || stopped || player.paused || !element.isConnected) return
                autoSwitching = true
                try {
                    player.list.switch(nextIndex)
                    player.play()
                } finally {
                    autoSwitching = false
                }
            }, SKIP_DELAY_MS)
        }
        player.audio.addEventListener('error', onAudioError, true)
        player.on('listswitch', () => {
            cancelSkip()
            handledUrl = null
            if (!autoSwitching) resetFailures()
        })
        player.on('pause', cancelSkip)
        // `play` only means a playback attempt; failed HTTP requests can emit it
        // before `error`. Clear errors and the failure budget only after `playing`.
        player.on('playing', () => {
            if (destroyed) return
            resetFailures()
            player.notice('', 1)
            message.textContent = ''
            retry.hidden = true
            status.hidden = true
            musicState(true, 'playing')
        })
        playbackPreparations.set(player, () => {
            resetFailures()
            // Retrying an already failed media element otherwise rejects play()
            // without firing a fresh error event, leaving the controls stuck.
            if (player.audio.error) player.audio.load()
        })
        const onManualPlay = (event) => {
            if (!event.target.closest('.aplayer-pic, .aplayer-icon-play')) return
            // The theme child-button handler and APlayer's parent handler otherwise
            // toggle twice. Own this action once, before either vendor listener.
            event.preventDefault()
            event.stopImmediatePropagation()
            togglePlayback()
        }
        player.container.addEventListener('click', onManualPlay, true)
        const picture = player.container.querySelector('.aplayer-pic')
        picture?.setAttribute('role', 'button')
        picture?.setAttribute('tabindex', '0')
        const onPictureKey = event => {
            if (!['Enter', ' '].includes(event.key) || !event.target.closest('.aplayer-pic')) return
            event.preventDefault()
            event.stopImmediatePropagation()
            togglePlayback()
        }
        player.container.addEventListener('keydown', onPictureKey, true)
        const onPageHide = () => {
            cancelSkip();
            player.pause()
        }
        window.addEventListener('pagehide', onPageHide)
        player.on('destroy', () => {
            destroyed = true
            cancelSkip()
            // Keep the capture guard on the disposed media element: a queued error
            // must not reach APlayer's still-attached bridge and restart its timer.
            // It has no external references and is collected with the old player.
            player.container.removeEventListener('click', onManualPlay, true)
            player.container.removeEventListener('keydown', onPictureKey, true)
            playbackPreparations.delete(player)
            playbackAttempts.delete(player)
            window.removeEventListener('pagehide', onPageHide)
        })
    }

    function syncConsoleMusic() {
        if (!consoleMusic) return
        const state = playbackState
        const caption = {loading: '加载中', playing: '播放中', paused: '已暂停', error: '暂不可用'}[state]
        const player = playerElement?.aplayer
        const action = !player ? '打开音乐页' : state === 'loading' ? '取消播放' : !player.paused ? '暂停音乐' : '播放音乐'
        consoleMusic.dataset.state = state
        consoleMusic.classList.toggle('on', state === 'playing')
        consoleMusic.setAttribute('aria-pressed', String(state === 'playing'))
        consoleMusic.setAttribute('aria-busy', String(state === 'loading'))
        consoleMusic.title = `${caption} · ${action}`
        consoleMusic.setAttribute('aria-label', consoleMusic.title)
        consoleStatus.textContent = caption
        const icon = consoleMusic.querySelector('.music-switch i')
        if (icon) icon.className = `anzhiyufont anzhiyu-icon-${player && !player.paused ? 'pause' : 'play'}`
    }

    function musicState(playing, state) {
        if (state) playbackState = state
        else if (!playing && playbackState !== 'error') playbackState = 'paused'
        document.getElementById('nav-music')?.classList.toggle('playing', playing)
        syncConsoleMusic()
        if (typeof anzhiyu_musicPlaying !== 'undefined') anzhiyu_musicPlaying = playing
        const menu = document.getElementById('menu-music-toggle')
        if (menu) menu.innerHTML = `<i class="anzhiyufont anzhiyu-icon-${playing ? 'pause' : 'play'}"></i><span>${playing ? '暂停音乐' : '播放音乐'}</span>`
        const tips = document.getElementById('nav-music-hoverTips')
        if (tips) tips.textContent = playing ? '暂停音乐' : '播放音乐'
        const player = playerElement?.aplayer
        const song = player?.list.audios[player.list.index]
        // APlayer needs escaped HTML; accessible names and tooltips need plain text.
        const name = document.createElement('textarea')
        name.innerHTML = song?.name || ''
        const label = `${playing ? '暂停音乐' : '播放音乐'}${name.value ? `：${name.value}` : ''}`
        disc.disabled = !player
        disc.title = label
        disc.setAttribute('aria-label', label)
        disc.setAttribute('aria-pressed', String(playing))
        disc.querySelector('i').className = `anzhiyufont anzhiyu-icon-${playing ? 'pause' : 'play'}`
        player?.container.querySelector('.aplayer-pic')?.setAttribute('aria-label', label)
        player?.container.querySelector('.aplayer-icon-play')?.setAttribute('aria-label', label)
    }

    // Other full-screen experiences (for example the surprise video) must be
    // able to pause the single persistent APlayer instance before they take
    // over the viewport.  Bump the attempt counter as well so a pending
    // play() promise cannot resume audio after the overlay has opened.
    window.siteMusicPause = () => {
        const player = playerElement?.aplayer
        automaticIntent = false
        awaitingGesture = false
        if (!player) return false
        playbackAttempts.set(player, (playbackAttempts.get(player) || 0) + 1)
        player.pause()
        player.audio?.pause?.()
        musicState(false, 'paused')
        return true
    }

    // Replace the upstream infinite 16ms readiness poll with player events.
    GLOBAL_CONFIG.navMusic = false
    anzhiyu.musicToggle = (changePlay = true) => {
        // The original theme uses false for a state-only notification from its
        // child button. Preserve that contract without starting/stopping media.
        if (changePlay === false) musicState(playerElement?.aplayer ? !playerElement.aplayer.paused : false)
        else togglePlayback()
        window.rm?.hideRightMenu?.()
    }
    anzhiyu.musicBindEvent = () => {
    } // Playback events now own state synchronization.
    anzhiyu.musicTelescopic = () => {
    } // No expandable seek surface in the mini disc.
    // The upstream right-menu script captures musicToggle before this deferred
    // adapter runs. Intercept that legacy listener rather than patching the vendor.
    document.getElementById('menu-music-toggle')?.addEventListener('click', event => {
        event.stopImmediatePropagation()
        anzhiyu.musicToggle()
    }, true)
    // The console uses the same disc; never expose the old expanded APlayer here.
    anzhiyu.addEventListenerConsoleMusicList = () => {
    }
    anzhiyu.getCustomPlayList = () => {
        if (!playerElement) {
            const template = document.querySelector('#nav-music template[data-site-meting]')
            if (!template) return
            playerElement = template.content.querySelector('meting-js')
            playerElement.setAttribute('order', 'random')
            playerElement.setAttribute('list-max-height', '60vh')
            template.replaceWith(template.content)
        }
        mountPage()
    }
    // Keep the custom element (and its media object) outside #body-wrap forever.
    // Only its UI is shown on the music page; return that UI before PJAX replaces it.
    document.addEventListener('pjax:send', () => {
        const player = playerElement?.aplayer
        if (page && mobileMusic?.matches) player?.list.hide()
        if (player && player.container.parentNode !== playerElement) playerElement.append(player.container)
    })
    document.addEventListener('pjax:complete', mountPage)
    const onGesture = event => {
        if (!event.isTrusted || !awaitingGesture || !automaticIntent) return
        if (event.type === 'keydown' && !['Enter', ' '].includes(event.key)) return
        if (event.target.closest('.aplayer, #nav-music-hoverTips, #consoleMusic')) {
            automaticIntent = false
            awaitingGesture = false
            return
        }
        if (playerElement?.aplayer) {
            gestureAttempted = true
            attemptAutoplay(playerElement.aplayer)
        }
    }
    document.addEventListener('click', onGesture)
    document.addEventListener('keydown', onGesture)
    retry.addEventListener('click', reloadPlaylist)
})()
