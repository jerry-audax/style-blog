import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import {fileURLToPath} from 'node:url'
import {execFileSync} from 'node:child_process'
import test from 'node:test'
import {inspectTestSupply, assertTestReports, modules} from './verify-test-supply.mjs'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const applicationJar = path.join(root, 'blog-server/blog-app/target/blog-app.jar')
const entries = jar => execFileSync('jar', ['tf', jar], {encoding: 'utf8'}).split(/\r?\n/).filter(Boolean)

test('appArtifactContainsLayerJarsAndStableMainClass without duplicate old implementations', t => {
  assert.ok(fs.existsSync(applicationJar), 'Build the reactor application artifact first')
  const appEntries = entries(applicationJar)
  assert.ok(appEntries.includes('BOOT-INF/classes/com/blogsystem/BlogServerApplication.class'))
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'blog-arch-jar-inspect-'))
  t.after(() => {
    assert.equal(path.dirname(fs.realpathSync(directory)), fs.realpathSync(os.tmpdir()))
    assert.ok(path.basename(directory).startsWith('blog-arch-jar-inspect-'))
    fs.rmSync(directory, {recursive: true})
  })
  const libraries = modules.filter(module => module !== 'app').map(module => `BOOT-INF/lib/blog-${module}-0.0.1-SNAPSHOT.jar`)
  for (const library of libraries) assert.ok(appEntries.includes(library), library)
  execFileSync('jar', ['xf', applicationJar, 'META-INF/MANIFEST.MF', ...libraries], {cwd: directory})
  const manifest = fs.readFileSync(path.join(directory, 'META-INF/MANIFEST.MF'), 'utf8')
  assert.match(manifest, /Start-Class: com\.blogsystem\.BlogServerApplication\r?\n/)
  assert.match(manifest, /Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?\n/)
  const seen = new Set()
  for (const group of [appEntries.map(entry => entry.replace(/^BOOT-INF\/classes\//, '')),
    ...libraries.map(library => entries(path.join(directory, library)))]) {
    for (const entry of group.filter(entry => entry.startsWith('com/blogsystem/') && entry.endsWith('.class'))) {
      assert.ok(!seen.has(entry), `Duplicate project class: ${entry}`)
      seen.add(entry)
      assert.ok(entry === 'com/blogsystem/BlogServerApplication.class'
        || /^com\/blogsystem\/(types|domain|application|infrastructure|trigger|app)\//.test(entry), `Legacy class: ${entry}`)
    }
  }
  for (const layer of modules) assert.ok([...seen].some(entry => entry.startsWith(`com/blogsystem/${layer}/`)), layer)
  assert.ok(seen.size > 350, 'Incomplete application class inventory')
})

test('every supplied test class has an executed non-failing reactor report', () => {
  const counts = assertTestReports(root, inspectTestSupply(root))
  assert.ok(counts.classes >= 100)
  assert.ok(counts.tests > counts.skipped)
  console.log(`Reactor evidence: ${JSON.stringify(counts)}`)
})
