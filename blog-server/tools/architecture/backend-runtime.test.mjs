import assert from 'node:assert/strict'
import {execFileSync} from 'node:child_process'
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import test from 'node:test'

const helper = fileURLToPath(new URL('./backend-runtime.ps1', import.meta.url))
const quote = value => `'${value.replaceAll("'", "''")}'`
function execute(buildExit) {
  const fixture = mkdtempSync(path.join(os.tmpdir(), 'arch 构建 spaces-'))
  const jar = path.join(fixture, 'blog-server/blog-app/target/blog-app.jar')
  mkdirSync(path.dirname(jar), {recursive: true})
  writeFileSync(jar, 'fixture')
  writeFileSync(path.join(fixture, 'blog-server/pom.xml'), '<project/>')
  const script = `
    $ErrorActionPreference='Stop'
    . ${quote(helper)}
    function global:mvn.cmd { $global:LASTEXITCODE=${buildExit} }
    try {
      $jar = Invoke-BackendPackage -Repository ${quote(fixture)}
      Write-Output ('START_JAVA:' + $jar)
    } catch { Write-Output 'BUILD_REFUSED' }
  `
  return {output: execFileSync('pwsh', ['-NoProfile', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], {encoding: 'utf8'}), jar}
}

test('a failed reactor package prevents starting a stale application jar', () => {
  const {output} = execute(7)
  assert.match(output, /BUILD_REFUSED/)
  assert.doesNotMatch(output, /START_JAVA/)
})

test('successful packaging resolves the unique application artifact with Chinese and space paths', () => {
  const {output, jar} = execute(0)
  assert.ok(output.includes(`START_JAVA:${jar}`))
})

test('relocated launcher and CLI wrapper fixtures remain offline and executable', () => {
  for (const name of ['media-catalog-launcher', 'media-dry-run-wrapper', 'media-import-wrapper']) {
    const fixture = fileURLToPath(new URL(`../../../blog-server/blog-app/src/test/resources/${name}.test.ps1`, import.meta.url))
    const output = execFileSync('pwsh', ['-NoProfile', '-File', fixture], {encoding: 'utf8'})
    assert.match(output, /(?:0 failures|1 passed|6 passed)/, name)
  }
})

test('Jenkins declares PowerShell 7 before executing architecture fixtures', () => {
  const pipeline = readFileSync(new URL('../../../Jenkinsfile', import.meta.url), 'utf8')
  assert.match(pipeline.slice(pipeline.indexOf("stage('Toolchain')"), pipeline.indexOf("stage('Database migration tests')")), /pwsh.*PSVersionTable.*Major.*7/)
})
