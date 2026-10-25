import {afterEach, beforeEach, expect, it, vi} from 'vitest'
import {createApp, nextTick} from 'vue'
import {createPinia} from 'pinia'
import {useAdminAuthStore} from '../stores/auth'
vi.mock('../api/index.js', () => ({getMediaCapabilities: vi.fn(), getSurpriseVideos: vi.fn(), createSurpriseVideo: vi.fn(), updateSurpriseVideo: vi.fn(), deleteSurpriseVideo: vi.fn(),loginByPassword:vi.fn(),me:vi.fn().mockResolvedValue({owner:true,userId:1}),logout:vi.fn(),createMediaUploadOperation:vi.fn(),putMediaUploadContent:vi.fn(),getMediaUploadOperation:vi.fn(),listMediaUploadOperations:vi.fn(),pauseMediaUploadOperation:vi.fn(),recoverMediaUploadOperation:vi.fn(),bindMediaUploadOperation:vi.fn(),previewMediaDeletion:vi.fn(),submitMediaDeletion:vi.fn(),getMediaDeletionOperation:vi.fn(),listMediaDeletionOperations:vi.fn(),confirmMediaDeletionOwnership:vi.fn()}))
import * as uploadApi from '../api/index.js'
import {getMediaCapabilities, getSurpriseVideos, createSurpriseVideo, updateSurpriseVideo, deleteSurpriseVideo} from '../api/index.js'
import SurpriseManage from '../views/SurpriseManage.vue'
const legacyCaps = {metadataWrite: true, metadataVersionRequired: false, mediaUpload: true, mediaReplace: true, mediaDelete: true, catalogDelete: true, uploadProtocol:null, uploadLimits:null}
const pgCaps = {...legacyCaps, metadataVersionRequired: true, mediaUpload: false, mediaReplace: false, mediaDelete: false, catalogDelete: false}
const video = (version = 0, size = null) => ({id: 'video-1', title: '视频', url: 'https://example.test/file/blog/video.mp4', path: 'blog/video.mp4', mediaType: null, size, enabled: true, sortOrder: 0, version})
let app, host
const flush = async () => {for (let n = 0; n < 5; n++) {await Promise.resolve(); await nextTick()}}
const button = text => [...host.querySelectorAll('button')].find(node => node.textContent.trim() === text)
async function mount(rows = [video()]) {getSurpriseVideos.mockResolvedValue(rows); host = document.createElement('div'); document.body.append(host);const pinia=createPinia(); app = createApp(SurpriseManage);app.use(pinia);await useAdminAuthStore(pinia).fetchMe(); app.mount(host); await flush()}
beforeEach(() => {vi.clearAllMocks(); vi.useFakeTimers(); getMediaCapabilities.mockResolvedValue(pgCaps);uploadApi.me.mockResolvedValue({owner:true,userId:1});uploadApi.listMediaUploadOperations.mockResolvedValue({records:[],total:0})})
afterEach(() => {app?.unmount(); host?.remove(); vi.useRealTimers(); vi.restoreAllMocks();vi.unstubAllGlobals()})
it('sends zero then the new returned editor version', async () => {
    await mount(); updateSurpriseVideo.mockResolvedValueOnce(video(1)).mockResolvedValueOnce(video(2))
    button('保存').click(); await flush(); expect(updateSurpriseVideo).toHaveBeenLastCalledWith('video-1', expect.objectContaining({expectedVersion: 0}))
    button('保存').click(); await flush(); expect(updateSurpriseVideo).toHaveBeenLastCalledWith('video-1', expect.objectContaining({expectedVersion: 1}))
})

