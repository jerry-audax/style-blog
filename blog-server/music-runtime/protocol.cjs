'use strict'

// Only allowlisted projections ever leave this backend-only process.
function parseJson(stdout) {
    const clean = String(stdout || '').replace(/\x1b\[[0-9;]*m/g, '').trim()
    try {
        return JSON.parse(clean)
    } catch {
    }
    for (const line of clean.split('\n')) {
        try {
            const value = JSON.parse(line);
            if (value && typeof value === 'object') return value
        } catch {
        }
    }
    throw new Error('INVALID_RESPONSE')
}

function authorized(value) {
    const message = String(value?.message || '')
    if (/未登录|尚未登录|请.*登录|not logged/i.test(message)) return false
    if (value?.success === true && /已登录|登录状态有效|logged in|authenticated/i.test(message)) return true
    if (value?.success === false) return false
    throw new Error('INVALID_RESPONSE')
}

function officialAuthorizationUrl(value) {
    try {
        const url = new URL(value)
        if (url.protocol === 'https:' && ['163cn.tv', 'music.163.com', 'st.music.163.com'].includes(url.hostname)
            && !url.username && !url.password) return url.href
    } catch {
    }
    throw new Error('INVALID_RESPONSE')
}

function safeImage(value) {
    try {
        const url = new URL(value)
        if (url.protocol === 'https:' && !url.username && !url.password) return url.href
    } catch {
    }
    return ''
}

const text = value => String(value || '').slice(0, 500)

function songView(song) {
    // Private cloud uploads are never published to the visitors' playlist.
    if (!song || song.privateCloudSong === true || song.privateCloudSong === 1
        || !/^[1-9]\d*$/.test(String(song.originalId))) return null
    return {
        id: String(song.originalId), name: text(song.name),
        artist: Array.isArray(song.artists) ? song.artists.map(a => text(a.name)).filter(Boolean).join(' / ') : '',
        cover: safeImage(song.coverImgUrl),
        ownerPlayable: song.playFlag === true, preview: song.freeTrailFlag === true,
    }
}

function apiData(value) {
    if (value?.code === 401 || value?.code === 301) throw new Error('NOT_AUTHORIZED')
    if (value?.code !== 200) throw new Error('UNAVAILABLE')
    return value.data
}

module.exports = {parseJson, authorized, officialAuthorizationUrl, songView, apiData}
