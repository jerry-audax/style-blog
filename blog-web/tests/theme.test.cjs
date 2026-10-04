const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const postcss = require('postcss')

// Source-level style contracts and conservative contrast checks; not a
// substitute for browser screenshots, GPU measurements or a full WCAG audit.
const css = postcss.parse(fs.readFileSync(path.join(__dirname, '../source/css/site.css'), 'utf8'))
const value = (selector, property, media = '') => {
    let found
    css.walkRules(rule => {
        if (!rule.selectors.includes(selector)) return
        const condition = rule.parent.type === 'atrule' ? rule.parent.params : ''
        if (condition !== media) return
        rule.walkDecls(property, decl => {
            found = decl
        })
    })
    return found
}

test('light theme uses a light base and hides particles without forcing dark nav/footer tokens', () => {
    assert.equal(value(':root', '--site-background').value, '#f7f9fe')
    assert.equal(value('html[data-theme="dark"]', '--site-background').value, '#1b1722')
    assert.equal(value('html[data-theme="light"] #site-particles-background', 'display').value, 'none')
    assert.equal(value('#page-header #nav', '--anzhiyu-fontcolor'), undefined)
    assert.equal(value('#footer', '--anzhiyu-fontcolor'), undefined)
})

test('dark cards have translucent black backgrounds without fading or hiding original components', () => {
    for (const [name, fill] of [
        ['--site-card-surface', 'rgb(14 14 17 / 0.62)'],
        ['--site-reading-surface', 'rgb(14 14 17 / 0.8)'],
        ['--site-secondary-surface', 'rgb(23 23 28 / 0.7)'],
    ]) assert.equal(value('html[data-theme="dark"]', name).value, fill)
    for (const selector of [
        '#recent-posts > .recent-post-item', '#aside-content .card-widget', '#random-banner',
        '#swiper_container', '#categoryBar',
    ]) assert.equal(value(`html[data-theme="dark"] ${selector}`, 'background').value, 'var(--site-card-surface)')
    for (const selector of ['div#post', 'div#page', '#console .console-card', '#sidebar #sidebar-menus',
        '#page-header.not-top-img > #nav'])
        assert.equal(value(`html[data-theme="dark"] ${selector}`, 'background').value, 'var(--site-reading-surface)')
    for (const selector of ['#recent-posts > .recent-post-item', 'div#post', 'div#page', '#aside-content .card-widget'])
        assert.equal(value(`html[data-theme="dark"] ${selector}`, 'opacity'), undefined)
    for (const selector of ['#home_top', '#random-banner', '#swiper_container', '#bannerGroup',
        '#recent-posts > .recent-post-item', '#aside-content .card-widget'])
        assert.equal(value(`html[data-theme="dark"] ${selector}`, 'display'), undefined)
    assert.equal(value('html[data-theme="dark"] #console .console-mask', 'backdrop-filter').value, 'none')
    assert.equal(value('html[data-theme="dark"] body[data-type="music"] div#page', 'background').value, 'transparent')
})

test('black translucent panels keep text and controls readable over the brighter particle background', () => {
    const tokens = 'html[data-theme="dark"]'
    const toRgb = hex => [1, 3, 5].map(offset => parseInt(hex.slice(offset, offset + 2), 16))
    const luminance = rgb => rgb.map(channel => {
        const s = channel / 255
        return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
    }).reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0)
    const contrast = (a, b) => {
        const values = [luminance(a), luminance(b)].sort((a, b) => b - a)
        return (values[0] + 0.05) / (values[1] + 0.05)
    }
    const alpha = Number(value(':root', '--site-particle-opacity').value)
    const backdrop = toRgb(value(tokens, '--site-background').value)
        .map(channel => channel * (1 - alpha) + 255 * alpha)
    assert.equal(alpha, 0.55, 'particles are more visible than the former 0.2 setting')
    for (const surface of ['--site-card-surface', '--site-reading-surface', '--site-secondary-surface']) {
        const [, red, green, blue, opacity] = value(tokens, surface).value.match(/^rgb\((\d+) (\d+) (\d+) \/ ([\d.]+)\)$/)
        const background = [red, green, blue].map((channel, index) =>
            Number(channel) * Number(opacity) + backdrop[index] * (1 - Number(opacity)))
        for (const text of ['--anzhiyu-fontcolor', '--anzhiyu-secondtext'])
            assert.ok(contrast(toRgb(value(tokens, text).value), background) >= 4.5, `${text} on ${surface}`)
        assert.ok(contrast(toRgb('#A5B4FC'), background) >= 4.5, `dark accent on ${surface}`)
    }
    assert.ok(contrast(toRgb('#4F46E5'), toRgb('#f7f9fe')) >= 4.5, 'light accent over the light background')
    for (const [color, selector] of [['#A5B4FC', tokens], ['#4F46E5', ':root']])
        assert.ok(contrast(toRgb(color), toRgb(value(selector, '--site-accent-foreground').value)) >= 4.5,
            'primary control foreground must match its theme')
    assert.equal(value('#site-particles-background canvas', 'opacity').value, 'var(--site-particle-opacity)')
})

test('reduced transparency restores opaque surfaces, leaving the chosen particle parameters alone', () => {
    for (const name of ['--site-card-surface', '--site-reading-surface', '--site-secondary-surface'])
        assert.match(value('html[data-theme="dark"]', name, '(prefers-reduced-transparency: reduce)').value, /^#[a-f0-9]{6}$/)
})

test('console state colors override upstream hover styling and expose loading/error feedback', () => {
    const base = '#console .button-group #consoleMusic'
    assert.equal(value(`${base}:hover .music-switch`, 'background').important, true)
    const playing = value(`${base}[data-state="playing"] .music-switch`, 'background')
    assert.equal(playing.important, true)
    assert.equal(playing.value, 'var(--anzhiyu-main)')
    assert.equal(value(`${base}[data-state="playing"] .music-switch`, 'color').value, 'var(--site-accent-foreground)')
    assert.match(value(`${base}[data-state="loading"] .music-switch`, 'border').value, /dashed/)
    assert.match(value(`${base}[data-state="error"] .music-switch`, 'border').value, /solid/)
    assert.equal(value('.site-console-music-status', 'opacity'), undefined)
})

test('floating utility menus keep a contrast floor; main tint and button foreground follow both themes', () => {
    for (const selector of ['#local-search .search-dialog', '#nav .menus_item_child', '#rightMenu'])
        assert.equal(value(`html[data-theme="dark"] ${selector}`, 'background').value, 'var(--site-popover-surface)')
    assert.equal(value('html[data-theme="light"]', '--anzhiyu-theme-op').value, '#4f46e523')
    assert.equal(value('html[data-theme="dark"]', '--anzhiyu-theme-op').value, '#a5b4fc23')
    assert.equal(value('html[data-theme]', '--anzhiyu-hovertext').value, 'var(--anzhiyu-main)')
    assert.equal(value('html[data-theme]', '--btn-color').value, 'var(--site-accent-foreground)')
    for (const selector of ['#pagination .page-number.current', '.catalog-list-item.select a',
        '#nav .menus_item:hover > a.site-page', '#console .card-tag-cloud a:hover',
        '#rightMenu .rightMenu-group .rightMenu-item:hover']) {
        const foreground = value(`html[data-theme] ${selector}`, 'color')
        assert.equal(foreground.value, 'var(--site-accent-foreground)')
        assert.equal(foreground.important, true, 'replace upstream forced-white state text')
    }
})
