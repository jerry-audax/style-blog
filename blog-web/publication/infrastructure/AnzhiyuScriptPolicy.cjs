'use strict'
const acorn = require('acorn')
const {createHash} = require('node:crypto')
const {PublicationManifestError} = require('../domain/PublicationManifest.cjs')
const unsupported = () => new PublicationManifestError('UNSUPPORTED_SCRIPT')
// 名称对应已核对的上游模板/自有源码，不从候选发布产物自动学习白名单。
const INLINE = new Map([
    ['3b625084d5fd89139eabcb5b5f85364df3378b2badf5823cc4dd3c4161228477', 'head/config_site: local storage and theme helpers'],
    ['a0c1f92ceeb20498c372888bbb8703cea62c2e5b210e47d696dff5d9db839a94', 'site-integration: background variant'],
    ['3cde18f0e9cc5189b55e9140fd9f1f1f60d6fbeaea5d3f7191cb820fd0bf5a31', 'includes/loading: loading state'],
    ['639510c4b94dae30d7ae97748aa39cccfa6834ce0bfdbbe6f63cb8c7a9146df6', 'third-party/runtime/runtime-js: clock and fixed public badge'],
    ['a43122022c54305f7774be0728abd5160f0c900ab76ce71cfe06c5e636ed649c', 'third-party/pjax: replay already inspected scripts'],
    ['492e8ad5d21e237ea84db0fde5b676ca5637bd6cff8bcdff13253ad8bcee8fc1', 'includes/anzhiyu/swiper: slider controls'],
    ['58f132d2adaaf6cfa3ec3371447d4890e55bc40ffeae595e5f4614735345c38e', 'includes/post/ptool: copy current page URL']
])
const FILES = new Map(Object.entries({
    'js/site-bridge.js': '2bfd3552fa5ca54eb367af2540e0755198cfe3949c6bdbceb54ab0eb54e3224b',
    'js/site-click-effect.js': '70b5291cd8902db60b4f754bc04db2e10a8544f9c1985ad42ceed682622ecfb0',
    'js/site-media-loading.js': '83e7d4782dd0d468f86d1d1265e231ac76b3afe39fd425d96020a8b064c3cfca',
    'js/site-music.js': '4105f4cc33856bf36daaddf0c9a28584aba519c2576c027756fdb2cbb9762ce4',
    'js/site-surprise.js': 'ded95670c78f1ebdde5e49bc165113a498bdbf44fd46c9047ccbb5ab514080d8',
    'js/utils.js': 'fa472157762fd71f6af397a57a6bd39195f09991dd526358f3f8a2e0027e4842',
    'js/main.js': '81b48948681dae6aace33e4afd054582ff9dc7a9e990805023563c4d39562463',
    'js/tw_cn.js': '53ab00abe26ccb088bb1be259fef3d20f71c97855ba504e79d0d9dee8ea4a5f7',
    'js/search/local-search.js': 'd09610c7a8c842f73781c202d56a14e9e7c502ca1852d8f2aeea7f6c3f7ed1e5',
    'js/anzhiyu/right_click_menu.js': '76ad3ca74178d697a66a70c252adc1e762d49cf5d66554bcf005f3f923b71292',
    'pluginsSrc/medium-zoom/dist/medium-zoom.min.js': '6603320c02180d81b16dca4971f52763035ebc6ff18bd38729a47ff062be8d67',
    'pluginsSrc/node-snackbar/dist/snackbar.min.js': '34eaac416486ba5585e23d327c5945c24f6b985a40a115799e8746a881f28d4a',
    'pluginsSrc/anzhiyu-blog-static/js/APlayer.min.js': 'e80cbd75db6bc8d821af97ebfd641d16d2eb2911a103f15aa77bb4d63cb55a73',
    'pluginsSrc/hexo-anzhiyu-music/assets/js/Meting2.min.js': '94e50ffa5f6a2db31368260a2cd74beac05917ef0966b1715e65eb55bcd2823d',
    'pluginsSrc/pjax/pjax.min.js': 'c9819844d18cf059f52a7da53f786d418348cc662dd7e243f009ec559bac85e2',
    'pluginsSrc/anzhiyu-theme-static/waterfall/waterfall.js': '9346717edb8b8f4664ffe8233bb5458c8d4720442d1e27e2a3347d5c33f2e767',
    'pluginsSrc/anzhiyu-theme-static/swiper/swiper.min.js': '7defe6a9e6bafc47b031c2897487bcdf51718012d6b982745dd917eb7609cc4e',
    'vendor/qrcode.min.js': 'c541ef06327885a8415bca8df6071e14189b4855336def4f36db54bde8484f36'
}))
const EVENTS = new Set([
    "this.media='all'", 'document.getElementById("loading-box").classList.add("loaded")',
    'toRandomPost()', 'anzhiyu.switchConsole();', 'anzhiyu.hideAsideBtn()', 'anzhiyu.musicToggle()',
    'anzhiyu.hideConsole()', 'anzhiyu.scrollToDest(0,500)', 'anzhiyu.scrollToDest(0, 500)', 'anzhiyu.scrollCategoryBarToRight()',
    'window.event.cancelBubble=true;', 'copyCurrentPageUrl()', 'anzhiyu.toPage()', 'anzhiyu.changeSayHelloText()',
    'anzhiyu.addRewardMask()', 'anzhiyu.removeRewardMask()', 'anzhiyu.addRandomCommentInfo()',
    "value=value.replace(/[^0-9]/g,'')", "this.value=this.value.replace(/[^u4e00-u9fa5w]/g,'')"
])
const RANDOM_TEMPLATE = `window.toRandomPost = function () {
      const paths = [];
      const target = paths.length ? paths[Math.floor(Math.random() * paths.length)] : '/blog/archives/';
      if (window.pjax) window.pjax.loadUrl(target); else window.location.assign(target);
    };`
