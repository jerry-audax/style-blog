'use strict'
const {createHash} = require('node:crypto')
const loopback = new Set(['127.0.0.1', 'localhost', '[::1]'])
const bad = () => new Error('Invalid unified ingress policy')
function origin(value, privateOnly) {
    if (typeof value !== 'string' || value.length > 2048 || /[\\\u0000-\u0020\u007f]/.test(value)) throw bad()
    let parsed
    try {parsed = new URL(value)} catch {throw bad()}
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash
            || parsed.pathname !== '/' || !/^(?:[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?|\[[0-9a-f:]+\])$/i.test(parsed.hostname)
            || parsed.hostname.includes('..') || privateOnly && (parsed.protocol !== 'http:' || !loopback.has(parsed.hostname))) throw bad()
    return parsed
}
function createUnifiedIngressPolicy(options) {
    if (!options || Object.keys(options).length !== 3 || !['listenPort', 'publicationOrigin', 'backendOrigin'].every(key => Object.hasOwn(options, key))
            || !Number.isSafeInteger(options.listenPort) || options.listenPort < 1 || options.listenPort > 65535) throw bad()
    const publication = origin(options.publicationOrigin, true), backend = origin(options.backendOrigin, false)
    const port = uri => Number(uri.port || (uri.protocol === 'https:' ? 443 : 80))
    if (port(publication) === options.listenPort || backend.origin === publication.origin
            || loopback.has(backend.hostname) && port(backend) === options.listenPort) throw bad()
    const deny = path => Object.freeze({match: path.endsWith('/') ? 'prefix' : 'exact', path, action: 'deny'})
    const proxy = (path, upstream, timeout) => Object.freeze({match: 'prefix', path, action: 'proxy', upstream, buffering: false, timeout})
    const routes = Object.freeze([deny('/internal'), deny('/internal/'), deny('/uploads'), deny('/uploads/'),
        proxy('/api/ai/', 'backend', 300), proxy('/api/admin/music/', 'backend', 195), proxy('/api/', 'backend', 60), proxy('/', 'publication', 60)])
    const value = {protocolVersion: 1, listenPort: options.listenPort, publicationOrigin: publication.origin, backendOrigin: backend.origin, routes}
    return Object.freeze({...value, digest: createHash('sha256').update(JSON.stringify(value)).digest('hex')})
}
module.exports = {createUnifiedIngressPolicy}
