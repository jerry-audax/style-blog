import {particleOptions} from './config/particles'
import {createParticles} from './effects/react-bits-particles'
import {createApp} from 'vue'
import GalaxyBackgroundApp from './GalaxyBackgroundApp.vue'

// Lives outside #body-wrap: PJAX replaces pages, never this decorative canvas.
export function startSiteBackground() {
    if (window.siteParticleBackground) return window.siteParticleBackground
    const element = document.createElement('div')
    element.id = 'site-particles-background'
    element.setAttribute('aria-hidden', 'true')
    element.setAttribute('inert', '')
    document.body.prepend(element)
    let dispose
    try {
        if (window.siteBackgroundVariant === 'galaxy') {
            const app = createApp(GalaxyBackgroundApp)
            app.mount(element)
            dispose = () => app.unmount()
        } else dispose = createParticles(element, particleOptions)
    } catch (error) {
        element.dataset.state = 'fallback';
        element.dataset.error = error instanceof Error ? error.name : 'unknown'
        element.replaceChildren()
    }
    const instance = {
        element,
        destroy() {
            dispose?.()
            element.remove()
            if (window.siteParticleBackground === instance) delete window.siteParticleBackground
        },
    }
    window.siteParticleBackground = instance
    return instance
}

startSiteBackground()
