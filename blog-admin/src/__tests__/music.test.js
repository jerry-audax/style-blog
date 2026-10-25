import {afterEach, beforeEach, expect, it, vi} from 'vitest'
import {createApp, nextTick} from 'vue'
import {createPinia} from 'pinia'
import {useAdminAuthStore} from '../stores/auth'

vi.mock('../api', () => ({
    getMusicStatus: vi.fn(),
    getMusicPlaylist: vi.fn(),
    authorizeMusic: vi.fn(),
    syncMusic: vi.fn(),
    revokeMusic: vi.fn(),
    getMusicCatalog: vi.fn(),
    getMediaCapabilities: vi.fn(),
    createMusicTrack: vi.fn(),
    updateMusicTrack: vi.fn(),
    uploadMusicMedia: vi.fn(),
    deleteMusicMedia: vi.fn(),
    deleteMusicTrack: vi.fn(), loginByPassword: vi.fn(), me: vi.fn().mockResolvedValue({owner:true,userId:1}), logout: vi.fn(),
    completeMusicCover: vi.fn(),
    previewMediaDeletion:vi.fn(),submitMediaDeletion:vi.fn(),getMediaDeletionOperation:vi.fn(),listMediaDeletionOperations:vi.fn(),confirmMediaDeletionOwnership:vi.fn(),
    createMediaUploadOperation:vi.fn(),putMediaUploadContent:vi.fn(),getMediaUploadOperation:vi.fn(),listMediaUploadOperations:vi.fn(),pauseMediaUploadOperation:vi.fn(),recoverMediaUploadOperation:vi.fn(),bindMediaUploadOperation:vi.fn()
}))
vi.mock('qrcode', () => ({default: {toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,fixture')}}))
import {getMusicStatus, getMusicPlaylist, authorizeMusic, syncMusic, revokeMusic, getMusicCatalog, getMediaCapabilities, updateMusicTrack, uploadMusicMedia, deleteMusicMedia, deleteMusicTrack, createMusicTrack} from '../api'
import MusicManage from '../views/MusicManage.vue'
import QRCode from 'qrcode'
import * as uploadApi from '../api'

let app, host
const status = {configured: true, authorized: false, playlistId: '939817038'}
const legacyCaps = {metadataWrite: true, metadataVersionRequired: false, mediaUpload: true, mediaReplace: true, mediaDelete: true, catalogDelete: true, uploadProtocol:null, uploadLimits:null}
const pgCaps = {...legacyCaps, metadataVersionRequired: true, mediaUpload: false, mediaReplace: false, mediaDelete: false, catalogDelete: false}
const trackFixture = version => ({id: 'track-1', title: '歌曲', artist: '歌手', sourceId: '', enabled: true, sortOrder: 0, audio: {path: 'blog/music/a.mp3', url: 'https://example.test/file/blog/music/a.mp3', mediaType: null, size: null}, cover: null, lyrics: null, version})

async function flush() {
    await Promise.resolve();
    await Promise.resolve();
    await nextTick();
    await Promise.resolve();
    await nextTick()
}

async function mount(value = status, tracks = []) {
    getMusicStatus.mockResolvedValue(value);
    getMusicPlaylist.mockResolvedValue(null)
    getMusicCatalog.mockResolvedValue(tracks)
    host = document.createElement('div');
    document.body.append(host);
    const pinia=createPinia(); app = createApp(MusicManage); app.use(pinia); await useAdminAuthStore(pinia).fetchMe()
    app.mount(host);
    await flush()
}

const button = name => [...host.querySelectorAll('button')].find(node => node.textContent.trim() === name)
beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
    QRCode.toDataURL.mockResolvedValue('data:image/png;base64,fixture')
    getMediaCapabilities.mockResolvedValue(legacyCaps)
    uploadApi.me.mockResolvedValue({owner:true,userId:1})
    uploadApi.listMediaUploadOperations.mockResolvedValue({records:[],total:0})
    uploadApi.listMediaDeletionOperations.mockResolvedValue({records:[],total:0})
})
afterEach(() => {
    app?.unmount();
    host?.remove();
    vi.useRealTimers();
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
})
it('provides clear unconfigured state with authorization and sync disabled', async () => {
    await mount({...status, configured: false})
    expect(host.textContent).toContain('请先完成后端 CLI 配置')
    expect(button('获取授权二维码').disabled).toBe(true);
    expect(button('同步歌单').disabled).toBe(true)
})

it('tracked cover removal clears an external-only cover without overwriting any metadata drafts',async()=>{
    const target={kind:'MUSIC_MEDIA',id:'track-1',slot:'COVER'},id='00000000-0000-0000-0000-000000000811'
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaDelete:true,catalogDelete:true,deleteProtocol:'tracked-delete-v1'})
    uploadApi.previewMediaDeletion.mockResolvedValue({target,expectedVersion:'7',candidates:[],referenceCoverageComplete:false,environmentId:'local'})
    uploadApi.submitMediaDeletion.mockResolvedValue({operationId:id,target,originalVersion:'7',committedAt:'2026-10-17T00:00:00Z',localRemovalCommitted:true,processingComplete:true,allFilesDeleted:false,items:[]})
    await mount(status,[{...trackFixture(7),cover:{path:'',url:'https://example.test/old.jpg'}},{...trackFixture(0),id:'track-2'}])
    const field=host.querySelector('.track-fields input[maxlength="200"]');field.value='未保存的歌曲名';field.dispatchEvent(new Event('input'));await flush()
    expect(button('删除封面')).toBeDefined();expect(button('删除封面').disabled).toBe(false)
    button('删除封面').click();await flush();expect(getMusicCatalog).toHaveBeenCalledOnce();expect(deleteMusicMedia).not.toHaveBeenCalled();expect(host.querySelectorAll('.catalog-row')).toHaveLength(2)
    button('确认从本站移除').click();await flush();await flush()
    expect(host.querySelectorAll('.catalog-row')).toHaveLength(2);expect(field.value).toBe('未保存的歌曲名');expect(button('删除封面')).toBeUndefined();expect(host.textContent).toContain('本站移除完成，无本站托管文件需处理');expect(updateMusicTrack).not.toHaveBeenCalled()
})

