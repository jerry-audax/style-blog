'use strict'
const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), crypto = require('node:crypto')
const {parseJson, authorized, officialAuthorizationUrl, songView, apiData} = require('./protocol.cjs')
const {currentHome, createHome, activate, environment} = require('./state.cjs')
const {credentials} = require('./setup.cjs')
const {run} = require('./runner.cjs')

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'blog-music-test-'))
    // Only generated test fixtures are removed; never real music state.
    t.after(() => fs.rmSync(root, {recursive: true, force: true}))
    const next = createHome(root)
    fs.writeFileSync(path.join(next.home, '.config/ncm-cli/credentials.enc.json'), 'fixture-encrypted')
    activate(root, next.id)
    return {root, home: next.home}
}

test('login success alone is not authorization; known states are parsed fail-closed', () => {
    assert.equal(authorized({success: true, message: '已登录'}), true)
    assert.equal(authorized({success: true, message: '尚未登录，请登录'}), false)
    assert.equal(authorized({success: false, message: 'not logged in'}), false)
    assert.throws(() => authorized({success: true, accessToken: 'do-not-export'}), /INVALID_RESPONSE/)
})
test('invalid provider JSON and API failure are not successful operations', () => {
    assert.deepEqual(parseJson('\x1b[32m{"code":200}\x1b[0m'), {code: 200})
    assert.throws(() => parseJson('accessToken=do-not-export'), /INVALID_RESPONSE/)
    assert.throws(() => apiData({code: 400, data: {}}), /UNAVAILABLE/)
    assert.throws(() => apiData({code: 301}), /NOT_AUTHORIZED/)
})
test('authorization links must be official HTTPS; private songs and unsafe covers are excluded', () => {
    assert.equal(officialAuthorizationUrl('https://163cn.tv/fixture'), 'https://163cn.tv/fixture')
    for (const url of ['http://163cn.tv/a', 'https://163cn.tv.attacker.test/a', 'https://password@music.163.com/a', 'javascript:alert(1)'])
        assert.throws(() => officialAuthorizationUrl(url))
    assert.equal(songView({originalId: 1, privateCloudSong: true}), null)
    const song = songView({
        originalId: 1,
        name: '歌曲',
        artists: [{name: '歌手'}],
        coverImgUrl: 'javascript:alert(1)',
        playFlag: true,
        accessToken: 'never-export',
        id: 'encrypted-id'
    })
    assert.equal(song.cover, '');
    assert.equal(song.artist, '歌手');
    assert.equal(song.ownerPlayable, true)
    assert.equal(song.accessToken, undefined);
    assert.equal(song.id, '1')
})
test('only declared operations and numeric playlist IDs can reach the CLI', t => {
    const {root} = fixture(t), invoke = () => {
        throw new Error('must not call')
    }
    for (const action of ['config', 'diag', 'play', 'delete', '../../login']) assert.throws(() => run(root, action, null, invoke), /INVALID_REQUEST/)
    assert.throws(() => run(root, 'sync', '--command', invoke), /INVALID_REQUEST/)
    assert.equal(environment(root).DB_PASSWORD, undefined);
    assert.equal(environment(root).IMGBED_API_TOKEN, undefined)
    assert.equal(environment(root).HOME, root)
})
test('single pending QR is reused without generating more background login attempts', t => {
    const {root} = fixture(t);
    let logins = 0
    const invoke = (_, args) => {
        if (args.includes('--check')) return {success: true, message: '未登录'}
        assert.deepEqual(args, ['login', '--background']);
        logins++
        return {success: true, qrCodeUrl: 'https://163cn.tv/fixture', accessToken: 'never-export'}
    }
    const first = run(root, 'authorize', null, invoke), second = run(root, 'authorize', null, invoke)
    assert.deepEqual(second, first);
    assert.equal(logins, 1);
    assert.equal(first.accessToken, undefined)
})
test('logout rotates the active home even if upstream fails; old QR cannot restore it', t => {
    const {root, home} = fixture(t)
    fs.writeFileSync(path.join(home, '.config/ncm-cli/tokens.enc.json'), 'old-encrypted-session')
    const result = run(root, 'logout', null, () => {
        throw new Error('upstream timeout')
    })
    assert.equal(result.authorized, false);
    assert.equal(result.remoteLogoutConfirmed, false)
    const next = currentHome(root)
    assert.notEqual(next, home)
    assert.ok(fs.existsSync(path.join(next, '.config/ncm-cli/credentials.enc.json')))
    assert.ok(!fs.existsSync(path.join(next, '.config/ncm-cli/tokens.enc.json')))
})
test('sync finds encrypted resource ID, fetches data arrays, projects and deduplicates songs', t => {
    const {root} = fixture(t), seen = []
    const invoke = (_, args) => {
        seen.push(args)
        if (args.includes('--check')) return {success: true, message: '已登录'}
        if (args[1] === 'created') return {
            code: 200,
            data: {
                records: [{originalId: 939817038, id: 'ENCRYPTED-FIXTURE', name: '博客歌单'}, {
                    originalId: 2,
                    id: 'OTHER',
                    name: '私密歌单'
                }]
            }
        }
        assert.equal(args[args.indexOf('--playlistId') + 1], 'ENCRYPTED-FIXTURE')
        return {
            code: 200,
            data: [{originalId: 1, name: '歌曲'}, {originalId: 1, name: '歌曲'}, {
                originalId: 2,
                privateCloudSong: true
            }]
        }
    }
    const result = run(root, 'sync', '939817038', invoke)
    assert.equal(result.songs.length, 1);
    assert.equal(result.playlistId, '939817038')
    assert.equal(seen.length, 3);
    assert.ok(!JSON.stringify(result).includes('ENCRYPTED-FIXTURE'));
    assert.ok(!JSON.stringify(result).includes('私密歌单'))
})
test('sync is bounded and rejects oversize or invalid playlist pages', t => {
    const {root} = fixture(t)
    let pages = 0
    const invoke = (_, args) => {
        if (args.includes('--check')) return {success: true, message: '已登录'}
        if (args[1] === 'created') return {code: 200, data: {records: [{originalId: 1, id: 'RESOURCE'}]}}
        pages++;
        return {code: 200, data: Array.from({length: 500}, (_, i) => ({originalId: pages * 500 + i + 1}))}
    }
    assert.throws(() => run(root, 'sync', '1', invoke), /PLAYLIST_TOO_LARGE/);
    assert.equal(pages, 5)
})
test('field-based PKCS8 credentials are validated without echoing secrets', () => {
    const {privateKey} = crypto.generateKeyPairSync('rsa', {modulusLength: 2048})
    const key = privateKey.export({format: 'der', type: 'pkcs8'}).toString('base64')
    const value = credentials(`AppID=fixture-app\nPrivateKey=${key}\n`)
    assert.equal(value.appId, 'fixture-app');
    assert.equal(value.privateKey, key)
    assert.throws(() => credentials('AppSecret=not-a-private-key'), /INVALID_CREDENTIALS/)
})
