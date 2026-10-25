'use strict'
const postcss = require('postcss')
const valueParser = require('postcss-value-parser')
const {PublicationManifestError} = require('../domain/PublicationManifest.cjs')
const fail = reason => {throw new PublicationManifestError(reason)}
const significant = nodes => nodes.filter(node => !['space', 'comment'].includes(node.type))

function extractCssReferences(css) {
    if (typeof css !== 'string') fail('INVALID')
    if (Buffer.byteLength(css) > 8 * 1024 * 1024) fail('LIMIT')
    const links = new Set(), imports = new Set(); let visited = 0
    const uri = node => {
        if (!node || !['string', 'word'].includes(node.type) || node.unclosed || !node.value.trim()
                || /[\\\u0000-\u001f]/.test(node.value)
                || node.type === 'word' && /[\s()'\"]/.test(node.value)) fail('UNSUPPORTED')
        return node.value
    }
    const inspect = (nodes, property, depth = 0) => {
        if (depth > 64) fail('LIMIT')
        for (const node of nodes) {
            if (++visited > 200000) fail('LIMIT')
            if (node.unclosed) fail('UNSUPPORTED')
            if (node.type !== 'function') continue
            if (node.value.includes('\\')) fail('UNSUPPORTED')
            const name = node.value.toLowerCase(), args = significant(node.nodes)
            if (name === 'url') {
                if (args.length !== 1) fail('UNSUPPORTED')
                links.add(uri(args[0])); continue
            }
            if (['image', 'cross-fade', '-webkit-cross-fade'].includes(name)) fail('UNSUPPORTED')
            if (name === 'image-set' || name === '-webkit-image-set') {
                // 字符串图片不经过url()，必须单独登记；动态变量不能冒充空资源集合。
                for (const arg of args) {
                    if (arg.type === 'string') links.add(uri(arg))
                    if (arg.type === 'function' && ['var', 'attr'].includes(arg.value.toLowerCase())) fail('UNSUPPORTED')
                }
            }
            if (name === 'attr' && !(property === 'content' && args.length === 1 && args[0].type === 'word'
                    && /^(?:title|data-[a-z0-9_-]+)$/i.test(args[0].value))) fail('UNSUPPORTED')
            inspect(node.nodes, property, depth + 1)
        }
    }
    try {
        const tree = postcss.parse(css, {from: undefined})
        tree.walkDecls(declaration => inspect(valueParser(declaration.value).nodes, declaration.prop.toLowerCase()))
        tree.walkAtRules(rule => {
            if (rule.name.includes('\\')) fail('UNSUPPORTED')
            const nodes = valueParser(rule.params).nodes
            if (rule.name.toLowerCase() !== 'import') {inspect(nodes, '@' + rule.name); return}
            const args = significant(nodes), first = args.shift()
            if (!first) fail('UNSUPPORTED')
            if (first.type === 'function' && first.value.toLowerCase() === 'url') {
                const target = significant(first.nodes)
                if (first.unclosed || target.length !== 1) fail('UNSUPPORTED')
                imports.add(uri(target[0]))
            } else imports.add(uri(first))
            if (args.some(node => node.type === 'string' || node.unclosed)) fail('UNSUPPORTED')
            inspect(args, '@import')
        })
        return {links: [...links], imports: [...imports]}
    } catch (error) {
        if (error instanceof PublicationManifestError) throw error
        fail('UNSUPPORTED')
    }
}
module.exports = {extractCssReferences}