it('a newer removal preview never blesses an older metadata draft with its version',async()=>{
    const target={kind:'MUSIC_MEDIA',id:'track-1',slot:'COVER'},id='00000000-0000-0000-0000-000000000821'
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaDelete:true,catalogDelete:true,deleteProtocol:'tracked-delete-v1'})
    uploadApi.previewMediaDeletion.mockResolvedValue({target,expectedVersion:'8',candidates:[],referenceCoverageComplete:false,environmentId:'local'})
    uploadApi.submitMediaDeletion.mockResolvedValue({operationId:id,target,originalVersion:'8',committedAt:'2026-10-17T00:00:00Z',localRemovalCommitted:true,processingComplete:true,allFilesDeleted:false,items:[]})
    await mount(status,[{...trackFixture(7),cover:{path:'',url:'https://example.test/old.jpg'}}]);const title=host.querySelector('.track-fields input[maxlength="200"]');title.value='旧版本上的未保存内容';title.dispatchEvent(new Event('input'));await flush()
    button('删除封面').click();await flush();button('确认从本站移除').click();await flush();await flush()
    expect(title.value).toBe('旧版本上的未保存内容');expect(button('保存资料').disabled).toBe(true);button('保存资料').dispatchEvent(new Event('click'));await flush();expect(updateMusicTrack).not.toHaveBeenCalled();expect(host.textContent).toContain('未保存输入已保留')
})

