import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import ElementPlus from 'element-plus'
vi.mock('../api',()=>({listTags:vi.fn(),saveTag:vi.fn(),deleteTag:vi.fn()}))
import * as api from '../api'
import TagManage from '../views/TagManage.vue'

let app,host
const tags=[{id:'9007199254740993',name:'系统设计',slug:'systems',sort:5,status:1,deleted:0,isHot:1,color:'#123456'},
    {id:'2',name:'Java',slug:'java',sort:0,status:1,deleted:0,isHot:0}]
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
const button=name=>[...host.querySelectorAll('button')].find(node=>node.textContent.trim()===name)
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp(TagManage);app.use(ElementPlus);app.mount(host);await flush()}
beforeEach(()=>{vi.resetAllMocks();vi.stubGlobal('ResizeObserver',class{observe(){}disconnect(){}});api.listTags.mockResolvedValue(tags)})
afterEach(()=>{app?.unmount();host?.remove();vi.unstubAllGlobals()})

it('loads reusable hot flags through ordinary tag data instead of separate homepage settings',async()=>{
    await mount();expect(host.querySelectorAll('.tag-card')).toHaveLength(2)
    expect(host.querySelectorAll('[data-hot-tag]')).toHaveLength(1)
    expect(host.textContent).toContain('热门')
    expect(host.querySelector('input[aria-label="热门标签"]')).not.toBeNull()
})
it('persists an edited hot flag with string identity and preserves unrelated tag properties',async()=>{
    await mount();host.querySelector('.tag-card button').click();await flush()
    const hot=host.querySelector('input[aria-label="热门标签"]')
    expect(hot.checked).toBe(true);hot.click();await flush()
    button('更新').click();await flush()
    expect(api.saveTag).toHaveBeenCalledWith(expect.objectContaining({id:'9007199254740993',name:'系统设计',slug:'systems',isHot:0,sort:5,status:1,color:'#123456'}))
})
it('new tags default to not hot and can be explicitly marked without requiring three selections',async()=>{
    await mount();const name=host.querySelector('input[placeholder="标签名称"]');name.value='新标签';name.dispatchEvent(new Event('input',{bubbles:true}))
    host.querySelector('input[aria-label="热门标签"]').click();await flush()
    button('新增').click();await flush()
    expect(api.saveTag).toHaveBeenCalledWith(expect.objectContaining({name:'新标签',isHot:1}))
})
it('does not clear the draft after a failed save or submit a second request while pending',async()=>{
    let reject;api.saveTag.mockImplementation(()=>new Promise((resolve,r)=>{reject=r}))
    await mount();host.querySelector('.tag-card button').click();await flush();button('更新').click();await flush()
    expect(button('更新').disabled).toBe(true);button('更新').click();await flush();expect(api.saveTag).toHaveBeenCalledTimes(1)
    reject(new Error('offline'));await flush();expect(host.querySelector('input[placeholder="标签名称"]').value).toBe('系统设计')
})

it('keeps load failures distinct from an empty catalogue and blocks stale writes until retry',async()=>{
    api.listTags.mockRejectedValueOnce(new Error('offline'));await mount()
    expect(host.querySelector('[role=alert]').textContent).toContain('offline')
    expect(host.querySelector('.empty')).toBeNull()
    button('重新加载标签').click();await flush();expect(host.querySelectorAll('.tag-card')).toHaveLength(2)
})

it('cancel resets the hot flag rather than carrying it into a new tag',async()=>{
    await mount();host.querySelector('.tag-card button').click();await flush()
    expect(host.querySelector('input[aria-label="热门标签"]').checked).toBe(true)
    button('取消').click();await flush()
    expect(host.querySelector('input[aria-label="热门标签"]').checked).toBe(false)
    expect(host.querySelector('input[placeholder="标签名称"]').value).toBe('')
})

it('does not allow a false-success hot save against the old backend before migration',async()=>{
    api.listTags.mockResolvedValue(tags.map(({isHot,...tag})=>tag));await mount()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('后端')
    const input=host.querySelector('input[placeholder="标签名称"]');input.value='test';input.dispatchEvent(new Event('input',{bubbles:true}));await flush()
    expect(button('新增').disabled).toBe(true);expect(api.saveTag).not.toHaveBeenCalled()
})
