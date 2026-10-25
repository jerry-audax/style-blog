import {afterEach, describe, expect, it, vi} from 'vitest'
import {createApp, nextTick} from 'vue'
vi.mock('../api/publication', () => ({getPublicationStatus: vi.fn(), retryPublication: vi.fn()}))
import {getPublicationStatus, retryPublication} from '../api/publication'
import PublicationNotice from '../components/PublicationNotice.vue'
let app, host
const flush = async () => {await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()}
const mount = async state => {
    getPublicationStatus.mockResolvedValue(state)
    host = document.createElement('div'); document.body.append(host)
    app = createApp(PublicationNotice); app.mount(host); await flush()
}
afterEach(() => {app?.unmount(); host?.remove(); vi.clearAllMocks()})
describe('automatic public-site publication', () => {
    it('shows the last successful publication without instructing the owner to run build commands', async () => {
        await mount({configured: true, phase: 'CURRENT', publishedAt: '2026-10-03T18:00:00Z'})
        expect(host.textContent).toContain('最近一次发布成功')
        expect(host.textContent).toContain('自动更新')
        expect(host.textContent).not.toContain('npm run build')
    })
    it('displays failure, preserves the saved-content explanation and exposes an asynchronous retry', async () => {
        await mount({configured: true, phase: 'FAILED', error: '生成失败，仍展示上一版'})
        expect(host.textContent).toContain('生成失败，仍展示上一版')
        retryPublication.mockResolvedValue({configured: true, phase: 'WAITING'})
        const button = [...host.querySelectorAll('button')].find(button => button.textContent.includes('重试'))
        button.click(); await flush()
        expect(host.textContent).toContain('等待发布')
        expect(host.textContent).not.toContain('发布成功')
    })
    it('shows an unavailable service as a configuration problem instead of publication success', async () => {
        await mount({configured: false, phase: 'UNAVAILABLE', error: '自动发布服务未连接'})
        expect(host.textContent).toContain('自动发布服务未连接')
        expect(host.textContent).toContain('已保存')
        expect(host.querySelector('button').disabled).toBe(true)
    })
})
