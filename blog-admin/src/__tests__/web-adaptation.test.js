import {describe, expect, it} from 'vitest'
import {adminNavigation} from '../config/navigation'
import {articlePublicUrl, resolvePublicBlogUrl} from '../config/blog'
import {imageLimit, validateImageUpload} from '../utils/images'

describe('public blog links and single-owner navigation', () => {
    it('uses the existing dev blog and canonical /blog/ path', () => {
        expect(resolvePublicBlogUrl('', {development: true})).toBe('http://127.0.0.1:5173/blog/')
        for (const url of ['https://blog.example.test', 'https://blog.example.test/blog/'])
            expect(resolvePublicBlogUrl(url)).toBe('https://blog.example.test/blog/')
        expect(resolvePublicBlogUrl('', {origin: 'https://blog.example.test'})).toBe('https://blog.example.test/blog/')
    })
    it('rejects script URLs, credentials, query parameters and incorrect deployment paths', () => {
        for (const url of ['javascript:alert(1)', 'https://user:secret@blog.example.test', 'https://blog.example.test/?token=x', 'https://blog.example.test/#private', 'https://blog.example.test/elsewhere', '/relative'])
            expect(() => resolvePublicBlogUrl(url)).toThrow()
    })
    it('keeps article URLs stable by database ID and does not generate invalid links', () => {
        expect(articlePublicUrl('https://blog.example.test/blog/', 12)).toBe('https://blog.example.test/blog/article/12/')
        for (const id of ['../private', 0, -1, '12?token=x', NaN, 9007199254740992]) expect(articlePublicUrl('https://blog.example.test/blog/', id)).toBe('')
        expect(articlePublicUrl('', 12)).toBe('')
    })
    it('shares desktop/mobile navigation without adding unsupported modules', () => {
        const routes = adminNavigation.flatMap(group => group.items.map(item => item.to))
        expect(routes).toEqual(['/dashboard', '/articles', '/categories', '/tags', '/comments', '/images', '/music', '/surprise', '/account', '/logs'])
        expect(new Set(routes).size).toBe(routes.length)
        for (const retired of ['/users', '/ai', '/storage', '/files']) expect(routes).not.toContain(retired)
    })
})

describe('image policies are not relaxed by the media library', () => {
    it('uses existing avatar 2 MiB and article/cover 10 MiB limits', () => {
        expect(imageLimit('avatar')).toBe(2 * 1024 * 1024)
        expect(imageLimit('cover')).toBe(10 * 1024 * 1024)
        expect(() => validateImageUpload({type: 'image/png', size: 2 * 1024 * 1024}, 'avatar')).not.toThrow()
        expect(() => validateImageUpload({type: 'image/png', size: 2 * 1024 * 1024 + 1}, 'avatar')).toThrow()
        expect(() => validateImageUpload({type: 'image/jpeg', size: 10 * 1024 * 1024}, 'cover')).not.toThrow()
    })
    it('rejects empty files, invalid sizes, SVG, audio, oversize and invalid purposes', () => {
        for (const file of [{type: 'image/png', size: 0}, {type: 'image/png', size: NaN}, {type: 'image/png', size: -1}, {type: 'image/svg+xml', size: 10}, {type: 'audio/mpeg', size: 10}, {type: 'image/webp', size: 10 * 1024 * 1024 + 1}])
            expect(() => validateImageUpload(file)).toThrow()
        expect(() => validateImageUpload({type: 'image/png', size: 5}, '../upload')).toThrow()
    })
})
