import {afterEach, describe, expect, it, vi} from 'vitest'
import {createApp, nextTick} from 'vue'

vi.mock('../src/api', () => ({articleDetail: vi.fn(), commentList: vi.fn()}))
import {articleDetail, commentList} from '../src/api'
import InteractionWidget from '../src/components/InteractionWidget.vue'

let app, host
afterEach(() => {
    app?.unmount();
    host?.remove();
    vi.resetAllMocks()
})
describe('Public historical comments', () => {
    it('renders read-only comments without login, write controls or executable HTML', async () => {
        articleDetail.mockResolvedValue({isCommentEnabled: 1, viewCount: 3, likeCount: 2})
        commentList.mockResolvedValue([{id: '1', nickname: '读者', content: '<script>fixture</script>'}])
        host = document.createElement('div');
        document.body.append(host)
        app = createApp(InteractionWidget, {articleId: '19'});
        app.mount(host)
        await new Promise(resolve => setTimeout(resolve, 0));
        await nextTick()
        expect(commentList).toHaveBeenCalledWith('19')
        expect(host.textContent).toContain('历史评论仅供阅读')
        expect(host.querySelector('script')).toBeNull()
        expect(host.querySelector('textarea')).toBeNull()
        expect(host.querySelector('a[href*="login"]')).toBeNull()
    })
})
