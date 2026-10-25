import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,reactive} from 'vue'
import {useMediaDeletion} from '../composables/useMediaDeletion'
import MediaDeletionPanel from '../components/MediaDeletionPanel.vue'
vi.mock('../api/index.js',()=>({previewMediaDeletion:vi.fn(),submitMediaDeletion:vi.fn(),getMediaDeletionOperation:vi.fn(),listMediaDeletionOperations:vi.fn(),confirmMediaDeletionOwnership:vi.fn()}))
import * as api from '../api/index.js'
const ID='00000000-0000-0000-0000-000000000801',ASSET='00000000-0000-0000-0000-000000000802'
const target={kind:'MUSIC_MEDIA',id:'song',slot:'COVER'},other={kind:'MUSIC_TRACK',id:'other',slot:null}
const caps={metadataWrite:true,metadataVersionRequired:true,mediaUpload:false,mediaReplace:false,mediaDelete:true,catalogDelete:true,deleteProtocol:'tracked-delete-v1'}
const preview=()=>({target:{...target},expectedVersion:'9007199254740993',referenceCoverageComplete:false,environmentId:'local',candidates:[{assetId:ASSET,kind:'IMAGE',assetVersion:'9007199254740993',deletionScope:'UNVERIFIED',exclusiveEnvironment:null,status:'RETAINED',reason:'OWNERSHIP_UNVERIFIED'}]})
const receipt=(extra={})=>({operationId:ID,target:{...target},originalVersion:'9007199254740993',committedAt:'2026-10-17T00:00:00Z',localRemovalCommitted:true,processingComplete:true,allFilesDeleted:false,items:[{id:ID,assetId:ASSET,kind:'IMAGE',assetVersion:'9007199254740993',status:'RETAINED',reason:'OWNERSHIP_UNVERIFIED'}],...extra})
const flush=async()=>{for(let n=0;n<10;n++){await Promise.resolve();await nextTick()}}
let app,host,state,auth,committed,activeCaps
async function mount(owner=true){auth=reactive({initialized:owner,user:owner?{owner:true,userId:1}:null,verifiedUserId:owner?'1':'',token:'session-1',clear:vi.fn()});committed=vi.fn();host=document.createElement('div');document.body.append(host);app=createApp({setup(){state=useMediaDeletion({auth,capabilities:()=>activeCaps,onCommitted:committed});return()=>h(MediaDeletionPanel,{deleter:state})}});app.mount(host);await flush()}
beforeEach(()=>{vi.resetAllMocks();activeCaps={...caps};vi.spyOn(crypto,'randomUUID').mockReturnValue(ID);api.previewMediaDeletion.mockResolvedValue(preview());api.submitMediaDeletion.mockResolvedValue(receipt());api.listMediaDeletionOperations.mockResolvedValue({records:[],total:0})})
afterEach(()=>{app?.unmount();host?.remove();vi.restoreAllMocks()})
it('preview then cancellation never commits or mutates rows',async()=>{await mount();await state.prepare(target);expect(host.textContent).toContain('本站移除不等于图床删除');expect(state.preview.value.expectedVersion).toBe('9007199254740993');state.cancel();await state.submit();expect(api.submitMediaDeletion).not.toHaveBeenCalled();expect(committed).not.toHaveBeenCalled()})
it('double click submits once with exact decimal strings and updates only a committed result',async()=>{
    await mount();await state.prepare(target);let finish;api.submitMediaDeletion.mockReturnValue(new Promise(resolve=>{finish=resolve}));const request=state.submit();await state.submit();expect(api.submitMediaDeletion).toHaveBeenCalledOnce();expect(committed).not.toHaveBeenCalled();
    const [payload,key,options]=api.submitMediaDeletion.mock.calls[0];expect(payload).toEqual({target,expectedVersion:'9007199254740993',assetVersions:{[ASSET]:'9007199254740993'}});expect(key).toBe(ID);expect(options.signal).toBeInstanceOf(AbortSignal);
    finish(receipt());await request;await flush();expect(committed).toHaveBeenCalledOnce();expect(host.textContent).toContain('已从本站移除，图床文件保留');expect(host.textContent).toContain('归属尚未确认');expect(host.textContent).not.toContain('图床文件已删除')
})
it('unknown response reuses the original key and immutable payload',async()=>{
    await mount();await state.prepare(target);api.submitMediaDeletion.mockRejectedValueOnce(new Error('network-private'));await state.submit();expect(state.error.value).toContain('原幂等键');expect(host.textContent).not.toContain('network-private');
    await state.submit();const calls=api.submitMediaDeletion.mock.calls;expect(calls[0][0]).toEqual(calls[1][0]);expect(calls[0][1]).toBe(calls[1][1]);expect(crypto.randomUUID).toHaveBeenCalledOnce();expect(committed).toHaveBeenCalledOnce()
})
it('409 requires a fresh preview and never refreshes drafts',async()=>{
    await mount();await state.prepare(target);api.submitMediaDeletion.mockRejectedValueOnce({status:409});await state.submit();expect(state.requiresPreview.value).toBe(true);expect(state.error.value).toContain('未保存输入已保留');await state.submit();expect(api.submitMediaDeletion).toHaveBeenCalledOnce();expect(committed).not.toHaveBeenCalled();await state.prepare(target);expect(state.requiresPreview.value).toBe(false)
})
it('empty-file receipt is not described as all files deleted',async()=>{await mount();api.previewMediaDeletion.mockResolvedValue({...preview(),candidates:[]});api.submitMediaDeletion.mockResolvedValue(receipt({items:[]}));await state.prepare(target);await state.submit();await flush();expect(host.textContent).toContain('本站移除完成，无本站托管文件需处理');expect(state.operation.value.allFilesDeleted).toBe(false)})
it('disabled submissions can restore terminal server history without polling',async()=>{activeCaps={...caps,mediaDelete:false,catalogDelete:false};api.listMediaDeletionOperations.mockResolvedValue({records:[receipt()],total:1});await mount();await state.restore();await flush();expect(host.textContent).toContain(ID);await state.prepare(target);expect(api.previewMediaDeletion).not.toHaveBeenCalled();expect(api.submitMediaDeletion).not.toHaveBeenCalled()})
it.each(['logout','same-owner-new-session','target','unmount'])('ignores late submit after %s and aborts its request',async mode=>{
    await mount();await state.prepare(target);let finish;api.submitMediaDeletion.mockReturnValue(new Promise(resolve=>{finish=resolve}));const request=state.submit();const signal=api.submitMediaDeletion.mock.calls[0][2].signal;
    if(mode==='logout'){auth.verifiedUserId='';auth.user=null}else if(mode==='same-owner-new-session')auth.token='session-2';else if(mode==='target'){api.previewMediaDeletion.mockResolvedValue({...preview(),target:other});await state.prepare(other)}else app.unmount();
    expect(signal.aborted).toBe(true);finish(receipt());await request;expect(committed).not.toHaveBeenCalled();expect(state.operation.value).toBeNull()
})
it('out-of-order previews cannot replace the selected target',async()=>{await mount();let finish;api.previewMediaDeletion.mockReturnValueOnce(new Promise(resolve=>{finish=resolve}));const pending=state.prepare(target);api.previewMediaDeletion.mockResolvedValue({...preview(),target:other});await state.prepare(other);finish(preview());await pending;expect(state.preview.value.target).toEqual(other)})
it('requires verified owner and no capability fallback enables a write',async()=>{await mount(false);await state.restore();await state.prepare(target);expect(api.listMediaDeletionOperations).not.toHaveBeenCalled();expect(api.previewMediaDeletion).not.toHaveBeenCalled();auth.initialized=true;auth.user={owner:true,userId:1};auth.verifiedUserId='1';activeCaps={...caps,deleteProtocol:null};await state.prepare(target);expect(api.previewMediaDeletion).not.toHaveBeenCalled()})
it('ownership change invalidates the preview and requires explicit cross-environment acknowledgement',async()=>{
    await mount();await state.prepare(target);await flush();const button=[...host.querySelectorAll('button')].find(b=>b.textContent.trim()==='确认独占归属');expect(button.disabled).toBe(true);
    const note=host.querySelector('input[aria-label="独占确认依据"]');note.value='已核对跨环境使用';note.dispatchEvent(new Event('input'));await flush();expect(button.disabled).toBe(true);
    const check=host.querySelector('input[type="checkbox"]');check.checked=true;check.dispatchEvent(new Event('change'));await flush();expect(button.disabled).toBe(false);
    api.confirmMediaDeletionOwnership.mockResolvedValue({assetId:ASSET,assetVersion:'9007199254740994',deletionScope:'EXCLUSIVE',exclusiveEnvironment:'local'});button.click();await flush();expect(api.confirmMediaDeletionOwnership).toHaveBeenCalledWith(ASSET,{scope:'EXCLUSIVE',expectedVersion:'9007199254740993',note:'已核对跨环境使用'},expect.any(Object));expect(state.requiresPreview.value).toBe(true);await state.submit();expect(api.submitMediaDeletion).not.toHaveBeenCalled()
})
it('malformed or mismatched receipt cannot remove a row',async()=>{await mount();await state.prepare(target);api.submitMediaDeletion.mockResolvedValue(receipt({target:other}));await state.submit();expect(committed).not.toHaveBeenCalled();expect(state.operation.value).toBeNull();expect(state.error.value).toContain('原幂等键')})
