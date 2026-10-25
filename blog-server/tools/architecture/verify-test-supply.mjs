import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

export const modules = ['types','domain','application','infrastructure','trigger','app']
const root = fileURLToPath(new URL('../../../', import.meta.url))
const files = directory => fs.existsSync(directory) ? fs.readdirSync(directory, {withFileTypes: true})
  .flatMap(entry => entry.isDirectory() ? files(path.join(directory, entry.name)) : [path.join(directory, entry.name)]) : []
const optInSkips = new Set([
  'com.blogsystem.infrastructure.gateway.imgbed.asset.ImgBedSmokeTest',
  'com.blogsystem.app.it.music.infrastructure.cli.MusicSmokeTest',
  'com.blogsystem.app.it.shared.infrastructure.configuration.PostgresRepositoryIntegrationTest'
])

export function inspectTestSupply(repository = root) {
  return modules.flatMap(module => {
    const sources = files(path.join(repository, `blog-server/blog-${module}/src/test/java`))
      .filter(file => /(?:Test|Tests)\.java$/.test(file))
    assert.ok(sources.length > 0, `Missing test sources for blog-${module}; restore the approved test supply, do not bypass the gate.`)
    return sources.map(file => {
      const source = fs.readFileSync(file, 'utf8')
      const packageName = source.match(/^package\s+([\w.]+);/m)?.[1]
      assert.ok(packageName && /@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b/.test(source), `No executable test declarations: ${file}`)
      return {module, file, type: `${packageName}.${path.basename(file, '.java')}`}
    })
  })
}

export function assertTestReports(repository = root, supply = inspectTestSupply(repository)) {
  const counts = {classes: 0, tests: 0, failures: 0, errors: 0, skipped: 0}
  for (const entry of supply) {
    const file = path.join(repository, `blog-server/blog-${entry.module}/target/surefire-reports/TEST-${entry.type}.xml`)
    assert.ok(fs.existsSync(file), `Missing test report: ${entry.type}`)
    const header = fs.readFileSync(file, 'utf8').match(/<testsuite\s[^>]*>/)?.[0] ?? ''
    const attributes = Object.fromEntries([...header.matchAll(/([\w]+)="([^"]*)"/g)].map(match => [match[1], match[2]]))
    assert.equal(attributes.name, entry.type, `Wrong report identity: ${entry.type}`)
    const values = Object.fromEntries(['tests','failures','errors','skipped'].map(key => [key, Number(attributes[key])]))
    assert.ok(Object.values(values).every(value => Number.isSafeInteger(value) && value >= 0)
      && values.tests > 0 && values.failures === 0 && values.errors === 0 && values.skipped <= values.tests, `Zero or failed tests: ${entry.type}`)
    if (values.skipped === values.tests) assert.ok(optInSkips.has(entry.type), `Unexpected all-skipped test class: ${entry.type}`)
    assert.ok(fs.statSync(file).mtimeMs + 1000 >= fs.statSync(entry.file).mtimeMs, `Stale test report: ${entry.type}`)
    counts.classes++
    for (const key of Object.keys(values)) counts[key] += values[key]
  }
  return counts
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const supply = inspectTestSupply()
    console.log(process.argv.includes('--reports') ? JSON.stringify(assertTestReports(root, supply))
      : `Test supply verified: ${supply.length} classes across ${modules.length} modules.`)
  } catch (failure) {
    console.error(failure.message)
    process.exitCode = 1
  }
}