it('a catalogue read begun before removal cannot resurrect the removed song',async()=>{
    const target={kind:'MUSIC_TRACK',id:'track-1',slot:null},id='00000000-0000-0000-0000-000000000822'
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaDelete:true,catalogDelete:true,deleteProtocol:'tracked-delete-v1'})
    uploadApi.previewMediaDeletion.mockResolvedValue({target,expectedVersion:'7',candidates:[],referenceCoverageComplete:false,environmentId:'local'})
    uploadApi.submitMediaDeletion.mockResolvedValue({operationId:id,target,originalVersion:'7',committedAt:'2026-10-17T00:00:00Z',localRemovalCommitted:true,processingComplete:true,allFilesDeleted:false,items:[]})
    await mount(status,[trackFixture(7)]);button('删除歌曲').click();await flush()
    let finish;getMusicCatalog.mockReturnValueOnce(new Promise(resolve=>{finish=resolve}));button('刷新歌单').click();await flush()
    button('确认从本站移除').click();await flush();await flush();expect(host.querySelectorAll('.catalog-row')).toHaveLength(0)
    finish([trackFixture(7)]);await flush();expect(host.querySelectorAll('.catalog-row')).toHaveLength(0)
})
it('creates a local QR, polls authorization, and stops after a successful owner grant', async () => {
    await mount()
    authorizeMusic.mockResolvedValue({
        authorized: false,
        authorizationUrl: 'https://163cn.tv/fixture',
        expiresAt: new Date(Date.now() + 60000).toISOString()
    })
    button('获取授权二维码').click();
    await flush()
    expect(host.querySelector('.authorization img')).not.toBeNull()
    expect(host.querySelector('.authorization a').getAttribute('rel')).toBe('noopener noreferrer')
    getMusicStatus.mockResolvedValue({...status, authorized: true})
    await vi.advanceTimersByTimeAsync(10000);
    await flush()
    expect(host.textContent).toContain('网易云授权成功');
    expect(host.querySelector('.authorization')).toBeNull()
    const count = getMusicStatus.mock.calls.length
    await vi.advanceTimersByTimeAsync(30000);
    expect(getMusicStatus.mock.calls.length).toBe(count)
})
it('rejects a nonofficial authorization URL without rendering or polling it', async () => {
    await mount();
    authorizeMusic.mockResolvedValue({
        authorizationUrl: 'https://evil.test/',
        expiresAt: new Date(Date.now() + 60000).toISOString()
    })
    button('获取授权二维码').click();
    await flush()
    expect(host.textContent).toContain('授权入口无效');
    expect(host.querySelector('.authorization')).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
})
it('stops polling when expired or when the page is unmounted', async () => {
    await mount();
    authorizeMusic.mockResolvedValue({
        authorized: false,
        authorizationUrl: 'https://163cn.tv/fixture',
        expiresAt: new Date(Date.now() + 1000).toISOString()
    })
    button('获取授权二维码').click();
    await flush();
    await vi.advanceTimersByTimeAsync(10000);
    await flush()
    expect(host.textContent).toContain('二维码已过期');
    expect(vi.getTimerCount()).toBe(0)
    authorizeMusic.mockResolvedValue({
        authorized: false,
        authorizationUrl: 'https://163cn.tv/fixture',
        expiresAt: new Date(Date.now() + 60000).toISOString()
    })
    button('获取授权二维码').click();
    await flush();
    app.unmount();
    app = null
    expect(vi.getTimerCount()).toBe(0)
})
it('keeps the synced songs after failure and shows no false playback guarantee', async () => {
    await mount({...status, authorized: true})
    const playlist = {
        playlistId: '939817038',
        name: '博客歌单',
        syncedAt: new Date().toISOString(),
        songs: [{id: '1', name: '<script>歌曲</script>', artist: '歌手', ownerPlayable: true, preview: true}]
    }
    syncMusic.mockResolvedValueOnce(playlist).mockRejectedValueOnce(new Error('同步服务不可用'))
    button('同步歌单').click();
    await flush();
    expect(host.textContent).toContain('本站播放列表仍以已托管音频为准')
    expect(host.textContent).not.toContain('社区服务')
    expect(host.querySelector('script')).toBeNull();
    expect(host.querySelector('tbody').textContent).toContain('试听')
    button('同步歌单').click();
    await flush();
    expect(host.textContent).toContain('同步服务不可用')
    expect(host.querySelector('tbody').textContent).toContain('<script>歌曲</script>')
})
it('requires explicit confirmation before revoking and distinguishes remote uncertainty', async () => {
    await mount({...status, authorized: true})
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    button('解除授权').click();
    await flush();
    expect(revokeMusic).not.toHaveBeenCalled()
    confirm.mockReturnValue(true);
    revokeMusic.mockResolvedValue({authorized: false, remoteLogoutConfirmed: false})
    button('解除授权').click();
    await flush()
    expect(host.textContent).toContain('本站授权已停用');
    expect(button('同步歌单').disabled).toBe(true)
})

