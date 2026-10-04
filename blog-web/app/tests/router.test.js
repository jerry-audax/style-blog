import {describe, it, expect, vi} from 'vitest'

vi.mock('../src/views/StaticBlogRedirect.vue', () => ({
    default: {name: 'MockBlogRedirect'},
}))

import router from '../src/router'

describe('Blog Router', () => {
    it('registers all expected route paths', () => {
        const paths = router.getRoutes().map((r) => r.path)
        expect(paths).toContain('/login')
        expect(paths).toContain('/')
        expect(paths).toContain('/article/:id')
        expect(paths).toContain('/profile')
        expect(paths).toContain('/ai')
    })

    it('retired login route redirects to the blog without mounting a login form', () => {
        const login = router.getRoutes().find((r) => r.path === '/login')
        expect(login).toBeDefined()
        expect(login.redirect).toBe('/')
        expect(login.components).toBeUndefined()
    })

    it('home page is guest-accessible', () => {
        const home = router.getRoutes().find((r) => r.path === '/')
        expect(home).toBeDefined()
        expect(home.meta.guest).toBe(true)
    })

    it('root uses the blog redirect instead of a separate homepage', async () => {
        const root = router.getRoutes().find((r) => r.path === '/')
        const component = await root.components.default()
        expect(component.default.name).toBe('MockBlogRedirect')
    })

    it('article detail is guest-accessible', () => {
        const article = router.getRoutes().find((r) => r.path === '/article/:id')
        expect(article).toBeDefined()
        expect(article.meta.guest).toBe(true)
    })

    it('retired profile redirects to the blog', () => {
        const profile = router.getRoutes().find((r) => r.path === '/profile')
        expect(profile).toBeDefined()
        expect(profile.redirect).toBe('/')
    })

    it('AI is not silently made anonymous when visitor login is retired', () => {
        const ai = router.getRoutes().find((r) => r.path === '/ai')
        expect(ai).toBeDefined()
        expect(ai.redirect).toBe('/')
    })

    it('uses scrollBehavior that returns top: 0', () => {
        // scrollBehavior is a function on the router options
        expect(typeof router.options.scrollBehavior).toBe('function')
        const result = router.options.scrollBehavior()
        expect(result).toEqual({top: 0})
    })
})
