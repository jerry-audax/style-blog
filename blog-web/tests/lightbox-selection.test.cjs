const {test} = require('node:test')
const assert = require('node:assert/strict')
const {registerLightboxSelection} = require('../tools/lightbox-selection.cjs')

function instance(config) {
    const filters = new Map()
    return {theme: {config: {...config}}, filters,
        extend: {filter: {register(name, callback) {filters.set(name, callback)}}}}
}

test('lightbox selection changes only the instance derived config when mediumZoom wins', async () => {
    const original = {medium_zoom: true, fancybox: true, other: 'preserved'}
    const first = instance(original), second = instance({medium_zoom: false, fancybox: true})
    registerLightboxSelection(first); registerLightboxSelection(second)
    assert.deepEqual(first.theme.config, original)
    assert.equal(typeof first.filters.get('before_generate'), 'function')
    await first.filters.get('before_generate')()
    assert.equal(first.theme.config.medium_zoom, true)
    assert.equal(first.theme.config.fancybox, false)
    assert.equal(first.theme.config.other, 'preserved')
    assert.equal(second.theme.config.fancybox, true)
    await second.filters.get('before_generate')()
    assert.equal(second.theme.config.fancybox, true)
    assert.deepEqual(original, {medium_zoom: true, fancybox: true, other: 'preserved'})
})

test('disabled or absent lightbox choices are preserved and repeated builds remain stable', async () => {
    for (const config of [{}, {medium_zoom: false, fancybox: false}, {medium_zoom: true, fancybox: false}]) {
        const hexo = instance(config)
        registerLightboxSelection(hexo)
        assert.equal(typeof hexo.filters.get('before_generate'), 'function')
        await hexo.filters.get('before_generate')()
        await hexo.filters.get('before_generate')()
        assert.deepEqual(hexo.theme.config, config)
    }
})
