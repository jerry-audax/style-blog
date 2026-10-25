'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
// Match the pinned Hexo 8.1.2 YAML renderer without initializing Hexo or running site plugins.
const renderYaml = require('hexo/dist/plugins/renderer/yaml')

async function validateHexoPaths(workRoot) {
    let config
    try {config = renderYaml({text: await fs.readFile(path.join(workRoot, '_config.yml'), 'utf8')})} catch {
        throw new Error('Invalid frozen Hexo configuration')
    }
    const samePath = (left, right) => process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right
    if (!config || typeof config !== 'object' || Array.isArray(config)
        || typeof (config.source_dir ?? 'source') !== 'string' || typeof config.public_dir !== 'string'
        || !samePath(path.resolve(workRoot, config.source_dir ?? 'source'), path.join(workRoot, 'source'))
        || !samePath(path.resolve(workRoot, config.public_dir), path.join(workRoot, '.build/blog'))
        || config.theme && (typeof config.theme !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(config.theme)))
        throw new Error('Hexo paths must stay in the frozen source and output locations')
}
module.exports = {validateHexoPaths}
