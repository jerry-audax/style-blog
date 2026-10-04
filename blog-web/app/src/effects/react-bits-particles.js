// Adapted from React Bits Particles-JS-CSS, copyright (c) 2026 David Haz.
// Source commit: 1eeb6f105c68b964289d85dabbe84a1d551f3797.
// MIT + Commons Clause; see blog-web/licenses/react-bits.txt.
// Preserve its OGL shaders/particle behavior, replacing React's effect with a
// framework-independent lifecycle for the existing Hexo/Vue site, not a library.
import {Renderer, Camera, Geometry, Program, Mesh} from 'ogl'

const vertex = `
  attribute vec3 position;
  attribute vec4 random;
  attribute vec3 color;
  uniform mat4 modelMatrix;
  uniform mat4 viewMatrix;
  uniform mat4 projectionMatrix;
  uniform float uTime;
  uniform float uSpread;
  uniform float uBaseSize;
  uniform float uSizeRandomness;
  varying vec4 vRandom;
  varying vec3 vColor;
  void main() {
    vRandom = random;
    vColor = color;
    vec3 pos = position * uSpread;
    pos.z *= 10.0;
    vec4 mPos = modelMatrix * vec4(pos, 1.0);
    float t = uTime;
    mPos.x += sin(t * random.z + 6.28 * random.w) * mix(0.1, 1.5, random.x);
    mPos.y += sin(t * random.y + 6.28 * random.x) * mix(0.1, 1.5, random.w);
    mPos.z += sin(t * random.w + 6.28 * random.y) * mix(0.1, 1.5, random.z);
    vec4 mvPos = viewMatrix * mPos;
    if (uSizeRandomness == 0.0) {
      gl_PointSize = uBaseSize;
    } else {
      gl_PointSize = (uBaseSize * (1.0 + uSizeRandomness * (random.x - 0.5))) / length(mvPos.xyz);
    }
    gl_Position = projectionMatrix * mvPos;
  }
`
const fragment = `
  precision highp float;
  uniform float uTime;
  uniform float uAlphaParticles;
  varying vec4 vRandom;
  varying vec3 vColor;
  void main() {
    vec2 uv = gl_PointCoord.xy;
    float d = length(uv - vec2(0.5));
    if(uAlphaParticles < 0.5) {
      if(d > 0.5) { discard; }
      gl_FragColor = vec4(vColor + 0.2 * sin(uv.yxx + uTime + vRandom.y * 6.28), 1.0);
    } else {
      float circle = smoothstep(0.5, 0.4, d) * 0.8;
      gl_FragColor = vec4(vColor + 0.2 * sin(uv.yxx + uTime + vRandom.y * 6.28), circle);
    }
  }
`

