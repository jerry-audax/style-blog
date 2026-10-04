import {beforeEach, afterEach, describe, it, expect, vi} from 'vitest'

const records = vi.hoisted(() => ({
    renderers: [],
    cameras: [],
    geometries: [],
    programs: [],
    meshes: [],
    unsupported: false
}))
vi.mock('ogl', () => ({
    Renderer: class {
        constructor(options) {
            if (records.unsupported) throw new Error('WebGL unavailable')
            this.options = options
            this.gl = {
                canvas: document.createElement('canvas'), POINTS: 0, clearColor: vi.fn(),
                getExtension: vi.fn(() => ({loseContext: vi.fn()}))
            }
            this.setSize = vi.fn()
            this.render = vi.fn()
            records.renderers.push(this)
        }
    },
    Camera: class {
        constructor() {
            this.position = {set: vi.fn()};
            this.perspective = vi.fn();
            records.cameras.push(this)
        }
    },
    Geometry: class {
        constructor(gl, attributes) {
            this.attributes = attributes;
            this.remove = vi.fn();
            records.geometries.push(this)
        }
    },
    Program: class {
        constructor(gl, options) {
            this.uniforms = options.uniforms;
            this.remove = vi.fn();
            records.programs.push(this)
        }
    },
    Mesh: class {
        constructor() {
            this.position = {x: 0, y: 0};
            this.rotation = {x: 0, y: 0, z: 0};
            records.meshes.push(this)
        }
    },
}))

import {particleOptions} from '../src/config/particles'
import {startSiteBackground} from '../src/site-background'

let reduced, frames, frameId
const advance = time => {
    const callbacks = [...frames.values()]
    frames.clear()
    callbacks.forEach(callback => callback(time))
}
beforeEach(() => {
    window.siteParticleBackground?.destroy()
    document.documentElement.dataset.theme = 'dark'
    for (const key of ['renderers', 'cameras', 'geometries', 'programs', 'meshes']) records[key].length = 0
    records.unsupported = false
    document.body.innerHTML = '<div id="web_bg"></div><div id="body-wrap"><main>文章</main></div>'
    Object.defineProperty(document, 'visibilityState', {value: 'visible', writable: true, configurable: true})
    frames = new Map()
    frameId = 0
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => {
        frames.set(++frameId, callback)
        return frameId
    })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(id => frames.delete(id))
    reduced = Object.assign(new window.EventTarget(), {matches: false})
    window.matchMedia = vi.fn(() => reduced)
})
afterEach(() => {
    window.siteParticleBackground?.destroy();
    vi.restoreAllMocks()
})

