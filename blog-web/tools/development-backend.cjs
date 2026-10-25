'use strict'
const fs = require('node:fs')
const path = require('node:path')
const {parseEnv} = require('node:util')
const fallback = 'http://localhost:8080'

function developmentBackend({root, env = process.env}) {
    if (Object.hasOwn(env, 'BLOG_API_URL')) return env.BLOG_API_URL || fallback
    try {
        return parseEnv(fs.readFileSync(path.join(root, '.env.development.local'), 'utf8')).BLOG_API_URL || fallback
    } catch (error) {
        if (error.code !== 'ENOENT') throw error
        return fallback
    }
}
module.exports = {developmentBackend}
