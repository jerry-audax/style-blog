import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {mountHomeTags,unmountHomeTags} from '../src/home/home-tags'

const original=['文章','分类','标签']
const fixture=()=>`<div data-blog-home-tags>${original.map((name,i)=>`<a class="categoryButton color-${i}" href="/blog/${['archives','categories','tags'][i]}/"><span class="categoryButtonText">${name}</span><i data-icon="${i}"></i></a>`).join('')}</div><audio id="music"></audio>`
const selection=()=>({configured:true,tags:['9007199254740993','2','3'].map((id,i)=>({id,name:['DDD','Java','SQL'][i],slug:'not-a-static-path',position:i+1,href:`/blog/tags/?tagId=${id}`}))})
const response=data=>({ok:true,json:async()=>({code:0,data})})
const names=()=>[...document.querySelectorAll('.categoryButtonText')].map(el=>el.textContent)
beforeEach(()=>{vi.restoreAllMocks();history.replaceState({},'', '/blog/');document.body.innerHTML=fixture()})
afterEach(()=>{unmountHomeTags();document.body.replaceChildren();vi.unstubAllGlobals();vi.useRealTimers()})

it('atomically swaps all three labels and exact ID links while preserving classes icons and music',async()=>{
    const fetcher=vi.fn().mockResolvedValue(response(selection()));vi.stubGlobal('fetch',fetcher)
    const nodes=[...document.querySelectorAll('a')],music=document.querySelector('audio')
    await mountHomeTags();expect(names()).toEqual(['DDD','Java','SQL'])
    expect(nodes[0].getAttribute('href')).toBe('/blog/tags/?tagId=9007199254740993')
    expect(nodes.map(n=>n.className)).toEqual(['categoryButton color-0','categoryButton color-1','categoryButton color-2'])
    expect(document.querySelectorAll('i')).toHaveLength(3);expect(document.querySelector('audio')).toBe(music)
    expect(fetcher.mock.calls[0][0]).toBe('/api/content/home-tags')
    expect(fetcher.mock.calls[0][1]).toMatchObject({credentials:'omit',cache:'no-store',redirect:'error'})
    expect(fetcher.mock.calls[0][1].headers).not.toHaveProperty('Authorization')
})

it('keeps all original entries for duplicate unsafe and failed responses',async()=>{
    const bad=selection();bad.tags[2].href='javascript:alert(1)'
    const external=selection();external.tags[2].href='https://other.test/blog/tags/?tagId=3'
    const duplicate=selection();duplicate.tags[2].id='2'
    for(const data of [bad,external,duplicate]){
        unmountHomeTags();document.body.innerHTML=fixture();vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(data)))
        await mountHomeTags();expect(names()).toEqual(original)
    }
    unmountHomeTags();vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:false,status:503}));await mountHomeTags();expect(names()).toEqual(original)
})

it('renders backend fallback tags even when no complete hot selection exists',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response({configured:false,tags:selection().tags})))
    await mountHomeTags();expect(names()).toEqual(['DDD','Java','SQL'])
})

it('shows only available tags for short or empty catalogues and restores original nodes on unmount',async()=>{
    for(const count of [2,0]){
        unmountHomeTags();document.body.innerHTML=fixture()
        vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response({tags:selection().tags.slice(0,count)})))
        await mountHomeTags()
        const links=[...document.querySelectorAll('a')]
        expect(links.filter(link=>link.style.display!=='none')).toHaveLength(count)
        unmountHomeTags();expect(links.every(link=>link.style.display!=='none')).toBe(true);expect(names()).toEqual(original)
    }
})

it('hides theme wrapper slots rather than their links and restores existing display priority',async()=>{
    const group=document.querySelector('[data-blog-home-tags]')
    for(const link of [...group.querySelectorAll('a')]){
        const slot=document.createElement('div');slot.className='categoryItem'
        slot.style.setProperty('display','flex','important');link.before(slot);slot.append(link)
    }
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response({tags:selection().tags.slice(0,1)})))
    await mountHomeTags()
    const slots=[...group.querySelectorAll('.categoryItem')]
    expect(slots.map(slot=>slot.style.display)).toEqual(['flex','none','none'])
    expect([...group.querySelectorAll('a')].every(link=>link.style.display==='')).toBe(true)
    unmountHomeTags()
    expect(slots.map(slot=>slot.style.display)).toEqual(['flex','flex','flex'])
    expect(slots.every(slot=>slot.style.getPropertyPriority('display')==='important')).toBe(true)
    expect(names()).toEqual(original)
})

it('cancels on departure and ignores both late successes and errors after returning',async()=>{
    for(const lateFailure of [false,true]){
        unmountHomeTags();document.body.innerHTML=fixture()
        let resolve,reject;const fetcher=vi.fn().mockImplementationOnce(()=>new Promise((a,b)=>{resolve=a;reject=b})).mockResolvedValue(response(selection()));vi.stubGlobal('fetch',fetcher)
        const old=mountHomeTags();unmountHomeTags();expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true)
        document.body.innerHTML=fixture();await mountHomeTags()
        if(lateFailure)reject(new Error('late failure'));else resolve(response({configured:false,tags:[]}))
        await old;expect(names()).toEqual(['DDD','Java','SQL'])
    }
})

it('deduplicates an active mount and aborts a bounded timeout without changing the defaults',async()=>{
    vi.useFakeTimers();const fetcher=vi.fn().mockImplementation((url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError')))));vi.stubGlobal('fetch',fetcher)
    const pending=mountHomeTags();mountHomeTags();expect(fetcher).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(15000);await pending
    expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);expect(names()).toEqual(original)
})
