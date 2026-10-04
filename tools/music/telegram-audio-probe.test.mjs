import test from 'node:test'
import assert from 'node:assert/strict'
import {inspectM4a, probeSettings, uploadSample, verifySample, listProbeFiles} from './telegram-audio-probe-lib.mjs'

const atom = (type, ...parts) => {
    const payload = Buffer.concat(parts)
    const header = Buffer.alloc(8)
    header.writeUInt32BE(payload.length + 8)
    header.write(type, 4, 4, 'latin1')
    return Buffer.concat([header, payload])
}

function fixture(codec = 'mp4a') {
    const movie = Buffer.alloc(20)
    movie.writeUInt32BE(1000, 12)
    movie.writeUInt32BE(123000, 16)
    const description = Buffer.alloc(8)
    description.writeUInt32BE(1, 4)
    const sample = Buffer.alloc(28)
    sample.writeUInt16BE(2, 16)
    sample.writeUInt32BE(44100 * 65536, 24)
    const tag = (name, value) => atom(name, atom('data', Buffer.alloc(8), Buffer.from(value)))
    return Buffer.concat([
        atom('ftyp', Buffer.from('M4A \0\0\0\0M4A ', 'latin1')),
        atom('moov', atom('mvhd', movie),
            atom('trak', atom('mdia', atom('minf', atom('stbl', atom('stsd', description, atom(codec, sample)))))),
            atom('udta', atom('meta', Buffer.alloc(4), atom('ilst', tag('©nam', '测试音频'), tag('©ART', '测试作者'))))),
        atom('mdat', Buffer.alloc(200, 5)),
    ])
}

const env = {
    IMGBED_BASE_URL: 'https://img.example.test', IMGBED_API_TOKEN: 'fixture-token',
    IMGBED_UPLOAD_CHANNEL: 'telegram', IMGBED_CHANNEL_NAME: 'TelegramBot', IMGBED_ROOT_FOLDER: 'blog',
}

