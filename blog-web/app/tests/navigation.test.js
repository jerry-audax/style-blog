import {afterEach, describe, expect, it} from 'vitest'
import {createApp, h} from 'vue'
import BlogNavigation from '../src/components/BlogNavigation.vue'

let app, host
afterEach(() => {
    app?.unmount()
    host?.remove()
})

function mount(mobile = false) {
    host = document.createElement('div')
    document.body.append(host)
    app = createApp({render: () => h(BlogNavigation, {mobile})})
    app.mount(host)
    return host.querySelector('details')
}

describe('主页导航分组', () => {
    it('retains all original page links under 主页 on desktop and mobile', () => {
        const group = mount(true)
        expect(group.querySelector('summary').textContent.trim()).toBe('主页')
        const links = [...group.querySelectorAll('nav a')].map((link) => [
            link.textContent,
            link.getAttribute('href'),
        ])
        expect(links).toEqual([
            ['归档', '/blog/archives/'],
            ['分类', '/blog/categories/'],
            ['标签', '/blog/tags/'],
        ])
        expect(group.classList.contains('is-mobile')).toBe(true)
        expect([...host.querySelectorAll('.site-link')].map(link => [link.textContent, link.getAttribute('href')])).toEqual([
            ['最新文章', '/blog/#recent-posts'], ['音乐', '/blog/music/'], ['惊喜', '/blog/surprise/'], ['关于', '/blog/about/'],
        ])
        expect(group.querySelector('summary a').getAttribute('href')).toBe('/blog/')
    })
    it('closes with Escape and outside pointer actions without removing links', () => {
        const group = mount()
        group.open = true
        const escape = new KeyboardEvent('keydown', {
            key: 'Escape',
            bubbles: true,
            cancelable: true,
        })
        group.querySelector('summary').dispatchEvent(escape)
        expect(group.open).toBe(false)
        expect(escape.defaultPrevented).toBe(true)
        expect(document.activeElement).toBe(group.querySelector('summary'))
        const closedEscape = new KeyboardEvent('keydown', {
            key: 'Escape',
            bubbles: true,
            cancelable: true,
        })
        group.querySelector('summary').dispatchEvent(closedEscape)
        expect(closedEscape.defaultPrevented).toBe(false)
        group.open = true
        document.body.dispatchEvent(new Event('pointerdown', {bubbles: true}))
        expect(group.open).toBe(false)
        expect(group.querySelectorAll('a')).toHaveLength(4)
    })
})
