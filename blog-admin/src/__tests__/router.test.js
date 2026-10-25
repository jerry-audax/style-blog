import {describe, it, expect, vi} from 'vitest'

// Mock the eagerly-loaded LoginView component to avoid pulling in its
// dependencies (ElementPlus, axios, etc.) during import
vi.mock('../views/LoginView.vue', () => ({
    default: {name: 'MockLoginView', template: '<div />'}
}))

import router, {validateToken} from '../router'

describe('Admin Router', () => {
    it('registers all expected route paths', () => {
        const paths = router.getRoutes().map(r => r.path)
        expect(paths).toContain('/login')
        expect(paths).toContain('/dashboard')
        expect(paths).toContain('/articles')
        expect(paths).toContain('/article/new')
        expect(paths).toContain('/article/:id/edit')
        expect(paths).toContain('/comments')
        expect(paths).toContain('/categories')
        expect(paths).toContain('/tags')
        expect(paths).toContain('/users')
        expect(paths).toContain('/logs')
        expect(paths).toContain('/images')
    })

    it('login route has guest meta (no auth required)', () => {
        const login = router.getRoutes().find(r => r.path === '/login')
        expect(login).toBeDefined()
        expect(login.meta.guest).toBe(true)
        expect(login.meta.title).toBe('登录')
    })

    it('dashboard route requires authentication', () => {
        const dashboard = router.getRoutes().find(r => r.path === '/dashboard')
        expect(dashboard).toBeDefined()
        expect(dashboard.meta.requiresAuth).toBe(true)
        expect(dashboard.meta.title).toBe('仪表盘')
    })

    it('all content management routes require authentication', () => {
        const protectedPaths = [
            '/articles', '/article/new', '/article/:id/edit',
            '/comments', '/categories', '/tags', '/account', '/logs', '/images', '/music'
        ]
        const routes = router.getRoutes()

        for (const path of protectedPaths) {
            const route = routes.find(r => r.path === path)
            expect(route, `Route ${path} should exist`).toBeDefined()
            expect(route.meta.requiresAuth, `Route ${path} should require auth`).toBe(true)
        }
    })

    it('root path redirects to dashboard', () => {
        const root = router.getRoutes().find(r => r.path === '/')
        expect(root).toBeDefined()
        expect(root.redirect).toBe('/dashboard')
    })

    it('uses hash history mode', () => {
        // createWebHashHistory produces URLs with # prefix
        const href = router.resolve('/dashboard').href
        expect(href).toContain('#')
    })
})
describe('Verified owner routing', () => {
    it('retired user management resolves to owner settings', () => {
        expect(router.getRoutes().find(r => r.path === '/users').redirect).toBe('/account')
    })
    it('HTTP 200 with legacy business 401 is not a valid session', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ok: true, status: 200, json: async () => ({code: 401})}))
        expect(await validateToken('fixture')).toEqual({valid: false, status: 401})
        vi.unstubAllGlobals()
    })
    it('a non-owner response is rejected even with HTTP and business success', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: async () => ({code: 0, data: {owner: false}})
        }))
        expect(await validateToken('fixture')).toEqual({valid: false, status: 403})
        vi.unstubAllGlobals()
    })
    it('only a server-verified owner is accepted', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: async () => ({code: 0, data: {owner: true}})
        }))
        expect(await validateToken('fixture')).toEqual({valid: true})
        vi.unstubAllGlobals()
    })
})
