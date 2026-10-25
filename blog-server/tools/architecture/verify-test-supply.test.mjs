import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {inspectTestSupply, assertTestReports} from './verify-test-supply.mjs'

function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'blog-arch-tests-'))
  t.after(() => {
    assert.equal(path.dirname(fs.realpathSync(directory)), fs.realpathSync(os.tmpdir()))
    assert.ok(path.basename(directory).startsWith('blog-arch-tests-'))
    fs.rmSync(directory, {recursive: true})
  })
  return directory
}

test('missing test supply fails closed rather than accepting a clean checkout', t => {
  const root = fixture(t)
  assert.throws(() => inspectTestSupply(root), /Missing test sources/)
})

test('all six modules and their test classes must be represented', t => {
  const root = fixture(t)
  for (const module of ['types','domain','application','infrastructure','trigger','app']) {
    const directory = path.join(root, `blog-server/blog-${module}/src/test/java/fixture`)
    fs.mkdirSync(directory, {recursive: true})
    fs.writeFileSync(path.join(directory, 'ExampleTest.java'), `package fixture.${module}; class ExampleTest { @Test void provesSomething() {} }`)
  }
  const supply = inspectTestSupply(root)
  assert.equal(supply.length, 6)
  assert.throws(() => assertTestReports(root, supply), /Missing test report/)
  for (const entry of supply) {
    const directory = path.join(root, `blog-server/blog-${entry.module}/target/surefire-reports`)
    fs.mkdirSync(directory, {recursive: true})
    fs.writeFileSync(path.join(directory, `TEST-${entry.type}.xml`), `<testsuite name="${entry.type}" tests="1" failures="0" errors="0" skipped="0"></testsuite>`)
  }
  assert.deepEqual(assertTestReports(root, supply), {classes: 6, tests: 6, failures: 0, errors: 0, skipped: 0})
  const first = supply[0]
  const report = path.join(root, `blog-server/blog-${first.module}/target/surefire-reports/TEST-${first.type}.xml`)
  fs.writeFileSync(report, `<testsuite name="${first.type}" tests="0" failures="0" errors="0" skipped="0"/>`)
  assert.throws(() => assertTestReports(root, supply), /Zero or failed tests/)
  fs.writeFileSync(report, `<testsuite name="${first.type}" tests="1" failures="0" errors="0" skipped="1"/>`)
  assert.throws(() => assertTestReports(root, supply), /Unexpected all-skipped/)
})
