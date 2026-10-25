import {afterEach,expect,it,vi} from 'vitest'
import {nextTick} from 'vue'
import {mountInteractions,unmountInteractions} from '../src/interaction-widget'

const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
const markup='<div data-blog-tag-articles><a href="/blog/tags/Original/">原标签云</a></div><audio id="persistent-music"></audio>'
afterEach(()=>{unmountInteractions();document.body.replaceChildren();vi.unstubAllGlobals();history.replaceState({},'','/')})

it('keeps plain tag indexes unchanged and mounts exact-ID pages only once per PJAX entry',async()=>{
    const fetcher=vi.fn().mockImplementation(url=>Promise.resolve({ok:true,json:async()=>({code:0,data:url.startsWith('/api/content/tag/list')?[{id:'9007199254740993',name:'精确标签',status:1,deleted:0}]:{records:[],total:0,size:10,current:1,pages:0}})}))
    vi.stubGlobal('fetch',fetcher);document.body.innerHTML=markup;history.replaceState({},'','/blog/tags/')
    document.dispatchEvent(new Event('pjax:complete'));await flush();expect(fetcher).not.toHaveBeenCalled();expect(document.body.textContent).toContain('原标签云')
    history.replaceState({},'','/blog/about/?tagId=9007199254740993');mountInteractions();await flush();expect(fetcher).not.toHaveBeenCalled()
    history.replaceState({},'','/blog/tags/?tagId=9007199254740993')
    const audio=document.querySelector('audio');document.dispatchEvent(new Event('pjax:complete'));mountInteractions();await flush()
    expect(fetcher).toHaveBeenCalledTimes(2);expect(document.body.textContent).toContain('精确标签')
    expect(document.querySelector('audio')).toBe(audio)
    document.dispatchEvent(new Event('pjax:send'));expect(document.body.textContent).toContain('原标签云')
    document.dispatchEvent(new Event('pjax:complete'));await flush();expect(fetcher).toHaveBeenCalledTimes(4)
    expect(document.querySelector('audio')).toBe(audio)
})

it('restores homepage selection and tag articles after a BFCache pageshow without touching the player',async()=>{
    const tags=['1','2','3'].map((id,index)=>({id,name:`标签${id}`,position:index+1,href:`/blog/tags/?tagId=${id}`,status:1,deleted:0}))
    const fetcher=vi.fn().mockImplementation(url=>Promise.resolve({ok:true,json:async()=>({code:0,data:url==='/api/content/home-tags'?{configured:true,tags}:url==='/api/content/tag/list'?tags:{records:[],total:0,size:10,current:1,pages:0}})}))
    vi.stubGlobal('fetch',fetcher);history.replaceState({},'','/blog/tags/?tagId=1')
    document.body.innerHTML=`<div data-blog-home-tags>${tags.map((t,i)=>`<a class="categoryButton" href="/blog/tags/"><span class="categoryButtonText">默认${i}</span></a>`).join('')}</div>`+markup
    const audio=document.querySelector('audio'),pause=vi.spyOn(audio,'pause').mockImplementation(()=>{})
    mountInteractions();await flush();expect(document.body.textContent).toContain('该标签下暂无已发布文章')
    window.dispatchEvent(new Event('pagehide'));expect(document.body.textContent).toContain('默认0');expect(document.body.textContent).toContain('原标签云')
    const event=new Event('pageshow');Object.defineProperty(event,'persisted',{value:true});window.dispatchEvent(event);await flush()
    expect(document.querySelector('.categoryButtonText').textContent).toBe('标签1')
    expect(document.body.textContent).toContain('该标签下暂无已发布文章')
    expect(document.querySelector('audio')).toBe(audio);expect(pause).not.toHaveBeenCalled();expect(fetcher).toHaveBeenCalledTimes(6)
    window.dispatchEvent(event);await flush();expect(fetcher).toHaveBeenCalledTimes(6)
})
