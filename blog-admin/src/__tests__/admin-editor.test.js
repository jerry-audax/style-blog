import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {createApp, nextTick} from 'vue'
import ElementPlus, {ElMessage} from 'element-plus'
import {createPinia,setActivePinia} from 'pinia'
import {useAdminAuthStore} from '../stores/auth'
import {legacyImageCapabilities} from './fixtures/mediaCapabilities'
const routing = vi.hoisted(() => ({route: {params: {}}, push: vi.fn()}))
vi.mock('vue-router', () => ({useRoute: () => routing.route, useRouter: () => ({push: routing.push})}))
vi.mock('../api', () => ({getArticle: vi.fn(), listCategories: vi.fn(), listTags: vi.fn(), saveArticle: vi.fn(), listImages: vi.fn(), uploadImage: vi.fn(), getMediaCapabilities:vi.fn()}))
import {getArticle, listCategories, listTags, saveArticle, listImages, uploadImage,getMediaCapabilities} from '../api'
import ArticleEdit from '../views/ArticleEdit.vue'

let app, host
const flush = async () => {await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()}
const button = name => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === name)
const mount = async () => {
    host = document.createElement('div'); document.body.append(host)
    const pinia=createPinia();setActivePinia(pinia);const auth=useAdminAuthStore();auth.user={owner:true,userId:1};auth.initialized=true;auth.verifiedUserId='1'
    app = createApp(ArticleEdit); app.use(pinia);app.use(ElementPlus); app.mount(host); await flush()
}
beforeEach(() => {
    vi.resetAllMocks(); routing.route.params = {}
    localStorage.clear()
    getMediaCapabilities.mockResolvedValue(legacyImageCapabilities)
    vi.stubGlobal('ResizeObserver', class {observe() {} unobserve() {} disconnect() {}})
    listCategories.mockResolvedValue([{id: 3, name: '系统设计'}]); listTags.mockResolvedValue([{id: 4, name: '架构'}])
    getArticle.mockResolvedValue({id: 12, title: '旧文章', contentHtml: '<p>已有 HTML 正文</p>', contentMd: '不得覆盖的旧 Markdown', summary: '卡片摘要', coverUrl: 'https://img.example.test/file/old.png', categoryId: 3, tagIds: [4], status: 1, isTop: 1, isCommentEnabled: 1})
    listImages.mockResolvedValue({images: [{path: 'blog/covers/test.png', url: 'https://img.example.test/file/blog/covers/test.png'}], total: 1})
    saveArticle.mockResolvedValue({id: 12})
})
afterEach(() => {app?.unmount(); host?.remove(); vi.unstubAllGlobals()})

