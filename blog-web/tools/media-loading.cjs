'use strict'
const {parseDocument} = require('htmlparser2')
const postcss = require('postcss')
const escapeAttribute = value => String(value).replace(/[&"<>]/g, char =>
    ({'&':'&amp;','"':'&quot;','<':'&lt;','>':'&gt;'})[char])

// Transform generated markup before the browser discovers image sources. Do
// not reserialize scripts, article HTML, or the upstream theme's whole page.
function prepareMediaLoading(html) {
    const document = parseDocument(html, {withStartIndices:true,withEndIndices:true})
    const edits = []
    let firstCard = true, firstSlide = true
    const classes = node => (node.attribs?.class || '').split(/\s+/)
    const within = (node, predicate) => {
        for (let current=node; current; current=current.parent) if (predicate(current)) return true
        return false
    }
    function visit(node) {
        if (['script','style','template','noscript'].includes(node.name)) return
        if (node.attribs) {
            const attributes = {...node.attribs}
            let changed = false
            if (node.name === 'img' && attributes.src) {
                let eager = classes(node).includes('nolazyload') || within(node, ancestor =>
                    ['nav','page-header','nav-music'].includes(ancestor.attribs?.id) || classes(ancestor).includes('avatar-img'))
                if (classes(node).includes('post_bg') && within(node, ancestor => classes(ancestor).includes('recent-post-item'))) {
                    eager ||= firstCard; firstCard=false
                }
                if (within(node, ancestor => classes(ancestor).includes('blog-slider__img'))) {
                    eager ||= firstSlide; firstSlide=false
                }
                attributes.loading = eager ? 'eager' : 'lazy'
                attributes.decoding = 'async'
                changed = true
            }
            const decorative = node.attribs.id === 'footer' ||
                (classes(node).includes('author-content-item') && !classes(node).includes('single'))
            if (decorative && attributes.style && !attributes['data-site-lazy-background']) {
                try {
                    const style = postcss.parse(attributes.style)
                    style.walkDecls('background-image', declaration => {
                        const match = declaration.value.match(/^url\(\s*(['"]?)([^'"()\s]+)\1\s*\)$/i)
                        if (!match || !/^(https?:\/\/|\/(?!\/))/.test(match[2])) return
                        attributes['data-site-lazy-background'] = match[2]
                        if (declaration.important) attributes['data-site-lazy-background-priority'] = 'important'
                        declaration.remove(); changed=true
                    })
                    if (changed) attributes.style = style.toString()
                } catch { /* Keep unsupported theme styles unchanged. */ }
            }
            if (changed) {
                const opening = html.slice(node.startIndex).match(/^<(?:[^"'<>]|"[^"]*"|'[^']*')*>/)
                if (!opening) return
                const tag = '<' + node.name + Object.entries(attributes)
                    .map(([name,value]) => ` ${name}="${escapeAttribute(value)}"`).join('') + '>'
                edits.push([node.startIndex,node.startIndex+opening[0].length,tag])
            }
        }
        for (const child of node.children || []) visit(child)
    }
    visit(document)
    for (const [start,end,value] of edits.sort((a,b)=>b[0]-a[0])) html=html.slice(0,start)+value+html.slice(end)
    return html
}

module.exports = {prepareMediaLoading}