function pure(node, depth = 0) {
    if (!node || depth > 64) throw unsupported()
    if (node.type === 'Identifier' && node.name === 'undefined') return null
    if (node.type === 'Literal' && !node.regex && !node.bigint) return node.value
    if (node.type === 'UnaryExpression' && ['-', '+'].includes(node.operator) && node.argument.type === 'Literal' && typeof node.argument.value === 'number')
        return node.operator === '-' ? -node.argument.value : node.argument.value
    if (node.type === 'ArrayExpression') return node.elements.map(child => pure(child, depth + 1))
    if (node.type === 'ObjectExpression') {
        const value = Object.create(null)
        for (const entry of node.properties) {
            if (entry.type !== 'Property' || entry.computed || entry.method || entry.kind !== 'init') throw unsupported()
            const key = entry.key.type === 'Identifier' ? entry.key.name : entry.key.value
            if (typeof key !== 'string' || ['__proto__', 'constructor', 'prototype'].includes(key)) throw unsupported()
            value[key] = pure(entry.value, depth + 1)
        }
        return value
    }
    throw unsupported()
}
class AnzhiyuScriptPolicy {
    inspect(code, context = {}) {
        if (context.kind === 'remote') {
            // 上游统计脚本只承载计数；不获取远端内容，不把它登记为本站媒体。
            if (context.uri !== '//busuanzi.ibruce.info/busuanzi/2.3/busuanzi.pure.mini.js') throw unsupported()
            return {links: [], texts: [], dependencies: []}
        }
        if (typeof code !== 'string' || Buffer.byteLength(code) > 8 * 1024 * 1024) throw unsupported()
        const hash = createHash('sha256').update(code).digest('hex')
        const name = (context.filename || '').replace(/^(?:source|blog)\//, '')
        // 此精确版本使用 ES2021 ||=，acorn7 不支持。已审阅：唯一图片来源是
        // DOM 的 data-site-lazy-background，由 HTML 采集器直接登记，脚本无内嵌 URI。
        if (context.kind === 'file' && name === 'js/site-media-loading.js' && FILES.get(name) === hash)
            return {links: [], texts: [], dependencies: []}
        let ast
        try {ast = acorn.parse(code, {ecmaVersion: 2020, sourceType: context.kind === 'vite' ? 'module' : 'script'})} catch {throw unsupported()}
        const links = [], texts = [], dependencies = []
        if (context.kind === 'event') {
            if (EVENTS.has(code)) return {links, texts, dependencies}
            if (context.event === 'onclick' && ast.body.length === 1) {
                const call = ast.body[0].expression
                if (call?.type === 'CallExpression' && call.callee.type === 'MemberExpression' && !call.callee.computed
                        && call.callee.object.type === 'Identifier'
                        && ['pjax.loadUrl', 'rm.copyPageUrl'].includes(call.callee.object.name + '.' + call.callee.property.name)
                        && call.arguments.length === 1 && call.arguments[0].type === 'Literal'
                        && typeof call.arguments[0].value === 'string' && /^(?:\/blog\/|https?:\/\/)/.test(call.arguments[0].value))
                    return {links: [call.arguments[0].value], texts, dependencies}
            }
            // 回退图是两条纯赋值，禁止顺带调用函数或计算 URL。
            if (context.event === 'onerror' && ast.body.length === 2) {
                const assignments = ast.body.map(node => node.expression)
                const property = (node, name) => node?.type === 'AssignmentExpression' && node.operator === '='
                    && (node.left.type === 'MemberExpression' && !node.left.computed
                        && node.left.object.type === 'ThisExpression' && node.left.property.name === name
                        || node.left.type === 'Identifier' && node.left.name === name)
                const clear = assignments.find(node => property(node, 'onerror'))
                const image = assignments.find(node => property(node, 'src'))
                if (clear?.right.type === 'Literal' && clear.right.value === null
                        && image?.right.type === 'Literal' && typeof image.right.value === 'string')
                    return {links: [image.right.value], texts, dependencies}
                if (clear?.right.type === 'Literal' && clear.right.value === null
                        && image?.right.type === 'Identifier' && image.right.name === 'undefined') return {links, texts, dependencies}
            }
            if (/^window\.open\(/.test(code) && ast.body.length === 1) {
                const expression = ast.body[0].expression
                if (expression?.type === 'CallExpression' && expression.callee.type === 'MemberExpression'
                        && expression.callee.object.name === 'window' && expression.callee.property.name === 'open'
                        && !expression.callee.computed && expression.arguments.length === 2
                        && expression.arguments.every(arg => arg.type === 'Literal' && typeof arg.value === 'string')
                        && expression.arguments[1].value === '_blank') return {links: [expression.arguments[0].value], texts, dependencies}
            }
            throw unsupported()
        }
        const declarations = ast.body
        // 自有 after_generate 随文章改变数组，只有该数组可变，执行结构必须逐字一致。
        if (context.kind === 'file' && name === 'anzhiyu/random.js') {
            const array = declarations[0]?.expression?.right?.body?.body?.[0]?.declarations?.[0]?.init
            if (array?.type !== 'ArrayExpression' || code.slice(0, array.start) + '[]' + code.slice(array.end) !== RANDOM_TEMPLATE) throw unsupported()
            const paths = pure(array)
            if (!paths.every(value => typeof value === 'string' && value.startsWith('/blog/'))) throw unsupported()
            return {links: paths, texts, dependencies}
        }
        if (context.kind === 'inline' && declarations.length === 1 && declarations[0].type === 'VariableDeclaration'
                && declarations[0].declarations.length === 1) {
            const declaration = declarations[0].declarations[0]
            if (declaration.id.type === 'Identifier' && ['GLOBAL_CONFIG', 'GLOBAL_CONFIG_SITE'].includes(declaration.id.name))
                return {links, texts: [pure(declaration.init)], dependencies}
        }
        const trusted = context.kind === 'inline' ? INLINE.has(hash)
            : context.kind === 'vite' || FILES.get(name) === hash
        if (!trusted) throw unsupported()
        // AST 仅提取字面 URI，不执行代码。/file/ 验证器前缀不能当作资源。
        const stack = [ast]; let count = 0
        const literal = value => {
            if (/^(?:https?:\/\/|\/\/|\/file\/.)/.test(value) && !/[\s<>]/.test(value)) links.push(value)
            else if (/<(?:img|video|audio|source|style)\b/i.test(value)) texts.push(value)
            else if (value.includes('/file/') && value !== '/file/') throw unsupported()
        }
        while (stack.length) {
            const node = stack.pop()
            if (++count > 400000) throw unsupported()
            if (node.type === 'Literal' && typeof node.value === 'string') {
                literal(node.value)
            }
            if (node.type === 'TemplateLiteral') {
                if (!node.expressions.length) literal(node.quasis[0].value.cooked || '')
                else if (node.quasis.some(quasi => quasi.value.cooked?.includes('/file/'))) throw unsupported()
            }
            if (context.kind === 'vite' && ['ImportDeclaration', 'ExportAllDeclaration', 'ExportNamedDeclaration', 'ImportExpression'].includes(node.type) && node.source) {
                if (node.source.type !== 'Literal' || typeof node.source.value !== 'string' || !node.source.value.startsWith('.')) throw unsupported()
                dependencies.push(node.source.value)
            }
            for (const value of Object.values(node)) {
                if (Array.isArray(value)) {
                    for (const child of value) if (child?.type) stack.push(child)
                } else if (value?.type) stack.push(value)
            }
        }
        return {links, texts, dependencies}
    }
}
module.exports = {AnzhiyuScriptPolicy}
