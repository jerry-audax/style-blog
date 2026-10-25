const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const {JSDOM} = require('jsdom')

test('generated images keep critical media eager and defer other images without losing sizing or URLs', () => {
    const {prepareMediaLoading} = require('../tools/media-loading.cjs')
    const html = '<script>const sample="<img src=ignore>"</script><nav id="nav"><img src="/logo.png"></nav>' +
        '<div id="recent-posts"><article class="recent-post-item"><img class="post_bg" src="/first.webp"></article>' +
        '<article class="recent-post-item"><img class="post_bg" src="/second.webp" title="a > b" width="640" height="400"></article></div>' +
        '<div id="article-container"><img src="https://images.test/body?a=1&amp;b=2" loading="eager"></div>'
    const result = prepareMediaLoading(html), document = new JSDOM(result).window.document
    assert.equal(document.querySelector('#nav img').getAttribute('loading'), 'eager')
    const covers = document.querySelectorAll('.post_bg')
    assert.equal(covers[0].getAttribute('loading'), 'eager')
    assert.equal(covers[1].getAttribute('loading'), 'lazy')
    assert.equal(covers[1].getAttribute('decoding'), 'async')
    assert.equal(covers[1].getAttribute('width'), '640')
    assert.equal(covers[1].getAttribute('height'), '400')
    assert.equal(covers[1].getAttribute('title'), 'a > b')
    assert.equal(document.querySelector('#recent-posts').textContent,'','quoted angle brackets must not leak attribute fragments into the page')
    assert.equal(document.querySelector('#article-container img').getAttribute('loading'), 'lazy')
    assert.equal(document.querySelector('#article-container img').getAttribute('src'), 'https://images.test/body?a=1&b=2')
    assert.match(result, /<script>const sample="<img src=ignore>"<\/script>/)
    assert.equal(prepareMediaLoading(result), result, 'repeated publication must be idempotent')
})

test('only eligible decorative background images are deferred, leaving hero, colors and complex styles alone', () => {
    const {prepareMediaLoading} = require('../tools/media-loading.cjs')
    const document = new JSDOM(prepareMediaLoading('<header id="page-header" style="background-image:url(/hero.webp)"></header>' +
        '<footer id="footer" style="color:red;background-image:url(https://images.test/footer.webp);background-size:cover"></footer>' +
        '<div class="author-content-item" style="background:linear-gradient(red,blue)"></div>')).window.document
    const footer = document.querySelector('footer')
    assert.equal(footer.getAttribute('data-site-lazy-background'), 'https://images.test/footer.webp')
    assert.equal(footer.style.backgroundImage, '')
    assert.equal(footer.style.color, 'red')
    assert.equal(footer.style.backgroundSize, 'cover')
    assert.equal(document.querySelector('header').getAttribute('data-site-lazy-background'), null)
    assert.match(document.querySelector('.author-content-item').getAttribute('style'), /linear-gradient/)
})

function backgroundFixture({observer = true} = {}) {
    const dom = new JSDOM('<div id="body-wrap"><footer data-site-lazy-background="https://images.test/footer.webp"></footer></div>', {url:'http://localhost/blog/',runScripts:'outside-only'})
    const observers = []
    if (observer) dom.window.IntersectionObserver = class {
        constructor(callback, options) {this.callback=callback;this.options=options;this.targets=new Set();observers.push(this)}
        observe(node) {this.targets.add(node)}
        unobserve(node) {this.targets.delete(node)}
        disconnect() {this.targets.clear()}
    }
    const source = fs.readFileSync(path.join(__dirname,'../source/js/site-media-loading.js'),'utf8')
    vm.runInContext(source,dom.getInternalVMContext())
    dom.window.document.dispatchEvent(new dom.window.Event('DOMContentLoaded'))
    return {dom,observers,source}
}

test('backgrounds request only near the viewport and PJAX registers new nodes without duplicate observers', () => {
    const f = backgroundFixture()
    try {
        const doc=f.dom.window.document, footer=doc.querySelector('footer'), observer=f.observers[0]
        assert.equal(footer.style.backgroundImage, '')
        assert.equal(observer.options.rootMargin,'300px 0px')
        observer.callback([{target:footer,isIntersecting:false}])
        assert.equal(footer.style.backgroundImage, '')
        observer.callback([{target:footer,isIntersecting:true}])
        assert.match(footer.style.backgroundImage,/footer\.webp/)
        assert.equal(observer.targets.size,0)
        doc.dispatchEvent(new f.dom.window.Event('pjax:send'))
        doc.querySelector('#body-wrap').innerHTML='<div data-site-lazy-background="/next.webp"></div>'
        doc.dispatchEvent(new f.dom.window.Event('pjax:complete'))
        const next=doc.querySelector('[data-site-lazy-background]'), current=f.observers.at(-1)
        assert.ok(current.targets.has(next))
        const count=f.observers.length
        vm.runInContext(f.source,f.dom.getInternalVMContext())
        assert.equal(f.observers.length,count)
        f.dom.window.dispatchEvent(new f.dom.window.PageTransitionEvent('pagehide'))
        assert.equal(current.targets.size,0)
        f.dom.window.dispatchEvent(new f.dom.window.PageTransitionEvent('pageshow',{persisted:true}))
        assert.ok(f.observers.at(-1).targets.has(next))
    } finally {f.dom.window.close()}
})

test('browsers without observers show decorative media instead of leaving permanent placeholders', () => {
    const f=backgroundFixture({observer:false})
    try {assert.match(f.dom.window.document.querySelector('footer').style.backgroundImage,/footer\.webp/)}
    finally {f.dom.window.close()}
})
