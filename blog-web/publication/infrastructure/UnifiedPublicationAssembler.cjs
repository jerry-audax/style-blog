'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {ownedName} = require('../domain/PublicationAttempt.cjs')
const {assertUnifiedBudget} = require('../domain/UnifiedPublicationLayout.cjs')
const {readPublicationTree, readFrozenPublicationFile, publicationEntriesDigest} = require('./PublicationManifestCollector.cjs')
const bad = () => new Error('Unified publication cannot be assembled')
const samePath = (a, b) => process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b
async function assembleUnified(snapshot, hexoOutput, target, {copy = fs.copyFile} = {}) {
    const state = path.dirname(snapshot.workRoot)
    if (hexoOutput !== path.join(snapshot.workRoot, '.build/blog') || path.dirname(target) !== path.join(state, 'stages')
            || !ownedName(path.basename(target), 'stage') || !samePath(await fs.realpath(target), target)
            || !(await fs.lstat(target)).isDirectory() || (await fs.readdir(target)).length) throw bad()
    const sources = new Map(), expected = new Map()
    const add = (name, bytes, filename) => {
        if (!name || name.split('/').some(part => !part || part.startsWith('.')) || name.split('/').length > 64
                || expected.has(name)) throw bad()
        expected.set(name, bytes); sources.set(name, filename)
    }
    for (const [name, bytes] of await readPublicationTree(hexoOutput)) add('blog/' + name, bytes, path.join(hexoOutput, name))
    for (const file of snapshot.files.filter(file => file.path.startsWith('.build/app/'))) {
        const name = file.path.slice('.build/app/'.length)
        if (name === '.vite' || name.startsWith('.vite/')) continue
        if (name === 'blog' || name.startsWith('blog/')) throw bad()
        add(name, await readFrozenPublicationFile(snapshot, file), path.join(snapshot.workRoot, file.path))
    }
    if (!expected.has('index.html') || !expected.has('blog/index.html')) throw bad()
    assertUnifiedBudget([...expected].map(([name, bytes]) => ({path: name, size: bytes.length})))
    const expectedDigest = publicationEntriesDigest(expected)
    for (const [name, filename] of sources) {
        const destination = path.join(target, name)
        await fs.mkdir(path.dirname(destination), {recursive: true})
        await copy(filename, destination)
    }
    if (publicationEntriesDigest(await readPublicationTree(target)) !== expectedDigest) throw bad()
    return expectedDigest
}
module.exports = {assembleUnified}
