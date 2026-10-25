import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import test from 'node:test'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const modules = ['types', 'domain', 'application', 'infrastructure', 'trigger', 'app']
const files = directory => fs.existsSync(directory) ? fs.readdirSync(directory, {withFileTypes: true})
  .flatMap(entry => entry.isDirectory() ? files(path.join(directory, entry.name)) : [path.join(directory, entry.name)]) : []

test('no temporary external source roots remain', () => {
  for (const layer of modules) {
    const pom = fs.readFileSync(path.join(root, 'blog-server', `blog-${layer}`, 'pom.xml'), 'utf8')
    assert.doesNotMatch(pom, /<sourceDirectory>|<testSourceDirectory>|<directory>[^<]*\.\.\/src\//)
  }
  assert.equal(files(path.join(root, 'blog-server/src')).length, 0, 'legacy src must not carry code/resources')
})

test('production sources use only their physical layer and explicit imports', () => {
  const seen = new Set()
  for (const layer of modules) {
    const sources = files(path.join(root, 'blog-server', `blog-${layer}`, 'src/main/java')).filter(file => file.endsWith('.java'))
    assert.ok(sources.length > 0, layer)
    for (const file of sources) {
      const text = fs.readFileSync(file, 'utf8')
      const packageName = text.match(/^package\s+([\w.]+);/m)?.[1]
      const main = layer === 'app' && path.basename(file) === 'BlogServerApplication.java' && packageName === 'com.blogsystem'
      assert.ok(main || packageName?.startsWith(`com.blogsystem.${layer}.`), path.relative(root, file))
      assert.doesNotMatch(text, /^import\s+(?:static\s+)?[\w.]+\.\*;/m, path.relative(root, file))
      const type = `${packageName}.${path.basename(file)}`
      assert.ok(!seen.has(type), `duplicate source ${type}`)
      seen.add(type)
    }
  }
})