it('tracked video removal waits for a receipt and keeps the other row draft',async()=>{
    const target={kind:'SURPRISE_VIDEO',id:'video-1',slot:null},id='00000000-0000-0000-0000-000000000812'
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaDelete:true,catalogDelete:true,deleteProtocol:'tracked-delete-v1'})
    uploadApi.listMediaDeletionOperations.mockResolvedValue({records:[],total:0})
    uploadApi.previewMediaDeletion.mockResolvedValue({target,expectedVersion:'0',candidates:[],referenceCoverageComplete:false,environmentId:'local'})
    uploadApi.submitMediaDeletion.mockResolvedValue({operationId:id,target,originalVersion:'0',committedAt:'2026-10-17T00:00:00Z',localRemovalCommitted:true,processingComplete:true,allFilesDeleted:false,items:[]})
    await mount([video(),{...video(),id:'video-2'}]);const title=host.querySelectorAll('.video-fields input[maxlength="120"]')[1];title.value='另一个视频的草稿';title.dispatchEvent(new Event('input'));await flush()
    expect(button('删除').disabled).toBe(false);button('删除').click();await flush();expect(host.querySelectorAll('.video-row')).toHaveLength(2);expect(deleteSurpriseVideo).not.toHaveBeenCalled();expect(button('确认从本站移除')).toBeDefined()
    button('确认从本站移除').click();await flush();await flush();expect(host.querySelectorAll('.video-row')).toHaveLength(1);expect(host.querySelector('.video-fields input[maxlength="120"]').value).toBe('另一个视频的草稿');expect(getSurpriseVideos).toHaveBeenCalledOnce();expect(updateSurpriseVideo).not.toHaveBeenCalled()
})

