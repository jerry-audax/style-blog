import {afterEach, beforeEach, expect, it, vi} from 'vitest'
import {createApp, h, nextTick, reactive} from 'vue'
import {useMediaUpload} from '../composables/useMediaUpload'
import MediaUploadPanel from '../components/MediaUploadPanel.vue'
import {rememberUpload,readTrackedUploads} from '../media/uploadTracking'
vi.mock('../api/index.js', () => ({createMediaUploadOperation: vi.fn(),putMediaUploadContent:vi.fn(),getMediaUploadOperation:vi.fn(),listMediaUploadOperations:vi.fn(),pauseMediaUploadOperation:vi.fn(),recoverMediaUploadOperation:vi.fn(),bindMediaUploadOperation:vi.fn()}))
import * as api from '../api/index.js'
const ID='00000000-0000-0000-0000-000000000601'
const caps={metadataWrite:true,metadataVersionRequired:true,mediaUpload:true,mediaReplace:true,mediaDelete:false,catalogDelete:false,uploadProtocol:'tracked-v1',uploadLimits:{audioBytes:104857600,coverBytes:10485760,lyricsBytes:524288,videoBytes:209715200,chunkBytes:16000000}}
const target={targetType:'MUSIC_TRACK',targetId:'song',slot:'AUDIO',expectedVersion:0}
const operation=(status='UPLOADING',extra={})=>({operationId:ID,operationVersion:'9007199254740993',targetType:'MUSIC_TRACK',targetId:'song',slot:'AUDIO',status,stage:status==='UNKNOWN'?'DIRECT':'CHUNK',confirmedBytes:0,totalBytes:3,confirmedChunks:0,totalChunks:1,canPause:status==='UPLOADING',pauseRequested:false,canResumeContent:status==='PAUSED'||status==='READY',canRecover:status==='UNKNOWN'||status==='UPLOADING',canBind:status==='CONFLICT',safeErrorCode:null,createdAt:'2026-10-14T00:00:00Z',updatedAt:'2026-10-14T00:00:00Z',result:null,...extra})
let app,host,state,auth,succeeded,worker,activeCaps
const flush=async()=>{for(let n=0;n<12;n++){await Promise.resolve();await nextTick()}}
async function mount(verified=true){
    auth=reactive({initialized:verified,user:verified?{owner:true,userId:1}:null,verifiedUserId:verified?'1':'',clear:vi.fn()})
    succeeded=vi.fn();host=document.createElement('div');document.body.append(host)
    app=createApp({setup(){state=useMediaUpload({auth,capabilities:()=>activeCaps,targetType:'MUSIC_TRACK',onSucceeded:succeeded});return()=>h(MediaUploadPanel,{uploader:state,loadVersion:async()=>0})}});app.mount(host);await flush()
}
const file=()=>new File(['abc'],'song.mp3',{type:'audio/mpeg'})
beforeEach(()=>{
    vi.resetAllMocks();vi.useFakeTimers();sessionStorage.clear();vi.stubGlobal('isSecureContext',true)
    activeCaps=structuredClone(caps)
    worker={terminate:vi.fn(),postMessage:vi.fn(message=>queueMicrotask(()=>worker.onmessage?.({data:{requestId:message.requestId,sourceSha256:'a'.repeat(64)}})))}
    vi.stubGlobal('Worker',vi.fn(function(){return worker}));vi.spyOn(crypto,'randomUUID').mockReturnValue(ID)
    api.listMediaUploadOperations.mockResolvedValue({records:[],total:0,offset:0,limit:20});api.createMediaUploadOperation.mockResolvedValue(operation('READY'));api.getMediaUploadOperation.mockResolvedValue(operation());
})
afterEach(()=>{app?.unmount();host?.remove();vi.useRealTimers();vi.restoreAllMocks();vi.unstubAllGlobals()})
it('restoreWaitsForVerifiedOwner',async()=>{await mount(false);await state.restore();expect(api.listMediaUploadOperations).not.toHaveBeenCalled();auth.user={owner:true,userId:1};auth.initialized=true;auth.verifiedUserId='1';await flush();await state.restore();expect(api.listMediaUploadOperations).toHaveBeenCalled()})
it('capability revocation during preprocessing cannot submit an intent',async()=>{
    await mount();worker.postMessage=vi.fn();const pending=state.start(target,file());await flush();const message=worker.postMessage.mock.calls[0][0]
    activeCaps={...caps,uploadProtocol:'unknown'};worker.onmessage({data:{requestId:message.requestId,sourceSha256:'a'.repeat(64)}});await pending
    expect(api.createMediaUploadOperation).not.toHaveBeenCalled();expect(api.putMediaUploadContent).not.toHaveBeenCalled()
})
it('onlySucceededUpdatesCatalog and browser progress is not confirmed bytes',async()=>{
    await mount();let finish;api.putMediaUploadContent.mockImplementation((id,file,options)=>{options.onUploadProgress({loaded:3});return new Promise(resolve=>{finish=resolve})})
    const pending=state.start(target,file());await flush();expect(api.createMediaUploadOperation).toHaveBeenCalledWith(expect.objectContaining({operationId:ID,expectedVersion:0,sourceSha256:'a'.repeat(64)}));expect(state.browserBytes.value).toBe(3);expect(state.operations.value[0].confirmedBytes).toBe(0);expect(succeeded).not.toHaveBeenCalled()
    const result={targetType:'MUSIC_TRACK',music:{id:'song',version:1,title:'server'},video:null};api.getMediaUploadOperation.mockResolvedValue(operation('SUCCEEDED',{operationVersion:'9007199254740994',result,canPause:false,confirmedBytes:3}));await vi.advanceTimersByTimeAsync(2000);await flush();expect(succeeded).toHaveBeenCalledOnce()
    finish(operation('UPLOADED'));await pending;await flush();expect(state.operations.value[0].status).toBe('SUCCEEDED');expect(succeeded).toHaveBeenCalledOnce()
})
it('unknownRefreshQueriesOriginalIdWithoutPut',async()=>{
    rememberUpload('1',{operationId:ID,targetType:'MUSIC_TRACK',targetId:'song',slot:'AUDIO',createdAt:'2026-10-14T00:00:00Z'});api.getMediaUploadOperation.mockResolvedValue(operation('UNKNOWN'));await mount();await state.restore();await flush();expect(api.getMediaUploadOperation).toHaveBeenCalledWith(ID);expect(api.putMediaUploadContent).not.toHaveBeenCalled();expect(api.createMediaUploadOperation).not.toHaveBeenCalled();expect(host.textContent).toContain(ID)
})
it('pausePendingIsNotPausedAndResumeRechecksFile',async()=>{
    api.listMediaUploadOperations.mockResolvedValue({records:[operation()],total:1});await mount();await state.restore();api.pauseMediaUploadOperation.mockResolvedValue(operation('UPLOADING',{pauseRequested:true}));await state.pause(state.operations.value[0]);expect(api.pauseMediaUploadOperation).toHaveBeenCalledWith(ID,'9007199254740993');expect(state.operations.value[0].status).toBe('UPLOADING');expect(host.textContent).toContain('请求暂停')
    api.getMediaUploadOperation.mockResolvedValue(operation('PAUSED',{operationVersion:'9007199254740994',canPause:false}));await state.query(state.operations.value[0]);api.putMediaUploadContent.mockResolvedValue(operation('PAUSED',{operationVersion:'9007199254740995',canPause:false}));await state.resume(state.operations.value[0],file());expect(worker.postMessage).toHaveBeenCalled();expect(api.putMediaUploadContent).toHaveBeenCalledOnce();expect(api.createMediaUploadOperation).not.toHaveBeenCalled()
})
it('accountSwitchAndUnmountCannotMergeLateSuccess',async()=>{
    await mount();let finish;api.putMediaUploadContent.mockReturnValue(new Promise(resolve=>{finish=resolve}));const pending=state.start(target,file());await flush();auth.verifiedUserId='';auth.user=null;auth.initialized=true;await flush();finish(operation('SUCCEEDED',{result:{targetType:'MUSIC_TRACK',music:{id:'song',version:1},video:null}}));await pending;expect(succeeded).not.toHaveBeenCalled();expect(state.operations.value).toEqual([])
    app.unmount();await vi.advanceTimersByTimeAsync(10000);expect(vi.getTimerCount()).toBe(0)
})
it('conflictPreservesDraftUntilExplicitBind',async()=>{
    await mount();api.putMediaUploadContent.mockRejectedValue(Object.assign(new Error('conflict'),{status:409}));await state.start(target,file());expect(succeeded).not.toHaveBeenCalled();expect(api.bindMediaUploadOperation).not.toHaveBeenCalled();expect(state.error.value).toContain('核对')
    const child='00000000-0000-0000-0000-000000000602';vi.mocked(crypto.randomUUID).mockReturnValueOnce(child)
    api.getMediaUploadOperation.mockResolvedValue(operation('CONFLICT',{stage:'PERSIST',canPause:false}));await state.query(state.operations.value[0]);api.bindMediaUploadOperation.mockResolvedValue(operation('SUCCEEDED',{operationId:child,result:{targetType:'MUSIC_TRACK',music:{id:'song',version:1},video:null},canBind:false}));await state.bind(state.operations.value[0],0);expect(api.bindMediaUploadOperation).toHaveBeenCalledWith(ID,{newOperationId:child,expectedVersion:0,operationVersion:'9007199254740993'});expect(succeeded).toHaveBeenCalledOnce()
})
it('query failure stops polling without changing durable status or issuing another content call',async()=>{
    await mount();api.putMediaUploadContent.mockResolvedValue(operation());await state.start(target,file());api.getMediaUploadOperation.mockRejectedValue(new Error('offline'));await vi.advanceTimersByTimeAsync(2000);await flush();const calls=api.getMediaUploadOperation.mock.calls.length;await vi.advanceTimersByTimeAsync(6000);expect(api.getMediaUploadOperation).toHaveBeenCalledTimes(calls);expect(state.operations.value[0].status).toBe('UPLOADING');expect(api.putMediaUploadContent).toHaveBeenCalledOnce()
})
it('explicit missing-registration query releases a rejected intent and removes only its hint',async()=>{
    await mount();const other='00000000-0000-0000-0000-000000000603';rememberUpload('1',{operationId:other,targetType:'MUSIC_TRACK',targetId:'other-song',slot:'AUDIO',createdAt:'2026-10-14T00:00:00Z'})
    api.createMediaUploadOperation.mockRejectedValue(Object.assign(new Error('stale version'),{status:409}));await state.start(target,file())
    expect(state.isTargetBusy('song')).toBe(true);expect(api.putMediaUploadContent).not.toHaveBeenCalled()
    api.getMediaUploadOperation.mockRejectedValue(Object.assign(new Error('not registered'),{status:404}));await state.query(state.operations.value[0])
    expect(state.isTargetBusy('song')).toBe(false);expect(state.operations.value).toEqual([]);expect(readTrackedUploads('1').map(row=>row.operationId)).toEqual([other])
    vi.mocked(crypto.randomUUID).mockReturnValueOnce('00000000-0000-0000-0000-000000000604');await state.start({...target,expectedVersion:1},file())
    expect(api.createMediaUploadOperation).toHaveBeenCalledTimes(2);expect(api.putMediaUploadContent).not.toHaveBeenCalled()
})
it('missing-registration response cannot release an intent while its create is still in flight',async()=>{
    await mount();let create;api.createMediaUploadOperation.mockReturnValue(new Promise(resolve=>{create=resolve}));const pending=state.start(target,file());await flush()
    api.getMediaUploadOperation.mockRejectedValue(Object.assign(new Error('not yet registered'),{status:404}));await state.query(state.operations.value[0]);expect(state.isTargetBusy('song')).toBe(true)
    api.putMediaUploadContent.mockResolvedValue(operation('UNKNOWN'));create(operation('READY'));await pending;expect(state.operations.value[0].status).toBe('UNKNOWN')
})
