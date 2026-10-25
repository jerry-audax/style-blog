import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {createApp, h, nextTick, ref} from 'vue'
import ElementPlus from 'element-plus'
import {createPinia,setActivePinia} from 'pinia'
import {useAdminAuthStore} from '../stores/auth'
import {legacyImageCapabilities} from './fixtures/mediaCapabilities'
vi.mock('../api', () => ({getDashboard: vi.fn(), listArticles: vi.fn(), deleteArticle: vi.fn(), listImages: vi.fn(), uploadImage: vi.fn(),getMediaCapabilities:vi.fn()}))
import {getDashboard, listArticles, listImages, uploadImage,getMediaCapabilities} from '../api'
import Dashboard from '../views/Dashboard.vue'
import ArticleManage from '../views/ArticleManage.vue'
import ImagePicker from '../components/ImagePicker.vue'

let app, host
const flush = async () => {await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()}
const button = name => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === name)
const mount = async (component, props = {}) => {
    host = document.createElement('div'); document.body.append(host)
    const pinia=createPinia();setActivePinia(pinia);const auth=useAdminAuthStore();auth.user={owner:true,userId:1};auth.initialized=true;auth.verifiedUserId='1'
    app = createApp(component, props);app.use(pinia); app.use(ElementPlus)
    app.component('RouterLink', {props: ['to'], setup: (props, {slots}) => () => h('a', {href: props.to}, slots.default?.())})
    app.config.globalProperties.$router = {push: vi.fn()}
    app.mount(host); await flush()
}
beforeEach(() => {
    vi.resetAllMocks()
    getMediaCapabilities.mockResolvedValue(legacyImageCapabilities)
    vi.stubGlobal('ResizeObserver', class {observe() {} unobserve() {} disconnect() {}})
    getDashboard.mockResolvedValue({totalArticles: 7, publishedArticles: 5, totalComments: 3, totalUsers: 20, recentArticles: [{id: 12, title: '真实测试文章', status: 1, viewCount: 8}]})
    listArticles.mockResolvedValue({records: [{id: 12, title: '测试文章', status: 1, tagNames: ['测试'], summary: '真实摘要'}, {id: 13, title: '草稿', status: 0}], total: 2, pages: 1})
    listImages.mockResolvedValue({images: [{path: 'blog/covers/test.png', url: 'https://img.example.test/file/blog/covers/test.png'}], total: 1})
})
afterEach(() => {app?.unmount(); host?.remove(); vi.unstubAllGlobals()})

describe('blog-focused admin content', () => {
    it('defers article-list covers and decodes asynchronously without changing their source', async () => {
        listArticles.mockResolvedValue({records:[{id:12,title:'封面',status:1,coverUrl:'https://images.test/cover.webp'}],total:1,pages:1})
        await mount(ArticleManage)
        const cover=host.querySelector('.cell-cover')
        expect(cover.getAttribute('loading')).toBe('lazy')
        expect(cover.getAttribute('decoding')).toBe('async')
        expect(cover.getAttribute('src')).toBe('https://images.test/cover.webp')
    })
    it('shows real published/draft counts and removes registration metrics', async () => {
        await mount(Dashboard)
        expect(host.textContent).toContain('已发布文章5')
        expect(host.textContent).toContain('草稿文章2')
        expect(host.textContent).not.toContain('注册用户')
        expect(host.querySelector('.recent-item').getAttribute('href')).toBe('/article/12/edit')
    })
    it('does not render successful-looking counters after a dashboard failure and allows retry', async () => {
        getDashboard.mockRejectedValueOnce(new Error('offline'))
        await mount(Dashboard)
        expect(host.querySelector('.stat-grid')).toBeNull()
        expect(host.textContent).toContain('无法加载博客概览')
        button('重新加载').click(); await flush()
        expect(host.querySelector('.stat-grid')).not.toBeNull()
    })
    it('shows public links only for published articles', async () => {
        await mount(ArticleManage)
        expect(host.querySelectorAll('.public-article-link')).toHaveLength(1)
        expect(host.querySelector('.public-article-link').getAttribute('href')).toContain('/blog/article/12/')
    })
    it('searches only the current page with an explicit label', async () => {
        await mount(ArticleManage)
        const input = host.querySelector('input[aria-label="筛选本页标题"]')
        input.value = '草稿'; input.dispatchEvent(new Event('input', {bubbles: true})); await flush()
        expect(host.querySelectorAll('.article-title')).toHaveLength(1)
        expect(host.querySelector('.article-title').textContent).toBe('草稿')
        expect(listArticles).toHaveBeenCalledOnce()
    })
})