it('an in-flight old video list cannot resurrect a locally removed video',async()=>{
    const target={kind:'SURPRISE_VIDEO',id:'video-1',slot:null},id='00000000-0000-0000-0000-000000000823'
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaDelete:true,catalogDelete:true,deleteProtocol:'tracked-delete-v1'})
    uploadApi.listMediaDeletionOperations.mockResolvedValue({records:[],total:0})
    uploadApi.previewMediaDeletion.mockResolvedValue({target,expectedVersion:'0',candidates:[],referenceCoverageComplete:false,environmentId:'local'})
    uploadApi.submitMediaDeletion.mockResolvedValue({operationId:id,target,originalVersion:'0',committedAt:'2026-10-17T00:00:00Z',localRemovalCommitted:true,processingComplete:true,allFilesDeleted:false,items:[]})
    await mount([video()]);button('删除').click();await flush()
    let finish;getSurpriseVideos.mockReturnValueOnce(new Promise(resolve=>{finish=resolve}));button('刷新').click();await flush()
    button('确认从本站移除').click();await flush();await flush();expect(host.querySelectorAll('.video-row')).toHaveLength(0)
    finish([video()]);await flush();expect(host.querySelectorAll('.video-row')).toHaveLength(0)
})
it('keeps unsaved fields on conflict without refetching', async () => {
    await mount([video(7)]); const title = host.querySelector('.video-fields input[maxlength="120"]')
    title.value = '未保存名称'; title.dispatchEvent(new Event('input'))
    updateSurpriseVideo.mockRejectedValue(Object.assign(new Error('冲突'), {status: 409}))
    button('保存').click(); await flush(); expect(title.value).toBe('未保存名称'); expect(host.textContent).toContain('资料已变化，请刷新')
    expect(getSurpriseVideos).toHaveBeenCalledOnce(); expect(updateSurpriseVideo).toHaveBeenCalledOnce()
})
it.each([null, -1, 1.5, '0', Number.MAX_SAFE_INTEGER + 1])('blocks invalid version %s', async version => {
    await mount([video(version)]); expect(button('保存').disabled).toBe(true)
    button('保存').dispatchEvent(new Event('click')); await flush(); expect(updateSurpriseVideo).not.toHaveBeenCalled()
})
it.each(['pending', 'rejected', 'malformed', 'disabled'])('fails closed for %s capabilities including file-handler bypass', async kind => {
    if (kind === 'pending') getMediaCapabilities.mockReturnValue(new Promise(() => {}))
    else if (kind === 'rejected') getMediaCapabilities.mockRejectedValue(new Error('不可用'))
    else getMediaCapabilities.mockResolvedValue(kind === 'malformed' ? {mediaUpload: true} : pgCaps)
    await mount(); const input = host.querySelector('.surprise-form input[type="file"]'); expect(input.disabled).toBe(true)
    Object.defineProperty(input, 'files', {value: [new File(['video'], 'a.mp4', {type: 'video/mp4'})]})
    input.dispatchEvent(new Event('change')); await flush(); expect(createSurpriseVideo).not.toHaveBeenCalled()
    expect(button('删除').disabled).toBe(true); vi.spyOn(window, 'confirm').mockReturnValue(true)
    button('删除').dispatchEvent(new Event('click')); await flush(); expect(deleteSurpriseVideo).not.toHaveBeenCalled()
})
it('shows unknown versus actual zero bytes and explains an empty read-only upload catalogue', async () => {
    await mount([video(0, null), {...video(0, 0), id: 'zero'}]); const sizes = [...host.querySelectorAll('.video-fields small')].map(n => n.textContent)
    expect(sizes[0]).toContain('未知'); expect(sizes[1]).toContain('0 B')
    app.unmount(); host.remove(); await mount([])
    expect(host.textContent).toContain('暂无已登记视频，上传功能将在后续开放')
})
it('only explicit legacy capability permits an unversioned save', async () => {
    getMediaCapabilities.mockResolvedValue(legacyCaps); await mount([{...video(), version: undefined}]); updateSurpriseVideo.mockResolvedValue(video())
    button('保存').click(); await flush(); expect(updateSurpriseVideo.mock.calls.at(-1)[1]).not.toHaveProperty('expectedVersion')
})
it('blocks metadata when explicit capability is read-only', async () => {
    getMediaCapabilities.mockResolvedValue({...pgCaps, metadataWrite: false}); await mount()
    expect(button('保存').disabled).toBe(true); button('保存').dispatchEvent(new Event('click')); await flush()
    expect(updateSurpriseVideo).not.toHaveBeenCalled()
})
it('prevents typing into fields during a pending save', async () => {
    await mount(); let finish; updateSurpriseVideo.mockReturnValue(new Promise(resolve => {finish = resolve}))
    button('保存').click(); await flush(); expect(host.querySelector('.video-fields input[maxlength="120"]').disabled).toBe(true)
    finish(video(1)); await flush(); expect(host.querySelector('.video-fields input[maxlength="120"]').disabled).toBe(false)
})
it('does not append a video until tracked upload is SUCCEEDED',async()=>{
    const id='00000000-0000-0000-0000-000000000612',op=status=>({operationId:id,operationVersion:status==='SUCCEEDED'?'2':'1',targetType:'SURPRISE_CREATE',targetId:'new-video',slot:'VIDEO',status,stage:'PERSIST',confirmedBytes:3,totalBytes:3,confirmedChunks:1,totalChunks:1,canPause:false,canResumeContent:status==='READY',canRecover:true,canBind:false,createdAt:'2026-10-14T00:00:00Z',result:status==='SUCCEEDED'?{targetType:'SURPRISE_CREATE',music:null,video:{...video(0),id:'new-video'}}:null})
    getMediaCapabilities.mockResolvedValue({...pgCaps,mediaUpload:true,mediaReplace:true,uploadProtocol:'tracked-v1',uploadLimits:{audioBytes:104857600,coverBytes:10485760,lyricsBytes:524288,videoBytes:209715200,chunkBytes:16000000}})
    vi.stubGlobal('isSecureContext',true);vi.stubGlobal('Worker',class{postMessage(value){queueMicrotask(()=>this.onmessage({data:{requestId:value.requestId,sourceSha256:'a'.repeat(64)}}))}terminate(){}});vi.spyOn(crypto,'randomUUID').mockReturnValue(id)
    uploadApi.createMediaUploadOperation.mockResolvedValue(op('READY'));uploadApi.putMediaUploadContent.mockResolvedValue(op('UPLOADED'));uploadApi.getMediaUploadOperation.mockResolvedValue(op('SUCCEEDED'))
    await mount([]);const input=host.querySelector('.surprise-form input[type=file]');Object.defineProperty(input,'files',{value:[new File(['abc'],'a.mp4',{type:'video/mp4'})]});input.dispatchEvent(new Event('change'));await flush();await flush()
    expect(uploadApi.putMediaUploadContent).toHaveBeenCalled();expect(createSurpriseVideo).not.toHaveBeenCalled();expect(host.querySelectorAll('.video-row')).toHaveLength(0)
    ;[...host.querySelectorAll('button')].find(b=>b.textContent==='重新查询').click();await flush();expect(host.querySelectorAll('.video-row')).toHaveLength(1)
})
