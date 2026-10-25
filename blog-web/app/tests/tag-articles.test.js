import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import TagArticleList from '../src/components/TagArticleList.vue'
import {visibleSummary} from '../src/content/summary'

const id='9007199254740993'
let app,host,fetcher
const response=data=>({ok:true,json:async()=>({code:0,data})})
const tag={id,name:'数据库设计',slug:'not-the-page-path',status:1,deleted:0}
const article=(id,summary)=>({id,title:`文章${id}`,status:1,deleted:0,summary,contentHtml:'BODY_NEVER_SUMMARY',coverUrl:'https://images.test/cover.png'})
const page=(records,total=records.length,current=1)=>({records,total,size:10,current,pages:Math.ceil(total/10)})
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
async function mount(tagId=id){host=document.createElement('div');document.body.append(host);app=createApp(TagArticleList,{tagId});app.mount(host);await flush()}
const button=name=>[...host.querySelectorAll('button')].find(el=>el.textContent.trim()===name)
beforeEach(()=>{history.replaceState({},'',`/blog/tags/?tagId=${id}`);fetcher=vi.fn().mockImplementation(url=>Promise.resolve(response(url.startsWith('/api/content/tag/list')?[tag]:page([article('10','<p>明确摘要</p>'),article('11','<p>&nbsp;<br></p>'),article('12',null)]))));vi.stubGlobal('fetch',fetcher)})
afterEach(()=>{app?.unmount();host?.remove();vi.unstubAllGlobals()})

it('normalizes only visible supplied summaries without body fallback',()=>{
    for(const value of [undefined,null,'',' \n\t','<p><br></p>','<p>&nbsp;</p>','<script>alert(1)</script>','\u200b\ufeff'])expect(visibleSummary(value)).toBe('')
    expect(visibleSummary('<p>摘要内容 &amp; 安全</p>')).toBe('摘要内容 & 安全')
    expect(visibleSummary('&lt;img src=x&gt;')).toBe('<img src=x>')
})

it('loads published articles by exact tag ID and only renders visible summaries',async()=>{
    await mount();expect(host.textContent).toContain('数据库设计');expect(host.querySelectorAll('.tag-article-summary')).toHaveLength(1)
    expect(host.textContent).toContain('明确摘要');expect(host.textContent).not.toContain('BODY_NEVER_SUMMARY')
    expect(host.querySelector('.tag-article-title').getAttribute('href')).toBe('/blog/article/10/')
    const call=fetcher.mock.calls.find(([url])=>url.startsWith('/api/content/article/list'))
    const url=new URL(call[0],location.origin)
    expect(Object.fromEntries(url.searchParams)).toEqual({tagId:id,status:'1',pageNum:'1',pageSize:'10'})
    for(const [,options] of fetcher.mock.calls){expect(options.credentials).toBe('omit');expect(options.headers).not.toHaveProperty('Authorization')}
})

it('supports bounded pagination and keeps zero posts a valid empty state',async()=>{
    fetcher.mockImplementation(url=>Promise.resolve(response(url.startsWith('/api/content/tag/list')?[tag]:url.includes('pageNum=2')?page([article('21','')],11,2):page([article('10','')],11))))
    await mount();button('下一页').click();await flush();expect(host.textContent).toContain('文章21')
    expect(button('下一页').disabled).toBe(true);button('上一页').click();await flush();expect(host.textContent).toContain('文章10')
    app.unmount();host.remove();fetcher.mockImplementation(url=>Promise.resolve(response(url.startsWith('/api/content/tag/list')?[tag]:page([]))))
    await mount();expect(host.textContent).toContain('该标签下暂无已发布文章');expect(host.querySelector('.tag-article-summary')).toBeNull()
})

it('does not fetch invalid IDs and does not query articles for missing or disabled tags',async()=>{
    await mount('01');expect(fetcher).not.toHaveBeenCalled();expect(host.textContent).toContain('标签不存在或已停用')
    app.unmount();host.remove();fetcher.mockResolvedValue(response([{...tag,status:0}]))
    await mount();expect(fetcher).toHaveBeenCalledTimes(1);expect(host.textContent).toContain('标签不存在或已停用')
})

it('renders a retryable failure without displaying drafts or unsafe media URLs',async()=>{
    fetcher.mockRejectedValueOnce(new Error('offline'));await mount();expect(host.textContent).toContain('暂时无法加载')
    button('重试').click();await flush();expect(host.textContent).toContain('文章10')
    app.unmount();host.remove();fetcher.mockImplementation(url=>Promise.resolve(response(url.startsWith('/api/content/tag/list')?[tag]:page([{...article('20',''),status:0}]))))
    await mount();expect(host.textContent).not.toContain('文章20');expect(host.textContent).toContain('暂时无法加载')
})

it('aborts work on unmount and ignores a late page response',async()=>{
    let resolve;fetcher.mockImplementationOnce(()=>Promise.resolve(response([tag]))).mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
    await mount();const signal=fetcher.mock.calls[1][1].signal;app.unmount();expect(signal.aborted).toBe(true)
    resolve(response(page([article('88','late')])));await flush();expect(host.textContent).not.toContain('late')
})

it('renders escaped summary text and refuses executable cover URLs',async()=>{
    fetcher.mockImplementation(url=>Promise.resolve(response(url.startsWith('/api/content/tag/list')?[tag]:page([{...article('20','&lt;img src=x onerror=alert(1)&gt;'),coverUrl:'javascript:alert(1)'}]))))
    await mount();expect(host.querySelector('.tag-article-summary').textContent).toBe('<img src=x onerror=alert(1)>')
    expect(host.querySelector('img')).toBeNull()
})