it('sends version zero and uses the returned version for the next save', async () => {
    getMediaCapabilities.mockResolvedValue(pgCaps)
    await mount(status, [trackFixture(0)])
    updateMusicTrack.mockResolvedValueOnce({...trackFixture(1)}).mockResolvedValueOnce({...trackFixture(2)})
    button('保存资料').click(); await flush()
    expect(updateMusicTrack).toHaveBeenLastCalledWith('track-1', expect.objectContaining({expectedVersion: 0}))
    button('保存资料').click(); await flush()
    expect(updateMusicTrack).toHaveBeenLastCalledWith('track-1', expect.objectContaining({expectedVersion: 1}))
})
it('preserves an unsaved title on 409 without reload or retry', async () => {
    getMediaCapabilities.mockResolvedValue(pgCaps)
    await mount(status, [trackFixture(7)])
    const title = host.querySelector('.track-fields input[maxlength="200"]')
    title.value = '未保存名称'; title.dispatchEvent(new Event('input'))
    updateMusicTrack.mockRejectedValue(Object.assign(new Error('冲突'), {status: 409}))
    button('保存资料').click(); await flush()
    expect(host.textContent).toContain('资料已变化，请刷新')
    expect(title.value).toBe('未保存名称')
    expect(updateMusicTrack).toHaveBeenCalledOnce(); expect(getMusicCatalog).toHaveBeenCalledOnce()
})
it.each([undefined, null, -1, 1.5, '0', Number.MAX_SAFE_INTEGER + 1])('fails closed for unusable editor version %s', async version => {
    getMediaCapabilities.mockResolvedValue(pgCaps); await mount(status, [trackFixture(version)])
    expect(button('保存资料').disabled).toBe(true)
    button('保存资料').dispatchEvent(new Event('click')); await flush()
    expect(updateMusicTrack).not.toHaveBeenCalled()
})
it.each(['pending', 'rejected', 'malformed', 'disabled'])('blocks media actions when capabilities are %s, including handler bypass', async kind => {
    if (kind === 'pending') getMediaCapabilities.mockReturnValue(new Promise(() => {}))
    else if (kind === 'rejected') getMediaCapabilities.mockRejectedValue(new Error('不可用'))
    else getMediaCapabilities.mockResolvedValue(kind === 'malformed' ? {metadataWrite: true} : pgCaps)
    await mount(status, [trackFixture(0)])
    const input = host.querySelector('input[type="file"][accept="audio/*"]')
    expect(input.disabled).toBe(true)
    Object.defineProperty(input, 'files', {value: [new File(['audio'], 'a.mp3', {type: 'audio/mpeg'})]})
    input.dispatchEvent(new Event('change')); await flush()
    expect(uploadMusicMedia).not.toHaveBeenCalled()
    expect(button('删除歌曲').disabled).toBe(true)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    button('删除歌曲').dispatchEvent(new Event('click')); await flush()
    expect(deleteMusicTrack).not.toHaveBeenCalled()
    button('删除音频').dispatchEvent(new Event('click')); await flush()
    expect(deleteMusicMedia).not.toHaveBeenCalled()
})
it('uses an old payload only when legacy capability explicitly allows it', async () => {
    await mount(status, [trackFixture(undefined)])
    updateMusicTrack.mockResolvedValue(trackFixture(undefined))
    button('保存资料').click(); await flush()
    expect(updateMusicTrack.mock.calls.at(-1)[1]).not.toHaveProperty('expectedVersion')
})
it('gates metadata creation and preserves uncertain create results until explicit refresh', async () => {
    getMediaCapabilities.mockResolvedValue(pgCaps); await mount()
    createMusicTrack.mockRejectedValue(new Error('请求超时'))
    button('新增歌曲').click(); await flush()
    expect(host.textContent).toContain('先刷新核对')
    expect(createMusicTrack).toHaveBeenCalledOnce()
    button('新增歌曲').dispatchEvent(new Event('click')); await flush()
    expect(createMusicTrack).toHaveBeenCalledOnce()
})
it('loads the blog catalogue even when optional official status fails', async () => {
    getMusicStatus.mockRejectedValue(new Error('官方资料暂不可用'))
    getMusicPlaylist.mockResolvedValue(null); getMusicCatalog.mockResolvedValue([trackFixture(0)])
    getMediaCapabilities.mockResolvedValue(pgCaps)
    host = document.createElement('div'); document.body.append(host); app = createApp(MusicManage); app.use(createPinia()); app.mount(host); await flush()
    expect(host.querySelector('.catalog-row')).not.toBeNull()
})
it('does not submit metadata when the explicit capability is read-only', async () => {
    getMediaCapabilities.mockResolvedValue({...pgCaps, metadataWrite: false}); await mount(status, [trackFixture(0)])
    expect(button('保存资料').disabled).toBe(true); expect(button('新增歌曲').disabled).toBe(true)
    button('保存资料').dispatchEvent(new Event('click')); button('新增歌曲').dispatchEvent(new Event('click')); await flush()
    expect(updateMusicTrack).not.toHaveBeenCalled(); expect(createMusicTrack).not.toHaveBeenCalled()
})
it('prevents fresh typing being overwritten while a submitted save is pending', async () => {
    getMediaCapabilities.mockResolvedValue(pgCaps); await mount(status, [trackFixture(0)])
    let finish; updateMusicTrack.mockReturnValue(new Promise(resolve => {finish = resolve}))
    button('保存资料').click(); await flush()
    expect(host.querySelector('.track-fields input[maxlength="200"]').disabled).toBe(true)
    finish(trackFixture(1)); await flush()
    expect(host.querySelector('.track-fields input[maxlength="200"]').disabled).toBe(false)
})
it('uses tracked upload only and does not merge an uploaded-but-uncommitted resource', async () => {
    const id='00000000-0000-0000-0000-000000000611'
    const op=status=>({operationId:id,operationVersion:status==='SUCCEEDED'?'2':'1',targetType:'MUSIC_TRACK',targetId:'track-1',slot:'AUDIO',status,stage:'PERSIST',confirmedBytes:3,totalBytes:3,confirmedChunks:1,totalChunks:1,canPause:false,canResumeContent:status==='READY',canRecover:true,canBind:false,createdAt:'2026-10-14T00:00:00Z',result:status==='SUCCEEDED'?{targetType:'MUSIC_TRACK',video:null,music:{...trackFixture(1),audio:{path:'blog/new.mp3',url:'https://example.test/new.mp3'}}}:null})
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaUpload:true,mediaReplace:true,uploadProtocol:'tracked-v1',uploadLimits:{audioBytes:104857600,coverBytes:10485760,lyricsBytes:524288,videoBytes:209715200,chunkBytes:16000000}})
    vi.stubGlobal('isSecureContext',true);vi.stubGlobal('Worker',class{postMessage(value){queueMicrotask(()=>this.onmessage({data:{requestId:value.requestId,sourceSha256:'a'.repeat(64)}}))}terminate(){}});vi.spyOn(crypto,'randomUUID').mockReturnValue(id)
    uploadApi.createMediaUploadOperation.mockResolvedValue(op('READY'));uploadApi.putMediaUploadContent.mockResolvedValue(op('UPLOADED'));uploadApi.getMediaUploadOperation.mockResolvedValue(op('SUCCEEDED'))
    const row=trackFixture(0);await mount(status,[row]);const input=host.querySelector('input[accept="audio/*"]');Object.defineProperty(input,'files',{value:[new File(['abc'],'a.mp3',{type:'audio/mpeg'})]});input.dispatchEvent(new Event('change'));await flush();await flush()
    expect(uploadApi.createMediaUploadOperation).toHaveBeenCalled();expect(uploadMusicMedia).not.toHaveBeenCalled();expect(row.audio.url).toContain('/blog/music/a.mp3');expect(host.textContent).toContain('登记绑定中')
    const query=[...host.querySelectorAll('button')].find(b=>b.textContent==='重新查询');query.click();await flush();expect(row.audio.url).toBe('https://example.test/new.mp3')
})

