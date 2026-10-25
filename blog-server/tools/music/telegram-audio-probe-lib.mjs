import {createHash} from 'node:crypto'

// A deliberately narrow POC, not a replacement for a production media decoder.
const MAX_AUDIO = 16 * 1024 * 1024
const fail = (code) => {
    throw new Error(code)
}

function atoms(bytes, start = 0, end = bytes.length) {
    const items = []
    while (start < end) {
        if (end - start < 8) fail('INVALID_AUDIO')
        let length = bytes.readUInt32BE(start)
        let header = 8
        if (length === 1) {
            if (end - start < 16) fail('INVALID_AUDIO')
            const large = bytes.readBigUInt64BE(start + 8)
            if (large > BigInt(Number.MAX_SAFE_INTEGER)) fail('INVALID_AUDIO')
            length = Number(large)
            header = 16
        } else if (length === 0) length = end - start
        if (length < header || length > end - start) fail('INVALID_AUDIO')
        items.push({
            type: bytes.toString('latin1', start + 4, start + 8),
            start,
            payload: start + header,
            end: start + length
        })
        start += length
    }
    return items
}

export function inspectM4a(bytes) {
    if (!Buffer.isBuffer(bytes) || bytes.length < 32) fail('INVALID_AUDIO')
    if (bytes.length > MAX_AUDIO) fail('FILE_TOO_LARGE')
    const top = atoms(bytes)
    const ftyp = top.find((a) => a.type === 'ftyp')
    const moov = top.find((a) => a.type === 'moov')
    if (!ftyp || ftyp.end - ftyp.payload < 8 || !moov || !top.some((a) => a.type === 'mdat')) fail('INVALID_AUDIO')
    const info = {container: 'M4A', mediaType: 'audio/mp4', title: '', artist: '', durationSeconds: null}
    const entries = []
    const containers = new Set(['moov', 'trak', 'mdia', 'minf', 'stbl', 'udta', 'meta'])

    function visit(parent, depth = 0) {
        if (depth > 10) fail('INVALID_AUDIO')
        for (const a of atoms(bytes, parent.payload + (parent.type === 'meta' ? 4 : 0), parent.end)) {
            if (a.type === 'mvhd') {
                const version = bytes[a.payload]
                if (version === 0 && a.end - a.payload >= 20) {
                    const scale = bytes.readUInt32BE(a.payload + 12)
                    if (scale) info.durationSeconds = bytes.readUInt32BE(a.payload + 16) / scale
                } else if (version === 1 && a.end - a.payload >= 32) {
                    const scale = bytes.readUInt32BE(a.payload + 20)
                    if (scale) info.durationSeconds = Number(bytes.readBigUInt64BE(a.payload + 24)) / scale
                }
            } else if (a.type === 'stsd') {
                if (a.end - a.payload < 8) fail('INVALID_AUDIO')
                const samples = atoms(bytes, a.payload + 8, a.end)
                if (samples.length !== bytes.readUInt32BE(a.payload + 4)) fail('INVALID_AUDIO')
                for (const sample of samples) {
                    entries.push(sample.type)
                    if (sample.type === 'mp4a' && sample.end - sample.payload >= 28) {
                        info.channels = bytes.readUInt16BE(sample.payload + 16)
                        info.sampleRate = bytes.readUInt32BE(sample.payload + 24) / 65536
                    }
                }
            } else if (a.type === 'ilst') {
                for (const tag of atoms(bytes, a.payload, a.end)) {
                    const field = tag.type === '©nam' ? 'title' : tag.type === '©ART' ? 'artist' : null
                    if (!field) continue
                    const data = atoms(bytes, tag.payload, tag.end).find((value) => value.type === 'data')
                    if (data && data.end - data.payload >= 8 && data.end - data.payload <= 4096)
                        info[field] = bytes.toString('utf8', data.payload + 8, data.end).replace(/[\u0000-\u001f]/g, '').trim()
                }
            } else if (containers.has(a.type)) visit(a, depth + 1)
        }
    }

    visit(moov)
    if (!entries.length || entries.some((entry) => entry !== 'mp4a')) fail('UNSUPPORTED_AUDIO')
    info.sampleEntry = 'mp4a'
    info.sha256 = createHash('sha256').update(bytes).digest('hex')
    info.bytes = bytes.length
    return info
}

export function probeSettings(env) {
    try {
        const base = new URL(env.IMGBED_BASE_URL || 'https://cloudflare-imgbed-6of.pages.dev')
        const token = env.IMGBED_API_TOKEN
        const root = env.IMGBED_ROOT_FOLDER || 'blog'
        const channelName = env.IMGBED_CHANNEL_NAME || 'TelegramBot'
        if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash || base.pathname !== '/' ||
            !token || /[\r\n]/.test(token) || !/^[a-zA-Z0-9_-]+(?:\/[a-zA-Z0-9_-]+)*$/.test(root) ||
            (env.IMGBED_UPLOAD_CHANNEL && env.IMGBED_UPLOAD_CHANNEL !== 'telegram') || /[\r\n]/.test(channelName)) fail('INVALID_CONFIGURATION')
        return {base, token, folder: `${root}/music/audio/probe`, channelName}
    } catch {
        fail('INVALID_CONFIGURATION')
    }
}

async function boundedBody(response, maximum) {
    const pieces = []
    let size = 0
    if (!response.body) return Buffer.alloc(0)
    const reader = response.body.getReader()
    try {
        while (true) {
            const {value, done} = await reader.read()
            if (done) break
            size += value.length
            if (size > maximum) {
                await reader.cancel();
                fail('RESPONSE_TOO_LARGE')
            }
            pieces.push(Buffer.from(value))
        }
    } finally {
        reader.releaseLock()
    }
    return Buffer.concat(pieces)
}

