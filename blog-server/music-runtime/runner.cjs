'use strict'
const fs = require('node:fs')
const path = require('node:path')
const cp = require('node:child_process')
const {parseJson, authorized, officialAuthorizationUrl, songView, apiData} = require('./protocol.cjs')
const {atomicJson, currentHome, createHome, activate, environment} = require('./state.cjs')
const cli = require.resolve('@music163/ncm-cli/dist/index.js')

function call(home, args, readOnly = false) {
    for (let attempt = 0; attempt < (readOnly ? 2 : 1); attempt++) {
        const result = cp.spawnSync(process.execPath, [cli, ...args, '--output', 'json'], {
            cwd: home, env: environment(home), encoding: 'utf8', timeout: 18000,
            windowsHide: true, maxBuffer: 4 * 1024 * 1024,
        })
        if (!result.error && result.status === 0) return parseJson(result.stdout)
    }
    // Never forward CLI stderr, raw stdout, command arguments or provider errors.
    throw new Error('UNAVAILABLE')
}

function configured(home) {
    return fs.existsSync(path.join(home, '.config/ncm-cli/credentials.enc.json'))
}

function isAuthorized(home, invoke) {
    return authorized(invoke(home, ['login', '--check'], true))
}

function synchronize(home, playlistId, invoke) {
    if (!isAuthorized(home, invoke)) throw new Error('NOT_AUTHORIZED')
    let found
    for (const group of ['created', 'collected']) {
        for (let offset = 0; offset < 1000; offset += 100) {
            const data = apiData(invoke(home, ['playlist', group, '--limit', '100', '--offset', String(offset)], true))
            if (!Array.isArray(data?.records)) throw new Error('INVALID_RESPONSE')
            found = data.records.find(list => String(list.originalId) === playlistId)
            if (found || data.records.length < 100) break
        }
        if (found) break
    }
    if (!found || typeof found.id !== 'string') throw new Error('PLAYLIST_NOT_FOUND')
    const songs = new Map()
    // Fetch an extra page to detect oversize rather than silently publish a partial list.
    for (let offset = 0; offset <= 2000; offset += 500) {
        const data = apiData(invoke(home, ['playlist', 'tracks', '--playlistId', found.id,
            '--limit', '500', '--offset', String(offset)], true))
        if (!Array.isArray(data)) throw new Error('INVALID_RESPONSE')
        if (offset === 2000 && data.length) throw new Error('PLAYLIST_TOO_LARGE')
        for (const entry of data) {
            const song = songView(entry);
            if (song) songs.set(song.id, song)
        }
        if (data.length < 500) break
    }
    return {
        playlistId, name: String(found.name || '').slice(0, 500),
        syncedAt: new Date().toISOString(), songs: [...songs.values()]
    }
}

function run(root, action, playlistId, invoke = call) {
    if (!path.isAbsolute(root) || !['status', 'authorize', 'sync', 'logout'].includes(action)) throw new Error('INVALID_REQUEST')
    let home
    try {
        home = currentHome(root)
    } catch {
        if (action === 'status') return {configured: false, authorized: false}
        throw new Error('NOT_CONFIGURED')
    }
    if (!configured(home)) {
        if (action === 'status') return {configured: false, authorized: false}
        throw new Error('NOT_CONFIGURED')
    }
    if (action === 'status') return {configured: true, authorized: isAuthorized(home, invoke)}
    if (action === 'sync') {
        if (!/^[1-9]\d{0,19}$/.test(playlistId || '')) throw new Error('INVALID_REQUEST')
        return synchronize(home, playlistId, invoke)
    }
    if (action === 'logout') {
        // Rotate first: an old official background QR poll can no longer reactivate
        // this backend's active session, even if upstream logout fails or times out.
        const next = createHome(root, home)
        activate(root, next.id)
        let confirmed = false
        try {
            confirmed = invoke(home, ['logout']).success === true
        } catch {
        }
        return {authorized: false, remoteLogoutConfirmed: confirmed}
    }
    if (isAuthorized(home, invoke)) return {authorized: true, authorizationUrl: '', expiresAt: null}
    const pendingFile = path.join(home, 'pending-authorization.json')
    try {
        const pending = JSON.parse(fs.readFileSync(pendingFile, 'utf8'))
        if (Date.parse(pending.expiresAt) > Date.now()) {
            officialAuthorizationUrl(pending.authorizationUrl)
            return pending
        }
    } catch {
    }
    const result = invoke(home, ['login', '--background'])
    if (result.success !== true) throw new Error('UNAVAILABLE')
    const pending = {
        authorized: false,
        authorizationUrl: officialAuthorizationUrl(result.qrCodeUrl || result.clickableUrl),
        expiresAt: new Date(Date.now() + 240000).toISOString()
    }
    atomicJson(pendingFile, pending)
    return pending
}

if (require.main === module) {
    try {
        console.log(JSON.stringify({ok: true, data: run(...process.argv.slice(2))}))
    } catch (error) {
        const reasons = ['NOT_CONFIGURED', 'NOT_AUTHORIZED', 'INVALID_RESPONSE', 'PLAYLIST_NOT_FOUND', 'PLAYLIST_TOO_LARGE', 'INVALID_REQUEST']
        console.log(JSON.stringify({
            ok: false,
            reason: reasons.includes(error.message) ? error.message : 'UNAVAILABLE'
        }))
        process.exitCode = 1
    }
}
module.exports = {run}