describe('image picker uses the existing backend without deleting or re-uploading files', () => {
    it('loads bounded backend pages and selects the existing URL without upload', async () => {
        const select = vi.fn(), update = vi.fn()
        await mount(ImagePicker, {modelValue: true, purpose: 'cover', onSelect: select, 'onUpdate:modelValue': update})
        expect(listImages).toHaveBeenCalledWith(0, 12, '',legacyImageCapabilities.images)
        document.querySelector('.picker-image').click(); await flush()
        expect(select).toHaveBeenCalledWith(expect.objectContaining({path: 'blog/covers/test.png'}))
        expect(update).toHaveBeenCalledWith(false)
        expect(uploadImage).not.toHaveBeenCalled()
    })
    it('does not load or expose image lists while closed', async () => {
        await mount(ImagePicker, {modelValue: false})
        expect(listImages).not.toHaveBeenCalled()
        expect(document.querySelector('.picker-image')).toBeNull()
    })
    it('reports list errors rather than a misleading empty state', async () => {
        listImages.mockRejectedValueOnce(new Error('图床暂时不可用'))
        await mount(ImagePicker, {modelValue: true})
        expect(document.querySelector('.picker-error').textContent).toContain('图床暂时不可用')
        expect(document.querySelector('.picker-empty')).toBeNull()
        button('刷新核对').click(); await flush()
        expect(document.querySelector('.picker-image')).not.toBeNull()
    })
    it('rejects empty files before sending an upload request', async () => {
        await mount(ImagePicker, {modelValue: true})
        const input = document.querySelector('input[type=file]')
        Object.defineProperty(input, 'files', {value: [new File([], 'empty.png', {type: 'image/png'})]})
        input.dispatchEvent(new Event('change', {bubbles: true})); await flush()
        expect(uploadImage).not.toHaveBeenCalled()
        expect(document.querySelector('.picker-error').textContent).toContain('非空')
    })
    it('uploads a valid cover through its purpose endpoint and selects only the returned URL', async () => {
        const select = vi.fn()
        uploadImage.mockResolvedValue({path: 'blog/covers/new.png', url: 'https://img.example.test/file/blog/covers/new.png'})
        await mount(ImagePicker, {modelValue: true, purpose: 'cover', onSelect: select})
        const input = document.querySelector('input[type=file]'), file = new File(['image'], 'new.png', {type: 'image/png'})
        Object.defineProperty(input, 'files', {value: [file]})
        input.dispatchEvent(new Event('change', {bubbles: true})); await flush()
        expect(uploadImage).toHaveBeenCalledWith(file, 'cover')
        expect(select).toHaveBeenCalledWith(expect.objectContaining({url: 'https://img.example.test/file/blog/covers/new.png'}))
    })
    it('does not apply or retry an upload that completes after the picker was closed', async () => {
        let complete
        const open = ref(true), select = vi.fn()
        uploadImage.mockImplementation(() => new Promise(resolve => {complete = resolve}))
        await mount({setup: () => () => h(ImagePicker, {modelValue: open.value, onSelect: select})})
        const input = document.querySelector('input[type=file]')
        Object.defineProperty(input, 'files', {value: [new File(['image'], 'new.png', {type: 'image/png'})]})
        input.dispatchEvent(new Event('change', {bubbles: true})); await flush()
        expect(document.querySelector('input[aria-label="搜索图床图片"]').disabled).toBe(true)
        open.value = false; await flush()
        complete({path: 'blog/articles/new.png', url: 'https://img.example.test/file/blog/articles/new.png'}); await flush()
        expect(select).not.toHaveBeenCalled(); expect(uploadImage).toHaveBeenCalledOnce()
    })
})