describe('admin authoring with the real Tiptap editor', () => {
    it.each([409, 503])('keeps the article draft and session after resource protection HTTP %s without retrying', async status => {
        localStorage.setItem('admin_token', 'reference-fixture')
        routing.route.params = {id: '12'}
        const message = status === 409 ? '资源引用已变化或尚未登记，请先核对资源后重试' : '资源引用保护暂不可用，请稍后重试'
        saveArticle.mockRejectedValueOnce(Object.assign(new Error(message), {status}))
        await mount()
        const title = host.querySelector('#article-title')
        title.value = '尚未保存的新标题'
        title.dispatchEvent(new Event('input', {bubbles: true}))
        await flush()
        const success = vi.spyOn(ElMessage, 'success')
        try {
            button('保存发布状态').click(); await flush()
            expect(saveArticle).toHaveBeenCalledOnce()
            expect(routing.push).not.toHaveBeenCalled()
            expect(success).not.toHaveBeenCalled()
            expect(host.querySelector('#article-title').value).toBe('尚未保存的新标题')
            expect(host.querySelector('.tiptap').textContent).toBe('已有 HTML 正文')
            expect(host.querySelector('#article-cover').value).toBe('https://img.example.test/file/old.png')
            expect(host.querySelector('.article-card-preview').textContent).toContain('卡片摘要')
            expect(host.querySelector('.meta-tags .el-tag').textContent).toContain('架构')
            expect(localStorage.getItem('admin_token')).toBe('reference-fixture')
            expect(useAdminAuthStore().token).toBe('reference-fixture')
            expect(uploadImage).not.toHaveBeenCalled()
            expect(button('保存发布状态').disabled).toBe(false)
            // Only a second deliberate click retries the same draft; no implicit upload or save.
            button('保存发布状态').click(); await flush()
            expect(saveArticle).toHaveBeenCalledTimes(2)
            expect(saveArticle.mock.calls[1][0]).toEqual(expect.objectContaining({title: '尚未保存的新标题', summary: '卡片摘要', tagIds: ['4']}))
            expect(routing.push).toHaveBeenCalledWith('/articles')
        } finally { success.mockRestore() }
    })
    it('matches legacy numeric article tagIds against string options and actually removes the binding', async () => {
        routing.route.params = {id: '12'}
        listTags.mockResolvedValue([{id: '4', name: '架构'}])
        await mount()
        const selected = host.querySelector('.meta-tags .el-tag')
        expect(selected.textContent).toContain('架构')
        selected.querySelector('.el-tag__close').click(); await flush()
        expect(host.querySelector('.meta-tags .el-tag')).toBeNull()
        button('保存发布状态').click(); await flush()
        expect(saveArticle.mock.calls[0][0].tagIds).toEqual([])
    })
    it('loads existing HTML and preserves metadata when saving a published article', async () => {
        routing.route.params = {id: '12'}; await mount()
        expect(getArticle).toHaveBeenCalledWith(12)
        expect(host.querySelector('#article-title').value).toBe('旧文章')
        expect(host.querySelector('.tiptap').textContent).toBe('已有 HTML 正文')
        expect(host.querySelector('.article-card-preview').textContent).toContain('卡片摘要')
        button('保存发布状态').click(); await flush()
        expect(saveArticle).toHaveBeenCalledWith(expect.objectContaining({id: 12, title: '旧文章', contentMd: '<p>已有 HTML 正文</p>', categoryId: 3, tagIds: ['4'], status: 1, isTop: 1, coverUrl: 'https://img.example.test/file/old.png'}))
        expect(routing.push).toHaveBeenCalledWith('/articles')
    })
    it('starts new articles as drafts and prevents an empty submission', async () => {
        await mount()
        expect(getArticle).not.toHaveBeenCalled()
        expect(button('保存草稿')).toBeDefined()
        button('保存草稿').click(); await flush()
        expect(saveArticle).not.toHaveBeenCalled()
        expect(routing.push).not.toHaveBeenCalled()
    })
    it('blocks saving after a load failure and recovers on retry without losing the stored body', async () => {
        routing.route.params = {id: '12'}; getArticle.mockRejectedValueOnce(new Error('读取失败')); await mount()
        expect(host.querySelector('[role=alert]').textContent).toContain('读取失败')
        expect(button('保存草稿').disabled).toBe(true)
        button('重新加载').click(); await flush()
        expect(host.querySelector('.tiptap').textContent).toBe('已有 HTML 正文')
        expect(button('保存发布状态').disabled).toBe(false)
    })
    it('reuses a cover from the image library without uploading or saving automatically', async () => {
        routing.route.params = {id: '12'}; await mount()
        button('从图片库选择').click(); await flush()
        document.querySelector('.picker-image').click(); await flush()
        expect(host.querySelector('#article-cover').value).toBe('https://img.example.test/file/blog/covers/test.png')
        expect(host.querySelector('.article-card-preview > img').getAttribute('src')).toBe('https://img.example.test/file/blog/covers/test.png')
        expect(uploadImage).not.toHaveBeenCalled(); expect(saveArticle).not.toHaveBeenCalled()
    })
    it('inserts a reused body image into HTML and preserves the article cover', async () => {
        routing.route.params = {id: '12'}; await mount()
        button('图片库').click(); await flush()
        document.querySelector('.picker-image').click(); await flush()
        button('保存发布状态').click(); await flush()
        const payload = saveArticle.mock.calls[0][0]
        expect(payload.contentMd).toContain('已有 HTML 正文')
        expect(payload.contentMd).toContain('src="https://img.example.test/file/blog/covers/test.png"')
        expect(payload.coverUrl).toBe('https://img.example.test/file/old.png')
        expect(uploadImage).not.toHaveBeenCalled()
    })
})