describe('React Bits particles integration', () => {
    it('uses every supplied screenshot parameter and all 350 geometry records', () => {
        expect(particleOptions).toEqual({
            particleCount: 350, particleSpread: 10, speed: 0.1,
            particleColors: ['#6366F1', '#10B981', '#ffffff', '#EC4899'],
            moveParticlesOnHover: true, particleHoverFactor: 1, alphaParticles: false,
            particleBaseSize: 100, sizeRandomness: 1, cameraDistance: 25, disableRotation: false, pixelRatio: 1,
        })
        startSiteBackground()
        expect(records.renderers[0].options).toEqual({dpr: 1, depth: false, alpha: true})
        expect(records.geometries[0].attributes.position.data.length).toBe(350 * 3)
        expect(records.geometries[0].attributes.random.data.length).toBe(350 * 4)
        expect(records.geometries[0].attributes.color.data.length).toBe(350 * 3)
        expect(records.cameras[0].position.set).toHaveBeenCalledWith(0, 0, 25)
        expect(records.programs[0].uniforms.uSpread.value).toBe(10)
        expect(records.programs[0].uniforms.uBaseSize.value).toBe(100)
        expect(records.programs[0].uniforms.uAlphaParticles.value).toBe(0)
    })
    it('keeps one inert, non-interactive canvas outside PJAX replacement regions', () => {
        const instance = startSiteBackground()
        document.dispatchEvent(new Event('pjax:send'))
        document.getElementById('body-wrap').innerHTML = '<main>音乐</main>'
        document.dispatchEvent(new Event('pjax:complete'))
        expect(startSiteBackground()).toBe(instance)
        expect(instance.element.parentNode).toBe(document.body)
        expect(instance.element.getAttribute('aria-hidden')).toBe('true')
        expect(instance.element.hasAttribute('inert')).toBe(true)
        expect(document.querySelectorAll('#site-particles-background canvas').length).toBe(1)
        expect(records.renderers.length).toBe(1)
        expect(frames.size).toBe(1)
    })
    it('renders static particles for reduced motion, then starts only one loop if the preference changes', () => {
        reduced.matches = true
        startSiteBackground()
        expect(frames.size).toBe(0)
        expect(records.renderers[0].render).toHaveBeenCalled()
        window.dispatchEvent(new MouseEvent('mousemove', {clientX: 100, clientY: 100}))
        expect(records.meshes[0].position.x).toBe(0)
        reduced.matches = false
        reduced.dispatchEvent(new Event('change'))
        expect(frames.size).toBe(1)
        reduced.dispatchEvent(new Event('change'))
        expect(frames.size).toBe(1)
        reduced.matches = true
        reduced.dispatchEvent(new Event('change'))
        expect(frames.size).toBe(0)
    })
    it('suspends while hidden and resumes without a large time jump, including back-forward cache lifecycle', () => {
        startSiteBackground()
        advance(0)
        advance(100)
        const time = records.programs[0].uniforms.uTime.value
        expect(time).toBeCloseTo(0.01)
        document.visibilityState = 'hidden'
        document.dispatchEvent(new Event('visibilitychange'))
        expect(frames.size).toBe(0)
        document.visibilityState = 'visible'
        document.dispatchEvent(new Event('visibilitychange'))
        advance(10000)
        expect(records.programs[0].uniforms.uTime.value).toBe(time)
        window.dispatchEvent(new Event('pagehide'))
        expect(frames.size).toBe(0)
        window.dispatchEvent(new Event('pageshow'))
        expect(frames.size).toBe(1)
    })
    it('responds to mouse motion without taking input and adapts to viewport resizing', () => {
        startSiteBackground()
        window.dispatchEvent(new MouseEvent('mousemove', {clientX: window.innerWidth, clientY: 0}))
        advance(16)
        expect(records.meshes[0].position.x).toBe(-1)
        expect(records.meshes[0].position.y).toBe(-1)
        expect(records.meshes[0].rotation.z).toBeCloseTo(0.001)
        window.dispatchEvent(new Event('resize'))
        expect(records.renderers[0].setSize).toHaveBeenCalledWith(window.innerWidth, window.innerHeight)
    })
    it('stops rendering in light mode, then resumes the same canvas once without a time jump', async () => {
        const instance = startSiteBackground()
        advance(0)
        advance(100)
        const time = records.programs[0].uniforms.uTime.value
        document.documentElement.dataset.theme = 'light'
        await Promise.resolve()
        expect(frames.size).toBe(0)
        const renders = records.renderers[0].render.mock.calls.length
        window.dispatchEvent(new Event('resize'))
        window.dispatchEvent(new Event('pageshow'))
        document.dispatchEvent(new Event('visibilitychange'))
        reduced.dispatchEvent(new Event('change'))
        window.dispatchEvent(new MouseEvent('mousemove', {clientX: 500, clientY: 500}))
        advance(5000)
        expect(records.renderers[0].render).toHaveBeenCalledTimes(renders)
        document.documentElement.dataset.theme = 'dark'
        await Promise.resolve()
        expect(startSiteBackground()).toBe(instance)
        expect(frames.size).toBe(1)
        advance(10000)
        expect(records.programs[0].uniforms.uTime.value).toBe(time)
        expect(records.renderers.length).toBe(1)
        expect(document.querySelectorAll('#site-particles-background canvas').length).toBe(1)
    })
    it('never renders an initially saved light theme, even with reduced-motion changes', async () => {
        document.documentElement.dataset.theme = 'light'
        reduced.matches = true
        startSiteBackground()
        expect(frames.size).toBe(0)
        expect(records.renderers[0].render).not.toHaveBeenCalled()
        reduced.matches = false
        reduced.dispatchEvent(new Event('change'))
        expect(frames.size).toBe(0)
        document.documentElement.dataset.theme = 'dark'
        await Promise.resolve()
        expect(frames.size).toBe(1)
    })
    it('disconnects the theme observer when destroyed', async () => {
        const instance = startSiteBackground()
        instance.destroy()
        const renders = records.renderers[0].render.mock.calls.length
        document.documentElement.dataset.theme = 'light'
        await Promise.resolve()
        document.documentElement.dataset.theme = 'dark'
        await Promise.resolve()
        expect(frames.size).toBe(0)
        expect(records.renderers[0].render).toHaveBeenCalledTimes(renders)
    })
    it('falls back on context loss and releases all resources exactly once when disposed', () => {
        const instance = startSiteBackground()
        instance.element.querySelector('canvas').dispatchEvent(new Event('webglcontextlost'))
        expect(instance.element.dataset.state).toBe('fallback')
        expect(frames.size).toBe(0)
        window.dispatchEvent(new Event('pageshow'))
        expect(frames.size).toBe(0)
        instance.destroy()
        instance.destroy()
        expect(records.geometries[0].remove).toHaveBeenCalledTimes(1)
        expect(records.programs[0].remove).toHaveBeenCalledTimes(1)
        expect(window.siteParticleBackground).toBeUndefined()
        expect(document.getElementById('site-particles-background')).toBeNull()
    })
    it('degrades to the CSS background without throwing or touching the music player when WebGL is unavailable', () => {
        records.unsupported = true
        const audio = document.createElement('audio')
        audio.src = 'https://example.com/track.mp3'
        audio.currentTime = 37
        document.body.append(audio)
        const instance = startSiteBackground()
        expect(instance.element.dataset.state).toBe('fallback')
        expect(instance.element.childElementCount).toBe(0)
        expect(frames.size).toBe(0)
        expect(audio.isConnected).toBe(true)
        expect(audio.currentTime).toBe(37)
    })
})
