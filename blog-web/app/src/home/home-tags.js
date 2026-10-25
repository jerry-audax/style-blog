import {publicContent, validContentId} from '../content/public-content'

let mounted = null

export function mountHomeTags(root = document) {
    const group = root.querySelector('[data-blog-home-tags]')
    if (!group) return Promise.resolve()
    if (mounted?.group === group) return mounted.promise
    unmountHomeTags()
    const links = [...group.querySelectorAll('a.categoryButton')]
    if (links.length !== 3 || links.some(link => !link.querySelector('.categoryButtonText'))) return Promise.resolve()
    const slots = links.map(link => link.closest('.categoryItem') || link)
    const state = {group, links, slots, controller: new AbortController(), originals: links.map((link,index) => ({href: link.getAttribute('href'), text: link.querySelector('.categoryButtonText').textContent,
        display:slots[index].style.getPropertyValue('display'),priority:slots[index].style.getPropertyPriority('display')}))}
    mounted = state
    state.promise = (async () => {
        try {
            const data = await publicContent('/api/content/home-tags', state.controller.signal)
            if (mounted !== state || state.controller.signal.aborted || !group.isConnected || !Array.isArray(data?.tags) || data.tags.length > 3) return
            if (new Set(data.tags.map(tag => tag.id)).size !== data.tags.length || data.tags.some((tag, index) =>
                !validContentId(tag.id) || tag.position !== index + 1 || typeof tag.name !== 'string' || !tag.name.trim() || tag.href !== `/blog/tags/?tagId=${tag.id}`)) return
            // The backend already resolved hot/default tags. Validate everything
            // before the first mutation. Original nodes keep
            // their layout, icon, focus and event handlers, including PJAX links.
            data.tags.forEach((tag, index) => {
                links[index].querySelector('.categoryButtonText').textContent = tag.name
                links[index].setAttribute('href', tag.href)
            })
            slots.forEach((slot,index) => {
                if (index >= data.tags.length) slot.style.setProperty('display','none','important')
            })
        } catch { /* Keep all original entries on unavailable or malformed data. */ }
    })()
    return state.promise
}

export function unmountHomeTags() {
    const state = mounted
    mounted = null
    if (!state) return
    state.controller.abort()
    state.links.forEach((link, index) => {
        link.setAttribute('href', state.originals[index].href)
        link.querySelector('.categoryButtonText').textContent = state.originals[index].text
        const {display,priority} = state.originals[index]
        if (display) state.slots[index].style.setProperty('display',display,priority)
        else state.slots[index].style.removeProperty('display')
    })
}
