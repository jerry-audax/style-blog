import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,reactive,ref} from 'vue'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus from 'element-plus'
const routing=vi.hoisted(()=>({route:null,push:vi.fn()}))
vi.mock('vue-router',()=>({useRoute:()=>routing.route,useRouter:()=>({push:routing.push})}))
vi.mock('../api/http',()=>({default:{get:vi.fn(),post:vi.fn(),put:vi.fn(),delete:vi.fn()}}))
import http from '../api/http'
import {useAdminAuthStore} from '../stores/auth'
import ImagePicker from '../components/ImagePicker.vue'
import ImageManage from '../views/ImageManage.vue'
import ArticleEdit from '../views/ArticleEdit.vue'
import OwnerAccountView from '../views/OwnerAccountView.vue'
import {trackedImageCapabilities as caps} from './fixtures/mediaCapabilities'

const ASSET='00000000-0000-0000-0000-000000000702'
const image=()=>({assetId:ASSET,assetVersion:'0',fileName:'中文+100%.png',url:'https://example.test/file/blog/new.png',mimeType:'image/png',fileSize:3,status:'ACTIVE',createdAt:'2026-10-18T00:00:00Z',updatedAt:'2026-10-18T00:00:00Z'})
let app,host,auth,pinia,records,pending,mode,history
const flush=async()=>{for(let n=0;n<3;n++){await new Promise(r=>setTimeout(r,0));await nextTick()}}
const button=name=>[...document.querySelectorAll('button')].find(n=>n.textContent.trim()===name)
const op=(id,status='READY')=>({...records.get(id),status,operationVersion:status==='READY'?'0':'3',canResumeContent:status==='READY',canRecover:status==='UNKNOWN',confirmedBytes:status==='SUCCEEDED'?3:0,result:status==='SUCCEEDED'?{targetType:'IMAGE_CREATE',image:image(),music:null,video:null}:null})
async function mount(component,props){host=document.createElement('div');document.body.append(host);app=createApp(component,props);app.use(pinia);app.use(ElementPlus);app.component('RouterLink',{setup:(_,{slots})=>()=>h('a',{},slots.default?.())});app.mount(host);await flush()}
async function select(input,files=[new File(['png'],'图片.png',{type:'image/png'})]){Object.defineProperty(input,'files',{value:files,configurable:true});input.dispatchEvent(new Event('change',{bubbles:true}));await flush()}
beforeEach(()=>{
    vi.resetAllMocks();sessionStorage.clear();localStorage.clear();vi.stubGlobal('isSecureContext',true);vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
    vi.stubGlobal('Worker',vi.fn(function(){this.postMessage=m=>queueMicrotask(()=>this.onmessage?.({data:{requestId:m.requestId,sourceSha256:'a'.repeat(64)}}));this.terminate=vi.fn()}))
    routing.route=reactive({params:{id:'12'}});records=new Map();pending=[];mode='success';history=[]
    pinia=createPinia();setActivePinia(pinia);auth=useAdminAuthStore();auth.initialized=true;auth.verifiedUserId='1';auth.token='fixture-session';auth.user={owner:true,userId:1,nickname:'博主',avatar:'https://example.test/old.png'}
    http.get.mockImplementation((url,options)=>{
        if(url.endsWith('/capabilities'))return Promise.resolve(caps)
        if(url==='/api/admin/media/images')return Promise.resolve({images:[image()],total:'1',offset:options.params.offset,count:options.params.count})
        if(url==='/api/admin/media/uploads')return Promise.resolve({records:history,total:history.length})
        if(url.startsWith('/api/admin/media/uploads/'))return Promise.resolve(op(url.split('/').at(-1),'UNKNOWN'))
        if(url==='/api/content/category/list'||url==='/api/content/tag/list')return Promise.resolve([])
        if(url.startsWith('/api/content/article/'))return Promise.resolve({id:Number(url.split('/').at(-1)),title:'文章'+url.split('/').at(-1),contentHtml:'<p>原正文</p>',coverUrl:'https://example.test/old-cover.png',status:1,tagIds:[]})
        throw new Error('Unexpected GET '+url)
    })
    http.post.mockImplementation((url,payload)=>{
        if(url==='/api/admin/media/uploads'){
            const row={operationId:payload.operationId,operationVersion:'0',targetType:'IMAGE_CREATE',targetId:ASSET,slot:payload.slot,status:'READY',stage:'NONE',confirmedBytes:0,totalBytes:3,confirmedChunks:0,totalChunks:1,canPause:false,pauseRequested:false,canResumeContent:true,canRecover:false,canBind:false,createdAt:'2026-10-18T00:00:00Z',updatedAt:'2026-10-18T00:00:00Z',result:null};records.set(payload.operationId,row);return Promise.resolve(row)
        }
        if(url.endsWith('/recover'))return Promise.resolve(op(url.split('/').at(-2),'SUCCEEDED'))
        if(url==='/api/content/article')return Promise.resolve({id:12})
        return Promise.reject(new Error('Unexpected legacy POST '+url))
    })
    http.put.mockImplementation(url=>{
        const id=url.split('/').at(-2)
        if(url.endsWith('/content'))return mode==='pending'?new Promise(resolve=>pending.push(()=>resolve(op(id,'SUCCEEDED')))):Promise.resolve(op(id,mode==='unknown'?'UNKNOWN':'SUCCEEDED'))
        return Promise.resolve({...auth.user})
    })
})
afterEach(()=>{app?.unmount();host?.remove();document.querySelectorAll('.el-overlay').forEach(n=>n.remove());vi.unstubAllGlobals()})