it('explicit cover completion uses one tracked identity and merges only committed resource facts', async () => {
    localStorage.clear()
    sessionStorage.clear()
    const id='00000000-0000-0000-0000-000000000711'
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaUpload:true,mediaReplace:true,uploadProtocol:'tracked-v1',uploadLimits:{audioBytes:104857600,coverBytes:10485760,lyricsBytes:524288,videoBytes:209715200,chunkBytes:16000000}})
    vi.stubGlobal('isSecureContext',true); vi.stubGlobal('Worker',class{terminate(){}})
    vi.spyOn(crypto,'randomUUID').mockReturnValue(id)
    let finish
    uploadApi.completeMusicCover.mockReturnValue(new Promise(resolve=>{finish=resolve}))
    const row={...trackFixture(0),sourceId:'29572501'}
    await mount(status,[row])
    expect(button('补全封面').disabled, host.textContent).toBe(false)
    const title=host.querySelector('.track-fields input[maxlength="200"]')
    title.value='未保存标题';title.dispatchEvent(new Event('input'))
    button('补全封面').click();await flush()
    button('补全封面').dispatchEvent(new Event('click'));await flush()
    expect(uploadApi.completeMusicCover).toHaveBeenCalledOnce()
    expect(uploadApi.completeMusicCover).toHaveBeenCalledWith('track-1',{operationId:id,expectedVersion:0})
    finish({operationId:id,operationVersion:'2',targetType:'MUSIC_TRACK',targetId:'track-1',slot:'COVER',status:'SUCCEEDED',stage:'PERSIST',confirmedBytes:3,totalBytes:3,confirmedChunks:1,totalChunks:1,canPause:false,canResumeContent:false,canRecover:false,canBind:false,createdAt:'2026-10-17T00:00:00Z',result:{targetType:'MUSIC_TRACK',video:null,music:{...trackFixture(1),cover:{path:'blog/cover.jpg',url:'https://example.test/file/blog/cover.jpg'}}}})
    await flush()
    expect(title.value).toBe('未保存标题')
    expect(row.cover.path).toBe('blog/cover.jpg')
    expect(row.version).toBe(1)
})
