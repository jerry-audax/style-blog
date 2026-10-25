import {createApp} from 'vue'
import InteractionWidget from './components/InteractionWidget.vue'
import TagArticleList from './components/TagArticleList.vue'
import {mountHomeTags, unmountHomeTags} from './home/home-tags'
import {validContentId} from './content/public-content'

const mounted = new Map()
const tagPages = new Map()

export function mountInteractions() {
    void mountHomeTags()
    if (window.location.pathname === '/blog/tags/') {
        const ids = new URLSearchParams(window.location.search).getAll('tagId')
        const element = document.querySelector('[data-blog-tag-articles]')
        if (element && !tagPages.has(element) && ids.length === 1 && validContentId(ids[0])) {
            const original = element.innerHTML
            const app = createApp(TagArticleList, {tagId: ids[0]})
            app.mount(element)
            tagPages.set(element, {app, original})
        }
    }
    for (const element of document.querySelectorAll('[data-blog-interactions]')) {
        if (mounted.has(element)) continue
        const articleId = element.dataset.blogInteractions
        if (!/^[1-9]\d{0,18}$/.test(articleId)) continue
        const app = createApp(InteractionWidget, {articleId})
        app.mount(element)
        mounted.set(element, app)
    }
}

export function unmountInteractions() {
    unmountHomeTags()
    for (const [element, entry] of tagPages) {
        entry.app.unmount()
        element.innerHTML = entry.original
    }
    tagPages.clear()
    for (const app of mounted.values()) app.unmount()
    mounted.clear()
}

mountInteractions()
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mountInteractions, {once: true})
document.addEventListener('pjax:send', unmountInteractions)
document.addEventListener('pjax:complete', mountInteractions)
window.addEventListener('pagehide', unmountInteractions)
window.addEventListener('pageshow', event => {
    if (event.persisted) mountInteractions()
})