export async function uploadSample(bytes, settings, fetchImpl = fetch) {
    inspectM4a(bytes)
    const url = new URL('/upload', settings.base)
    url.search = new URLSearchParams({
        uploadChannel: 'telegram',
        channelName: settings.channelName,
        uploadFolder: settings.folder,
        returnFormat: 'full',
        uploadNameType: 'index',
        autoRetry: 'false',
        serverCompress: 'false'
    }).toString()
    const body = new FormData()
    body.append('file', new Blob([bytes], {type: 'audio/mp4'}), `audio-probe-${createHash('sha256').update(bytes).digest('hex').slice(0, 16)}.m4a`)
    let response
    try {
        response = await fetchImpl(url, {
            method: 'POST', headers: {Authorization: `Bearer ${settings.token}`},
            body, redirect: 'error', signal: AbortSignal.timeout(60000)
        })
    } catch {
        fail('UNKNOWN_UPLOAD_OUTCOME')
    }
    // A received error status is still not evidence that the upstream stored nothing.
    if (!response.ok) fail(`UPLOAD_HTTP_${response.status}`)
    try {
        const result = JSON.parse((await boundedBody(response, 65536)).toString('utf8'))
        if (!Array.isArray(result) || result.length !== 1 || typeof result[0].src !== 'string') fail('BAD_UPLOAD_RESPONSE')
        const publicUrl = new URL(result[0].src, settings.base)
        if (publicUrl.origin !== settings.base.origin || publicUrl.username || publicUrl.password || publicUrl.search || publicUrl.hash ||
            !publicUrl.pathname.startsWith(`/file/${settings.folder}/`)) fail('BAD_UPLOAD_RESPONSE')
        const relative = publicUrl.pathname.slice(`/file/${settings.folder}/`.length)
        // Probe has one flat audio folder; percent-encoded separators or traversal are never accepted.
        if (!/^[a-zA-Z0-9_-]+\.(?:m4a|mp4)$/.test(relative)) fail('BAD_UPLOAD_RESPONSE')
        return {path: publicUrl.pathname.slice('/file/'.length), publicUrl: publicUrl.href}
    } catch {
        fail('BAD_UPLOAD_RESPONSE')
    }
}

function safeHeaders(headers) {
    return Object.fromEntries(['content-type', 'content-length', 'content-range', 'accept-ranges',
        'access-control-allow-origin', 'cache-control'].map((key) => [key, headers.get(key)]))
}

export async function listProbeFiles(settings, fetchImpl = fetch) {
    const url = new URL('/api/manage/list', settings.base)
    url.search = new URLSearchParams({dir: settings.folder, recursive: 'false', start: '0', count: '25'}).toString()
    let response
    try {
        response = await fetchImpl(url, {
            headers: {Authorization: `Bearer ${settings.token}`},
            redirect: 'error', signal: AbortSignal.timeout(20000)
        })
    } catch {
        fail('LIST_UNAVAILABLE')
    }
    if (!response.ok) fail(`LIST_HTTP_${response.status}`)
    try {
        const result = JSON.parse((await boundedBody(response, 65536)).toString('utf8'))
        if (!Array.isArray(result.files) || result.files.length > 25) fail('BAD_LIST_RESPONSE')
        return result.files.flatMap((file) => {
            if (typeof file.name !== 'string' || !file.name.startsWith(`${settings.folder}/`) ||
                /[\u0000-\u001f?%#]/.test(file.name) || file.name.split('/').some((segment) => segment === '.' || segment === '..')) return []
            const metadata = file.metadata || {}
            return [{
                path: file.name,
                publicUrl: new URL(`/file/${file.name.split('/').map(encodeURIComponent).join('/')}`, settings.base).href,
                mediaType: metadata['File-Mime'] || metadata.FileType || '',
                bytes: Number(metadata.FileSizeBytes || metadata['File-Size'] || 0),
                timestamp: String(metadata.TimeStamp || '')
            }]
        })
    } catch {
        fail('BAD_LIST_RESPONSE')
    }
}

export async function verifySample(publicUrl, bytes, fetchImpl = fetch) {
    const headers = {Origin: 'http://127.0.0.1:5173', Referer: 'http://127.0.0.1:5173/blog/music/'}
    const head = await fetchImpl(publicUrl, {
        method: 'HEAD',
        headers,
        redirect: 'error',
        signal: AbortSignal.timeout(20000)
    })
    const range = async (start, end) => {
        const response = await fetchImpl(publicUrl, {
            method: 'GET', headers: {...headers, Range: `bytes=${start}-${end}`},
            redirect: 'error', signal: AbortSignal.timeout(20000)
        })
        // Some deployments ignore Range and return the entire object; keep memory bounded and report that separately.
        const actual = await boundedBody(response, MAX_AUDIO)
        const expected = bytes.subarray(start, end + 1)
        return {
            status: response.status, headers: safeHeaders(response.headers),
            matchesLocalBytes: actual.equals(expected), expectedContentRange: `bytes ${start}-${end}/${bytes.length}`
        }
    }
    const firstRange = await range(0, Math.min(4095, bytes.length - 1))
    const tailRange = await range(Math.max(0, bytes.length - 4096), bytes.length - 1)
    const valid = (check) => check.status === 206 && check.matchesLocalBytes && check.headers['content-range'] === check.expectedContentRange
    return {
        head: {status: head.status, headers: safeHeaders(head.headers)}, firstRange, tailRange,
        rangeSupported: valid(firstRange) && valid(tailRange)
    }
}
