'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {randomBytes} = require('node:crypto')

// Shared bootstrap: never read a second credential or accept a different key
// for the ingress and the private publication listener.
async function loadRuntimeSettings({root, env = process.env}) {
    const dev = env.BLOG_DEV === '1'
    const directory = path.resolve(env.PUBLICATION_STATE_DIRECTORY || path.join(root, '.runtime/publication'))
    const port = Number(env.PUBLICATION_PORT || (dev ? 5176 : 8081))
    const interval = Number(env.PUBLICATION_POLL_MS || 5000)
    if (!Number.isSafeInteger(port) || port < 1 || port > 65535 || !Number.isSafeInteger(interval) || interval < 1000)
        throw new Error('Invalid publication server settings')
    await fs.mkdir(directory, {recursive: true})
    let token = env.PUBLICATION_API_TOKEN
    if (!token && dev) {
        const filename = path.join(root, '.runtime/publication.key')
        try {token = await fs.readFile(filename, 'utf8')} catch (error) {
            if (error.code !== 'ENOENT') throw error
            token = randomBytes(32).toString('hex')
            await fs.mkdir(path.dirname(filename), {recursive: true})
            await fs.writeFile(filename, token, {flag: 'wx', mode: 0o600})
        }
    }
    if (typeof token !== 'string' || token.length < 32) throw new Error('A publication credential is required')
    return {root, directory, dev, token, port, interval}
}
module.exports = {loadRuntimeSettings}