it('image library reads persisted metadata, labels its boundary and disables postgres delete',async()=>{
    await mount(ImageManage)
    expect(host.textContent).toContain('仅显示已登记图片');expect(host.textContent).toContain('中文+100%.png');expect(host.textContent).toContain(ASSET)
    expect(button('删除图片').disabled).toBe(true);expect(http.get.mock.calls.some(([url])=>url==='/api/images')).toBe(false)
})
it('picker applies a committed image rather than raw upload progress',async()=>{
    mode='pending';const selectImage=vi.fn();await mount(ImagePicker,{modelValue:true,purpose:'cover',onSelect:selectImage})
    await select(document.querySelector('.picker-toolbar input[type=file]'));expect(selectImage).not.toHaveBeenCalled();expect(pending).toHaveLength(1)
    pending[0]();await flush();expect(selectImage).toHaveBeenCalledWith(expect.objectContaining({assetId:ASSET}));expect(http.post.mock.calls.some(([url])=>url.startsWith('/api/images/'))).toBe(false)
})
it('a rejected upload leaves an already loaded library selectable without reuploading',async()=>{
    const selected=vi.fn();await mount(ImagePicker,{modelValue:true,purpose:'cover',onSelect:selected})
    http.put.mockRejectedValue(Object.assign(new Error('HASH_MISMATCH'),{status:422}));await select(document.querySelector('.picker-toolbar input[type=file]'))
    expect(document.querySelector('.picker-image')).not.toBeNull();document.querySelector('.picker-image').click();await flush()
    expect(selected).toHaveBeenCalledOnce();expect(http.post.mock.calls.filter(([url])=>url==='/api/admin/media/uploads')).toHaveLength(1)
})
it.each(['closed','purpose','logout','unmount'])('picker late success cannot select after %s',async(change)=>{
    mode='pending';const open=ref(true),purpose=ref('article'),selected=vi.fn()
    await mount({setup:()=>()=>h(ImagePicker,{modelValue:open.value,purpose:purpose.value,onSelect:selected})})
    await select(document.querySelector('.picker-toolbar input[type=file]'));expect(pending).toHaveLength(1)
    if(change==='closed')open.value=false;if(change==='purpose')purpose.value='cover';if(change==='logout')auth.clear();if(change==='unmount')app.unmount()
    await flush();pending[0]();await flush();expect(selected).not.toHaveBeenCalled()
})
it('real Tiptap body and cover only change their own drafts without saving',async()=>{
    await mount(ArticleEdit);await select(host.querySelector('.toolbar input[type=file]'));await flush()
    expect(host.querySelector('.tiptap img').getAttribute('src')).toBe(image().url);expect(host.querySelector('#article-cover').value).toBe('https://example.test/old-cover.png')
    await select(host.querySelector('.authoring-sidebar input[type=file]'));expect(host.querySelector('#article-cover').value).toBe(image().url)
    expect(http.post.mock.calls.filter(([url])=>url==='/api/content/article')).toEqual([])
})
it('switching articles while upload is in flight cannot insert into the new document',async()=>{
    mode='pending';await mount(ArticleEdit);await select(host.querySelector('.toolbar input[type=file]'));expect(pending).toHaveLength(1)
    routing.route.params.id='13';await flush();expect(host.querySelector('#article-title').value).toBe('文章13')
    pending[0]();await flush();expect(host.querySelector('.tiptap img')).toBeNull();expect(http.post.mock.calls.filter(([url])=>url==='/api/content/article')).toEqual([])
})
it('a newer manual cover selection supersedes an older upload response',async()=>{
    mode='pending';await mount(ArticleEdit);await select(host.querySelector('.authoring-sidebar input[type=file]'));expect(pending).toHaveLength(1)
    const cover=host.querySelector('#article-cover');cover.value='https://example.test/newer-choice.png';cover.dispatchEvent(new Event('input',{bubbles:true}));await flush();pending[0]();await flush()
    expect(cover.value).toBe('https://example.test/newer-choice.png')
})
it('body batch stops at UNKNOWN and never auto-submits remaining files',async()=>{
    mode='unknown';await mount(ArticleEdit);await select(host.querySelector('.toolbar input[type=file]'),[new File(['png'],'1.png',{type:'image/png'}),new File(['png'],'2.png',{type:'image/png'})])
    expect(http.post.mock.calls.filter(([url])=>url==='/api/admin/media/uploads')).toHaveLength(1);expect(host.querySelector('.tiptap img')).toBeNull();expect(host.textContent).toContain('原编号')
})
it('avatar uses tracked AVATAR slot and leaves profile unchanged until explicit save',async()=>{
    await mount(OwnerAccountView);await select(host.querySelector('#profile-avatar'))
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe(image().url);expect(auth.user.avatar).toBe('https://example.test/old.png')
    expect(http.post.mock.calls.find(([url])=>url==='/api/admin/media/uploads')[1]).toEqual(expect.objectContaining({slot:'AVATAR',imagePurpose:'AVATAR'}))
    expect(http.put.mock.calls.filter(([url])=>url==='/api/auth/profile')).toHaveLength(0)
})
it('avatar success after account switch cannot overwrite the new owner draft',async()=>{
    mode='pending';await mount(OwnerAccountView);await select(host.querySelector('#profile-avatar'));expect(pending).toHaveLength(1)
    auth.clear();auth.user={owner:true,userId:2,avatar:'https://example.test/other.png'};auth.initialized=true;auth.verifiedUserId='2';await flush();pending[0]();await flush()
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('https://example.test/other.png');expect(http.put.mock.calls.filter(([url])=>url==='/api/auth/profile')).toHaveLength(0)
})
it('late profile save containing an avatar cannot replace a newer authenticated owner',async()=>{
    await mount(OwnerAccountView);let finish;http.put.mockReturnValue(new Promise(r=>{finish=r}));button('保存修改').click();await flush()
    auth.clear();auth.user={owner:true,userId:2,avatar:'https://example.test/other.png'};auth.initialized=true;auth.verifiedUserId='2';await flush()
    finish({owner:true,userId:1,avatar:'https://example.test/old-result.png'});await flush();expect(auth.user.userId).toBe(2)
})
it('a profile save started before avatar recovery preserves the recovered unsaved draft',async()=>{
    mode='unknown';await mount(OwnerAccountView);await select(host.querySelector('#profile-avatar'))
    const original=http.put.getMockImplementation();let finish
    http.put.mockImplementation((url,...args)=>url==='/api/auth/profile'?new Promise(resolve=>{finish=resolve}):original(url,...args))
    button('保存修改').click();await flush()
    expect(http.put.mock.calls.find(([url])=>url==='/api/auth/profile')[1]).not.toHaveProperty('avatar')
    button('核对并恢复').click();await flush();expect(host.querySelector('.avatar-img').getAttribute('src')).toBe(image().url)
    finish({...auth.user});await flush()
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe(image().url)
    expect(host.textContent).toContain('新的头像尚未保存')
    expect(auth.user.avatar).toBe('https://example.test/old.png')
    http.put.mockImplementation((url,payload)=>Promise.resolve({...auth.user,...payload}))
    button('保存修改').click();await flush()
    expect(http.put.mock.calls.filter(([url])=>url==='/api/auth/profile').at(-1)[1].avatar).toBe(image().url)
    expect(auth.user.avatar).toBe(image().url)
    expect(http.post.mock.calls.filter(([url])=>url==='/api/admin/media/uploads')).toHaveLength(1)
    expect(http.put.mock.calls.filter(([url])=>url.endsWith('/content'))).toHaveLength(1)
})
it.each(['UNKNOWN','SUCCEEDED'])('a refreshed avatar page explicitly adopts a %s history result without reuploading',async(status)=>{
    const id='00000000-0000-0000-0000-000000000703'
    records.set(id,{operationId:id,operationVersion:'3',targetType:'IMAGE_CREATE',targetId:ASSET,slot:'AVATAR',totalBytes:3,confirmedBytes:0,canRecover:true,canResumeContent:false})
    history=[op(id,status)];await mount(OwnerAccountView)
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('https://example.test/old.png')
    if(status==='UNKNOWN'){button('核对并恢复').click();await flush()}
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('https://example.test/old.png')
    expect(button('使用此头像')).toBeTruthy();button('使用此头像').click();await flush()
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe(image().url)
    expect(auth.user.avatar).toBe('https://example.test/old.png')
    expect(http.put).not.toHaveBeenCalled()
    http.put.mockImplementation((url,payload)=>Promise.resolve({...auth.user,...payload}))
    button('保存修改').click();await flush();expect(auth.user.avatar).toBe(image().url)
    expect(http.post.mock.calls.filter(([url])=>url==='/api/admin/media/uploads')).toHaveLength(0)
    expect(http.put.mock.calls.filter(([url])=>url.endsWith('/content'))).toHaveLength(0)
})
it.each(['slot','url','identity','mixed'])('avatar adoption rejects a history result with invalid %s',async(kind)=>{
    const id='00000000-0000-0000-0000-000000000703'
    records.set(id,{operationId:id,operationVersion:'3',targetType:'IMAGE_CREATE',targetId:ASSET,slot:'AVATAR',totalBytes:3,confirmedBytes:3})
    const row=op(id,'SUCCEEDED')
    if(kind==='slot')row.slot='IMAGE'
    if(kind==='url')row.result.image.url='javascript:alert(1)'
    if(kind==='identity')row.result.image.assetId=id
    if(kind==='mixed')row.result.music={id:'unexpected'}
    history=[row];await mount(OwnerAccountView)
    expect(button('使用此头像')).toBeUndefined()
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('https://example.test/old.png')
    expect(http.put).not.toHaveBeenCalled();expect(http.post).not.toHaveBeenCalled()
})
it('a detached avatar adoption action cannot apply a result after the session changes',async()=>{
    const id='00000000-0000-0000-0000-000000000703'
    records.set(id,{operationId:id,operationVersion:'3',targetType:'IMAGE_CREATE',targetId:ASSET,slot:'AVATAR',totalBytes:3,confirmedBytes:3})
    history=[op(id,'SUCCEEDED')];await mount(OwnerAccountView);const previousButton=button('使用此头像')
    expect(previousButton).toBeTruthy();history=[];auth.token='new-session';await flush()
    previousButton.click();await flush()
    expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('https://example.test/old.png')
    expect(http.put).not.toHaveBeenCalled();expect(http.post).not.toHaveBeenCalled()
})
it.each(['success','failure'])('image library ignores a previous-session delayed %s while loading the new session',async(outcome)=>{
    const original=http.get.getMockImplementation();let finish,fail,first=true
    http.get.mockImplementation((url,options)=>{
        if(url==='/api/admin/media/images' && first){first=false;return new Promise((resolve,reject)=>{finish=resolve;fail=reject})}
        if(url==='/api/admin/media/images')return Promise.resolve({images:[{...image(),fileName:'新会话.png'}],total:'1',offset:0,count:20})
        return original(url,options)
    })
    await mount(ImageManage);auth.token='new-session';await flush();expect(http.get.mock.calls.filter(([url])=>url==='/api/admin/media/images')).toHaveLength(2)
    if(outcome==='success')finish({images:[{...image(),fileName:'旧会话.png'}],total:'1',offset:0,count:20});else fail(new Error('stale-session-error'))
    await flush();expect(host.textContent).toContain('新会话.png');expect(host.textContent).not.toContain('旧会话.png');expect(host.textContent).not.toContain('stale-session-error')
})