test('inspect real atom structure, metadata and audio sample entry', () => {
    const info = inspectM4a(fixture())
    assert.equal(info.container, 'M4A')
    assert.equal(info.sampleEntry, 'mp4a')
    assert.equal(info.mediaType, 'audio/mp4')
    assert.equal(info.durationSeconds, 123)
    assert.equal(info.channels, 2)
    assert.equal(info.sampleRate, 44100)
    assert.equal(info.title, '测试音频')
    assert.equal(info.artist, '测试作者')
})
test('reject fake audio bytes', () => assert.throws(() => inspectM4a(Buffer.from('not audio')), /INVALID_AUDIO/))
test('reject encrypted audio sample entry', () => assert.throws(() => inspectM4a(fixture('enca')), /UNSUPPORTED_AUDIO/))
test('reject video sample entry', () => assert.throws(() => inspectM4a(fixture('avc1')), /UNSUPPORTED_AUDIO/))
test('reject malformed atom sizes', () => {
    const bytes = fixture()
    bytes.writeUInt32BE(0xffffffff, 0)
    assert.throws(() => inspectM4a(bytes), /INVALID_AUDIO/)
})
test('configuration never includes credentials in errors', () => {
    assert.throws(() => probeSettings({...env, IMGBED_API_TOKEN: 'secret\r\nvalue'}), /^Error: INVALID_CONFIGURATION$/)
    assert.throws(() => probeSettings({...env, IMGBED_BASE_URL: 'http://img.example.test'}), /INVALID_CONFIGURATION/)
    assert.throws(() => probeSettings({...env, IMGBED_ROOT_FOLDER: '../other'}), /INVALID_CONFIGURATION/)
})
test('upload exactly one audio to Telegram with credentials only in header', async () => {
    let calls = 0
    const result = await uploadSample(fixture(), probeSettings(env), async (url, options) => {
        calls++
        assert.equal(options.method, 'POST')
        assert.equal(options.redirect, 'error')
        assert.equal(options.headers.Authorization, 'Bearer fixture-token')
        assert.equal(url.searchParams.get('uploadFolder'), 'blog/music/audio/probe')
        assert.equal(url.searchParams.get('uploadChannel'), 'telegram')
        assert.equal(url.searchParams.get('channelName'), 'TelegramBot')
        assert.equal(url.searchParams.get('autoRetry'), 'false')
        assert.equal(url.searchParams.get('serverCompress'), 'false')
        assert.ok(!url.toString().includes('fixture-token'))
        assert.equal(options.body.get('file').type, 'audio/mp4')
        return Response.json([{src: '/file/blog/music/audio/probe/sample.m4a'}])
    })
    assert.equal(calls, 1)
    assert.equal(result.publicUrl, 'https://img.example.test/file/blog/music/audio/probe/sample.m4a')
    assert.ok(!JSON.stringify(result).includes('fixture-token'))
})
test('reject unsafe provider URLs and out-of-folder response', async () => {
    for (const src of ['https://other.test/file/blog/music/audio/probe/sample.m4a',
        '/file/blog/avatars/sample.m4a', '/file/blog/music/audio/probe/sample.m4a?token=secret',
        '/file/blog/music/audio/probe/%2e%2e/sample.m4a', '/file/blog/music/audio/probe/a%2Fb.m4a']) {
        await assert.rejects(uploadSample(fixture(), probeSettings(env), async () => Response.json([{src}])), /BAD_UPLOAD_RESPONSE/)
    }
})
test('unknown upload outcome is never automatically retried', async () => {
    let calls = 0
    await assert.rejects(uploadSample(fixture(), probeSettings(env), async () => {
        calls++;
        throw new Error('network');
    }), /UNKNOWN_UPLOAD_OUTCOME/)
    assert.equal(calls, 1)
})
test('HEAD and first/tail range checks use no credentials and compare real bytes', async () => {
    const bytes = fixture()
    const checks = await verifySample('https://img.example.test/file/blog/music/audio/probe/sample.m4a', bytes,
        async (_url, options) => {
            assert.equal(options.headers.Authorization, undefined)
            assert.equal(options.headers.Origin, 'http://127.0.0.1:5173')
            assert.equal(options.headers.Referer, 'http://127.0.0.1:5173/blog/music/')
            if (options.method === 'HEAD') return new Response(null, {
                headers: {
                    'Content-Type': 'audio/mp4',
                    'Content-Length': String(bytes.length)
                }
            })
            const [start, end] = options.headers.Range.slice(6).split('-').map(Number)
            return new Response(bytes.subarray(start, end + 1), {
                status: 206, headers: {
                    'Content-Type': 'audio/mp4',
                    'Content-Range': `bytes ${start}-${end}/${bytes.length}`,
                    'Access-Control-Allow-Origin': '*',
                }
            })
        })
    assert.equal(checks.rangeSupported, true)
    assert.equal(checks.firstRange.matchesLocalBytes, true)
    assert.equal(checks.tailRange.matchesLocalBytes, true)
})
test('HTTP 200 is not reported as successful Range support', async () => {
    const bytes = fixture()
    const checks = await verifySample('https://img.example.test/file/sample.m4a', bytes,
        async (_url, options) => new Response(options.method === 'HEAD' ? null : bytes, {headers: {'Content-Type': 'audio/mp4'}}))
    assert.equal(checks.rangeSupported, false)
})

test('provider may normalize audio/mp4 filename from .m4a to .mp4', async () => {
    const result = await uploadSample(fixture(), probeSettings(env), async () => Response.json([{src: '/file/blog/music/audio/probe/sample.mp4'}]))
    assert.equal(result.publicUrl, 'https://img.example.test/file/blog/music/audio/probe/sample.mp4')
})
test('size limit is reported separately from malformed audio', () => {
    assert.throws(() => inspectM4a(Buffer.alloc(16 * 1024 * 1024 + 1)), /FILE_TOO_LARGE/)
})
test('read-only reconciliation lists the exact probe folder without exposing provider secrets', async () => {
    const result = await listProbeFiles(probeSettings(env), async (url, options) => {
        assert.equal(url.searchParams.get('dir'), 'blog/music/audio/probe')
        assert.equal(options.method, undefined)
        return Response.json({
            files: [
                {
                    name: 'blog/music/audio/probe/sample.mp4', metadata: {
                        'File-Mime': 'audio/mp4', FileSizeBytes: 123,
                        'Bot-Token': 'must-not-return', TimeStamp: '1234'
                    }
                },
                {name: 'blog/avatars/secret.jpg'}, {name: 'blog/music/audio/probe/../secret.mp4'},
            ]
        })
    })
    assert.equal(result.length, 1)
    assert.equal(result[0].mediaType, 'audio/mp4')
    assert.ok(!JSON.stringify(result).includes('must-not-return'))
})
