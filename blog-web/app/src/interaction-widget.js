import {createApp} from 'vue'
import InteractionWidget from './components/InteractionWidget.vue'

const mounted = new Map()

export function mountInteractions() {
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
    for (const app of mounted.values()) app.unmount()
    mounted.clear()
}

mountInteractions()
document.addEventListener('pjax:send', unmountInteractions)
document.addEventListener('pjax:complete', mountInteractions)
window.addEventListener('pagehide', unmountInteractions)
