import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,reactive,ref} from 'vue'
import {useImageUpload} from '../composables/useImageUpload'
import MediaUploadPanel from '../components/MediaUploadPanel.vue'
import {readTrackedUploads} from '../media/uploadTracking'
import {trackedImageCapabilities as caps} from './fixtures/mediaCapabilities'
vi.mock('../api/index.js',()=>({getMediaCapabilities:vi.fn(),uploadImage:vi.fn(),createMediaUploadOperation:vi.fn(),putMediaUploadContent:vi.fn(),getMediaUploadOperation:vi.fn(),listMediaUploadOperations:vi.fn(),recoverMediaUploadOperation:vi.fn(),pauseMediaUploadOperation:vi.fn()}))
import * as api from '../api/index.js'
const ID='00000000-0000-0000-0000-000000000701',ASSET='00000000-0000-0000-0000-000000000702'
const image=()=>({assetId:ASSET,assetVersion:'0',fileName:'图片.png',url:'https://example.test/file/blog/image.png',mimeType:'image/png',fileSize:3,status:'ACTIVE',createdAt:'2026-10-18T00:00:00Z',updatedAt:'2026-10-18T00:00:00Z'})
const operation=(status='READY',extra={})=>({operationId:ID,operationVersion:'0',targetType:'IMAGE_CREATE',targetId:ASSET,slot:'IMAGE',status,stage:status==='READY'?'NONE':'PERSIST',confirmedBytes:status==='SUCCEEDED'?3:0,totalBytes:3,confirmedChunks:status==='SUCCEEDED'?1:0,totalChunks:1,canPause:false,pauseRequested:false,canResumeContent:status==='READY',canRecover:['UNKNOWN','UPLOADED'].includes(status),canBind:false,createdAt:'2026-10-18T00:00:00Z',updatedAt:'2026-10-18T00:00:00Z',result:status==='SUCCEEDED'?{targetType:'IMAGE_CREATE',image:image(),music:null,video:null}:null,...extra})
let app,host,state,auth,purpose,succeeded,worker
const flush=async()=>{for(let n=0;n<20;n++){await Promise.resolve();await nextTick()}}
async function mount(){
    auth=reactive({initialized:true,verifiedUserId:'1',user:{owner:true,userId:1},token:'session',clear:vi.fn()});purpose=ref('article');succeeded=vi.fn()
    host=document.createElement('div');document.body.append(host)
    app=createApp({setup(){state=useImageUpload({auth,purpose:()=>purpose.value,onSucceeded:succeeded});return()=>h(MediaUploadPanel,{uploader:state.uploader})}})
    app.mount(host);await flush();await state.loadCapabilities()
}
const file=()=>new File(['png'],'图片.png',{type:'image/png'})
beforeEach(()=>{
    vi.resetAllMocks();vi.useFakeTimers();sessionStorage.clear();vi.stubGlobal('isSecureContext',true)
    worker={terminate:vi.fn(),postMessage:vi.fn(m=>queueMicrotask(()=>worker.onmessage?.({data:{requestId:m.requestId,sourceSha256:'a'.repeat(64)}})))}
    vi.stubGlobal('Worker',vi.fn(function(){return worker}));vi.spyOn(crypto,'randomUUID').mockReturnValue(ID)
    api.getMediaCapabilities.mockResolvedValue(caps);api.listMediaUploadOperations.mockResolvedValue({records:[],total:0});api.createMediaUploadOperation.mockResolvedValue(operation());api.putMediaUploadContent.mockResolvedValue(operation('SUCCEEDED'))
})
afterEach(()=>{app?.unmount();host?.remove();vi.useRealTimers();vi.restoreAllMocks();vi.unstubAllGlobals()})
it('uploads only through a tracked image intent and applies committed image once',async()=>{
    await mount();await state.start(file());expect(api.createMediaUploadOperation).toHaveBeenCalledWith(expect.objectContaining({operationId:ID,targetType:'IMAGE_CREATE',slot:'IMAGE',imagePurpose:'ARTICLE',sourceSha256:'a'.repeat(64)}))
    expect(api.uploadImage).not.toHaveBeenCalled();expect(succeeded).toHaveBeenCalledOnce();expect(succeeded.mock.calls[0][0]).toEqual(image())
    expect(host.textContent).toContain(ID);expect(host.textContent).not.toContain('请求暂停')
    const hint=readTrackedUploads('1')[0];expect(Object.keys(hint).sort()).toEqual(['createdAt','operationId','slot','targetId','targetType'].sort());expect(JSON.stringify(hint)).not.toContain('https:')
})
it('does not fall back to legacy upload for missing, unknown or disabled image capabilities',async()=>{
    api.getMediaCapabilities.mockResolvedValue({});await mount();await state.start(file());expect(api.createMediaUploadOperation).not.toHaveBeenCalled();expect(api.uploadImage).not.toHaveBeenCalled();expect(state.error.value).toContain('版本')
})
it('invalidating an editor draft does not discard a valid capability response for the same session',async()=>{
    await mount();let finish;api.getMediaCapabilities.mockReturnValue(new Promise(r=>{finish=r}));auth.token='renewed-session';await flush()
    state.invalidate();finish(caps);await flush();expect(state.imageCapabilities.value).toEqual(caps.images)
})
it('avatar uses its own slot and rejects 2 MiB plus one and forged declaration before hashing',async()=>{
    await mount();purpose.value='avatar';await flush()
    await state.start(new File([new Uint8Array(2097153)],'a.png',{type:'image/png'}));await state.start(new File(['bad'],'a.svg',{type:'image/svg+xml'}))
    expect(api.createMediaUploadOperation).not.toHaveBeenCalled();expect(worker.postMessage).not.toHaveBeenCalled()
    api.createMediaUploadOperation.mockResolvedValue(operation('READY',{slot:'AVATAR'}));api.putMediaUploadContent.mockResolvedValue(operation('SUCCEEDED',{slot:'AVATAR'}))
    await state.start(file());expect(api.createMediaUploadOperation).toHaveBeenCalledWith(expect.objectContaining({slot:'AVATAR',imagePurpose:'AVATAR'}))
})
it('UNKNOWN recovers the original ID without another create or content upload',async()=>{
    await mount();api.putMediaUploadContent.mockResolvedValue(operation('UNKNOWN',{operationVersion:'3'}));await state.start(file());expect(succeeded).not.toHaveBeenCalled()
    api.recoverMediaUploadOperation.mockResolvedValue(operation('SUCCEEDED',{operationVersion:'4'}));await state.uploader.recover(state.uploader.operations.value[0])
    expect(api.recoverMediaUploadOperation).toHaveBeenCalledWith(ID,'3');expect(api.createMediaUploadOperation).toHaveBeenCalledOnce();expect(api.putMediaUploadContent).toHaveBeenCalledOnce();expect(succeeded).toHaveBeenCalledOnce();expect(state.error.value).toBe('')
})
it.each(['purpose','invalidate','logout','session','unmount'])('late success cannot apply after %s change',async(change)=>{
    await mount();let finish;api.putMediaUploadContent.mockReturnValue(new Promise(r=>{finish=r}));const sending=state.start(file());await flush()
    if(change==='purpose')purpose.value='cover';if(change==='invalidate')state.invalidate();if(change==='logout'){auth.verifiedUserId='';auth.user=null}if(change==='session')auth.token='new-session';if(change==='unmount')app.unmount()
    await flush();finish(operation('SUCCEEDED'));await sending;expect(succeeded).not.toHaveBeenCalled()
})
it.each(['url','mixed','identity','hash'])('malformed or rejected %s success never changes the draft',async(kind)=>{
    await mount();const result={targetType:'IMAGE_CREATE',image:image(),music:null,video:null}
    if(kind==='url')result.image.url='javascript:alert(1)';if(kind==='mixed')result.video={};if(kind==='identity')result.image.assetId=ID
    if(kind==='hash')api.putMediaUploadContent.mockRejectedValue(Object.assign(new Error('HASH_MISMATCH'),{status:422}));else api.putMediaUploadContent.mockResolvedValue(operation('SUCCEEDED',{result}))
    await state.start(file());expect(succeeded).not.toHaveBeenCalled();expect(api.createMediaUploadOperation).toHaveBeenCalledOnce()
})
it('history recovery from a closed editor refreshes durable state without applying to a new draft',async()=>{
    api.listMediaUploadOperations.mockResolvedValue({records:[operation('UNKNOWN',{operationVersion:'3'})],total:1});await mount()
    api.recoverMediaUploadOperation.mockResolvedValue(operation('SUCCEEDED',{operationVersion:'4'}));await state.uploader.recover(state.uploader.operations.value[0]);expect(succeeded).not.toHaveBeenCalled();expect(api.createMediaUploadOperation).not.toHaveBeenCalled()
})
it('explicit legacy remains available and late legacy response obeys the same generation guard',async()=>{
    api.getMediaCapabilities.mockResolvedValue({images:{...caps.images,storage:'legacy-json',uploadProtocol:'legacy',deleteEnabled:true}});await mount()
    let finish;api.uploadImage.mockReturnValue(new Promise(r=>{finish=r}));const task=state.start(file());await flush();state.invalidate();finish({url:'https://example.test/a.png'});await task
    expect(succeeded).not.toHaveBeenCalled();expect(api.uploadImage).toHaveBeenCalledOnce();expect(api.createMediaUploadOperation).not.toHaveBeenCalled()
})
it('automatic foreground polling is finite and never creates a replacement operation',async()=>{
    await mount();api.putMediaUploadContent.mockResolvedValue(operation('UPLOADING'));api.getMediaUploadOperation.mockResolvedValue(operation('UPLOADING'));await state.start(file())
    await vi.advanceTimersByTimeAsync(120000);await flush();const calls=api.getMediaUploadOperation.mock.calls.length;expect(calls).toBeGreaterThan(0);expect(calls).toBeLessThanOrEqual(30)
    await vi.advanceTimersByTimeAsync(120000);expect(api.getMediaUploadOperation).toHaveBeenCalledTimes(calls);expect(api.createMediaUploadOperation).toHaveBeenCalledOnce()
})