export function createParticles(container, options) {
    const document = container.ownerDocument, view = document.defaultView
    const reduced = view.matchMedia?.('(prefers-reduced-motion: reduce)')
    const renderer = new Renderer({dpr: options.pixelRatio, depth: false, alpha: true})
    const gl = renderer.gl
    container.append(gl.canvas)
    gl.canvas.setAttribute('aria-hidden', 'true')
    gl.clearColor(0, 0, 0, 0)
    const camera = new Camera(gl, {fov: 15})
    camera.position.set(0, 0, options.cameraDistance)
    const positions = new Float32Array(options.particleCount * 3)
    const randoms = new Float32Array(options.particleCount * 4)
    const colors = new Float32Array(options.particleCount * 3)
    for (let i = 0; i < options.particleCount; i++) {
        let x, y, z, length
        do {
            x = Math.random() * 2 - 1
            y = Math.random() * 2 - 1
            z = Math.random() * 2 - 1
            length = x * x + y * y + z * z
        } while (length > 1 || length === 0)
        const radius = Math.cbrt(Math.random())
        positions.set([x * radius, y * radius, z * radius], i * 3)
        randoms.set([Math.random(), Math.random(), Math.random(), Math.random()], i * 4)
        const color = parseInt(options.particleColors[Math.floor(Math.random() * options.particleColors.length)].slice(1), 16)
        colors.set([((color >> 16) & 255) / 255, ((color >> 8) & 255) / 255, (color & 255) / 255], i * 3)
    }
    const geometry = new Geometry(gl, {
        position: {size: 3, data: positions}, random: {size: 4, data: randoms}, color: {size: 3, data: colors},
    })
    const program = new Program(gl, {
        vertex, fragment, transparent: true, depthTest: false,
        uniforms: {
            uTime: {value: 0}, uSpread: {value: options.particleSpread},
            uBaseSize: {value: options.particleBaseSize * options.pixelRatio},
            uSizeRandomness: {value: options.sizeRandomness},
            uAlphaParticles: {value: options.alphaParticles ? 1 : 0},
        },
    })
    const particles = new Mesh(gl, {mode: gl.POINTS, geometry, program})
    let frame = null, lastTime = null, elapsed = 0, destroyed = false, lost = false
    const mouse = {x: 0, y: 0}
    const render = () => {
        const moving = options.moveParticlesOnHover && !reduced?.matches
        particles.position.x = moving ? -mouse.x * options.particleHoverFactor : 0
        particles.position.y = moving ? -mouse.y * options.particleHoverFactor : 0
        renderer.render({scene: particles, camera})
    }
    const paused = () => destroyed || lost || document.visibilityState === 'hidden'
        || document.documentElement.dataset.theme === 'light'
    const suspend = () => {
        if (frame !== null) view.cancelAnimationFrame(frame)
        frame = null
        lastTime = null
    }
    const update = time => {
        frame = null
        if (paused() || reduced?.matches) return
        elapsed += (lastTime === null ? 0 : Math.min(time - lastTime, 100)) * options.speed
        lastTime = time
        program.uniforms.uTime.value = elapsed * 0.001
        if (!options.disableRotation) {
            particles.rotation.x = Math.sin(elapsed * 0.0002) * 0.1
            particles.rotation.y = Math.cos(elapsed * 0.0005) * 0.15
            particles.rotation.z += 0.01 * options.speed
        }
        render()
        frame = view.requestAnimationFrame(update)
    }
    const resume = () => {
        if (paused()) return
        if (reduced?.matches) {
            render();
            return
        }
        if (frame === null) frame = view.requestAnimationFrame(update)
    }
    const resize = () => {
        const width = container.clientWidth || view.innerWidth, height = container.clientHeight || view.innerHeight
        renderer.setSize(width, height)
        camera.perspective({aspect: width / height})
        if (!paused()) render()
    }
    const onMouse = event => {
        if (reduced?.matches || paused()) return
        const rect = container.getBoundingClientRect()
        mouse.x = ((event.clientX - rect.left) / (rect.width || view.innerWidth)) * 2 - 1
        mouse.y = -(((event.clientY - rect.top) / (rect.height || view.innerHeight)) * 2 - 1)
    }
    const onVisibility = () => {
        suspend();
        resume()
    }
    // The theme persists on <html> across PJAX. CSS hides the canvas in light
    // mode; stop GPU rendering too, including resize/reduced-motion callbacks.
    const themeObserver = new view.MutationObserver(onVisibility)
    themeObserver.observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']})
    const onContextLost = () => {
        lost = true;
        suspend();
        container.dataset.state = 'fallback'
    }
    view.addEventListener('resize', resize)
    if (options.moveParticlesOnHover) view.addEventListener('mousemove', onMouse, {passive: true})
    document.addEventListener('visibilitychange', onVisibility)
    reduced?.addEventListener('change', onVisibility)
    view.addEventListener('pagehide', suspend)
    view.addEventListener('pageshow', resume)
    gl.canvas.addEventListener('webglcontextlost', onContextLost)
    container.dataset.state = 'ready'
    resize()
    resume()
    return () => {
        if (destroyed) return
        destroyed = true
        suspend()
        themeObserver.disconnect()
        view.removeEventListener('resize', resize)
        view.removeEventListener('mousemove', onMouse)
        document.removeEventListener('visibilitychange', onVisibility)
        reduced?.removeEventListener('change', onVisibility)
        view.removeEventListener('pagehide', suspend)
        view.removeEventListener('pageshow', resume)
        gl.canvas.removeEventListener('webglcontextlost', onContextLost)
        geometry.remove()
        program.remove()
        gl.getExtension('WEBGL_lose_context')?.loseContext()
        gl.canvas.remove()
    }
}
