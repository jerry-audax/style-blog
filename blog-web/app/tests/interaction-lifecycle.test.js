import {afterEach, expect, it, vi} from 'vitest'
import {nextTick} from 'vue'

vi.mock('../src/api', () => ({articleDetail: vi.fn(), commentList: vi.fn()}))
import {articleDetail, commentList} from '../src/api'
import {mountInteractions, unmountInteractions} from '../src/interaction-widget'

afterEach(() => {
    unmountInteractions();
    document.body.replaceChildren();
    vi.resetAllMocks()
})
it('mounts after PJAX, unmounts before replacement and never mounts twice', async () => {
    articleDetail.mockResolvedValue({isCommentEnabled: 1, viewCount: 3})
    commentList.mockResolvedValue([])
    document.body.innerHTML = '<section data-blog-interactions="19"></section>'
    document.dispatchEvent(new Event('pjax:complete'))
    document.dispatchEvent(new Event('pjax:complete'))
    mountInteractions()
    await new Promise(resolve => setTimeout(resolve, 0));
    await nextTick()
    expect(articleDetail).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('交流与讨论')
    document.dispatchEvent(new Event('pjax:send'))
    expect(document.querySelector('section').textContent).toBe('')
    document.body.innerHTML = '<section data-blog-interactions="20"></section>'
    document.dispatchEvent(new Event('pjax:complete'))
    await new Promise(resolve => setTimeout(resolve, 0));
    await nextTick()
    expect(articleDetail).toHaveBeenLastCalledWith('20')
    expect(articleDetail).toHaveBeenCalledTimes(2)
})
