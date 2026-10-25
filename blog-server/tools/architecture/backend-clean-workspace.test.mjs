import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'
import test from 'node:test'

const root = fileURLToPath(new URL('../../../', import.meta.url))

test('artifact validation works in a clean workspace without the local evidence directory', t => {
  const repository = fs.mkdtempSync(path.join(os.tmpdir(), 'arch-clean-workspace-'))
  t.after(() => {
    assert.equal(path.dirname(fs.realpathSync(repository)), fs.realpathSync(os.tmpdir()))
    assert.ok(path.basename(repository).startsWith('arch-clean-workspace-'))
    fs.rmSync(repository, {recursive: true})
  })
  const tools = path.join(repository, 'blog-server/tools/architecture')
  fs.mkdirSync(tools, {recursive: true})
  for (const file of ['backend-artifact.test.mjs','verify-test-supply.mjs']) {
    fs.copyFileSync(path.join(root, 'blog-server/tools/architecture', file), path.join(tools, file))
  }
  const jar = 'blog-server/blog-app/target/blog-app.jar'
  fs.mkdirSync(path.dirname(path.join(repository, jar)), {recursive: true})
  fs.copyFileSync(path.join(root, jar), path.join(repository, jar))
  assert.ok(!fs.existsSync(path.join(repository, '.artifacts')))
  const {NODE_TEST_CONTEXT: inheritedRunnerContext, ...environment} = process.env
  const executed = spawnSync(process.execPath, ['--test','--test-name-pattern=^appArtifactContains', path.join(tools, 'backend-artifact.test.mjs')], {encoding: 'utf8', env: environment})
  assert.equal(executed.status, 0, executed.stdout + executed.stderr)
  const output = executed.stdout
  assert.match(output, /# pass 1/)
  assert.ok(!fs.existsSync(path.join(repository, '.artifacts')), 'Inspection must leave no local evidence directory dependency')
})
