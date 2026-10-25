import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import {fileURLToPath} from 'node:url'
import test from 'node:test'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const at = p => path.join(root, p)

test('backend does not expose an unrelated Vite package as its npm entrypoint', () => {
  assert.ok(!fs.existsSync(at('blog-server/package.json')))
  assert.ok(!fs.existsSync(at('blog-server/package-lock.json')))
  const runtime = JSON.parse(fs.readFileSync(at('blog-server/music-runtime/package.json'), 'utf8'))
  assert.equal(runtime.dependencies['@music163/ncm-cli'], '0.1.7')
  for (const script of ['runner.cjs', 'protocol.cjs', 'state.cjs', 'setup.cjs']) {
    assert.ok(fs.statSync(at(`blog-server/music-runtime/${script}`)).size > 0, script)
  }
})

test('frontend source directory is not a private-key storage location', () => {
  assert.ok(!fs.existsSync(at('blog-web/.pem')), 'Move the private credential outside the frontend source directory')
  const component = JSON.parse(fs.readFileSync(at('blog-web/components.json'), 'utf8'))
  assert.ok(component.registries['@vue-bits'])
  assert.ok(!fs.existsSync(at('components.json')))
})

test('public deployment repair consumes its frontend-owned branding script', () => {
  const dockerfile = fs.readFileSync(at('blog-web/deploy/Dockerfile.runtime-repair'), 'utf8')
  const source = dockerfile.match(/^COPY ([^\r\n ]+) \/app\/scripts\/production-branding\.js\r?$/m)?.[1]
  assert.ok(source, 'The repair image must include the branding plugin')
  assert.ok(source.startsWith('blog-web/deploy/'), 'The Docker build input belongs to the public frontend')
  const plugin = fs.readFileSync(at(source), 'utf8')
  let beforeGenerate
  const config = {preloader: {avatar: 'http://127.0.0.1/old-avatar'}}
  vm.runInNewContext(plugin, {hexo: {theme: {config}, extend: {filter: {register(name, callback) {
    assert.equal(name, 'before_generate'); beforeGenerate = callback
  }}}}})
  assert.equal(typeof beforeGenerate, 'function')
  beforeGenerate()
  assert.equal(config.preloader.avatar, '/blog/img/avatar.jpg')
})
