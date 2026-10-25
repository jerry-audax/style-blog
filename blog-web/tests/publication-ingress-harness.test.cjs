'use strict'
const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const {createContext, assertOwned} = require('../tools/test-publication-ingress.cjs')

test('isolated Docker context uses allowlisted code and synthetic site, never project env or posts', async () => {
    assert.equal(typeof createContext, 'function')
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-ingress-harness-'))
    try {
        await createContext({root: path.resolve(__dirname, '..'), directory})
        assert.equal((await fs.readFile(path.join(directory, 'Dockerfile'), 'utf8')),
            await fs.readFile(path.join(__dirname, '../Dockerfile'), 'utf8'))
        assert.equal((await fs.readFile(path.join(directory, 'package-lock.json'), 'utf8')),
            await fs.readFile(path.join(__dirname, '../package-lock.json'), 'utf8'))
        assert.match(await fs.readFile(path.join(directory, '_config.yml'), 'utf8'), /url: https:\/\/fixture\.invalid/)
        assert.match(await fs.readFile(path.join(directory, 'source/index.html'), 'utf8'), /synthetic-ingress-fixture/)
        assert.deepEqual(await fs.readdir(path.join(directory, 'source')), ['index.html'])
        const visit = async name => {
            for (const entry of await fs.readdir(name, {withFileTypes: true})) {
                assert.ok(!entry.isSymbolicLink())
                assert.doesNotMatch(entry.name, /^(?:env|\.env.*|node_modules|\.runtime|\.git)$|\.(?:pem|key)$/i)
                if (entry.isDirectory()) await visit(path.join(name, entry.name))
            }
        }
        await visit(directory)
    } finally {
        assert.equal(path.dirname(directory), os.tmpdir()); assert.match(path.basename(directory), /^publication-ingress-harness-/)
        await fs.rm(directory, {recursive: true, force: true})
    }
})

test('Docker cleanup requires exact task label and object identity', () => {
    assert.equal(typeof assertOwned, 'function')
    assert.doesNotThrow(() => assertOwned({Id: 'owned-id', Config: {Labels: {'blog.task': 'task-uuid'}}}, 'owned-id', 'task-uuid'))
    for (const value of [{Id: 'other', Config: {Labels: {'blog.task': 'task-uuid'}}},
        {Id: 'owned-id', Config: {Labels: {'blog.task': 'another-task'}}}, {Id: 'owned-id'}])
        assert.throws(() => assertOwned(value, 'owned-id', 'task-uuid'))
})
