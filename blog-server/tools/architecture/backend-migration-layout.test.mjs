import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {execFileSync, spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import test from 'node:test'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const at = p => path.join(root, p)

// Catches missing or duplicate SQL inputs consumed by CLI and real repository tests.
test('database tools and tests share the canonical migration inputs', () => {
  for (const name of ['001_schema_postgresql.sql', '002_assign_blog_owner.sql',
    '003_cleanup_unused_legacy_tables.sql', '005_add_media_metadata_tables.sql',
    '006_add_media_upload_operation_table.sql']) {
    assert.ok(fs.statSync(at(`migration/${name}`)).size > 0, name)
  }
  for (const old of ['blog-server/blog-app/src/main/resources/db/schema-postgresql.sql',
    'tools/database/assign-blog-owner.sql', 'tools/database/cleanup-unused-legacy-tables.sql',
    'tools/database/reduce-owner-visitor-roles.sql', 'tools/database/add-media-metadata-tables.sql',
    'tools/database/add-media-upload-operation-table.sql']) assert.ok(!fs.existsSync(at(old)), old)
  for (const name of fs.readdirSync(at('migration')).filter(name => name.endsWith('.sql'))) {
    assert.match(name, /^\d{3}_[a-z0-9_]+\.sql$/)
  }
})

// Evaluates parameter defaults without running the destructive import script body.
test('relocated import resolves canonical DDL independently of current directory', () => {
  const script = at('blog-server/tools/media/media-import.ps1').replaceAll("'", "''")
  const directory = path.dirname(script).replaceAll("'", "''")
  const command = `$ErrorActionPreference='Stop';$tokens=$null;$errors=$null;$ast=[System.Management.Automation.Language.Parser]::ParseFile('${script}',[ref]$tokens,[ref]$errors);if($errors.Count){throw 'Parse failed'};$p=$ast.ParamBlock.Parameters|Where-Object {$_.Name.VariablePath.UserPath -eq 'Ddl'};$expression=$p.DefaultValue.Extent.Text.Replace('$PSScriptRoot',"'${directory}'");$value=& ([scriptblock]::Create($expression));[IO.Path]::GetFullPath($value)`
  const resolved = execFileSync('pwsh', ['-NoProfile', '-Command', command], {encoding: 'utf8', cwd: at('blog-server/blog-domain')}).trim()
  assert.equal(fs.realpathSync(resolved), fs.realpathSync(at('migration/005_add_media_metadata_tables.sql')))
})

test('packaged application does not carry manual or retired initialization SQL', () => {
  const entries = execFileSync('jar', ['tf', at('blog-server/blog-app/target/blog-app.jar')], {encoding: 'utf8'}).split(/\r?\n/)
  assert.ok(!entries.some(entry => /^BOOT-INF\/classes\/.*\.sql$/.test(entry)), 'manual migrations must stay outside the application JAR')
})

// The checkout may have a user-deleted schema.sql that is still tracked in HEAD.
// Reintroduce that resource only in an isolated Maven fixture, never in the worktree.
test('application packaging excludes retired SQL even when the source resource exists', t => {
  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'blog-sql-packaging-'))
  t.after(() => {
    assert.equal(path.dirname(fs.realpathSync(fixture)), fs.realpathSync(os.tmpdir()))
    assert.ok(path.basename(fixture).startsWith('blog-sql-packaging-'))
    fs.rmSync(fixture, {recursive: true})
  })
  const applicationPom = fs.readFileSync(at('blog-server/blog-app/pom.xml'), 'utf8')
  const jarPlugin = [...applicationPom.matchAll(/<plugin>[\s\S]*?<\/plugin>/g)]
    .map(match => match[0]).find(plugin => /<artifactId>maven-jar-plugin<\/artifactId>/.test(plugin)) ?? ''
  const aggregatePom = fs.readFileSync(at('blog-server/pom.xml'), 'utf8')
  const parent = aggregatePom.match(/<parent>[\s\S]*?<\/parent>/)?.[0]
  assert.ok(parent, 'Use the real build parent and plugin management')
  fs.writeFileSync(path.join(fixture, 'pom.xml'), `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>${parent}<groupId>local.fixture</groupId><artifactId>sql-packaging</artifactId><version>1.0</version><build><plugins>${jarPlugin}</plugins></build></project>`)
  const resources = path.join(fixture, 'src/main/resources')
  fs.mkdirSync(path.join(resources, 'db/nested'), {recursive: true})
  fs.writeFileSync(path.join(resources, 'db/schema.sql'), 'CREATE TABLE retired_fixture (id BIGINT);\n')
  fs.writeFileSync(path.join(resources, 'db/nested/manual.sql'), '-- manual SQL must not ship\n')
  fs.writeFileSync(path.join(resources, 'application.yml'), 'fixture: retained\n')
  fs.writeFileSync(path.join(resources, 'db/readme.txt'), 'A non-SQL resource must remain\n')
  const invocation = process.platform === 'win32'
    ? ['cmd.exe', ['/d', '/s', '/c', 'mvn.cmd -o -B -ntp package -DskipTests']]
    : ['mvn', ['-o', '-B', '-ntp', 'package', '-DskipTests']]
  const built = spawnSync(...invocation, {cwd: fixture, encoding: 'utf8', timeout: 120000, windowsHide: true})
  assert.equal(built.status, 0, built.stdout + built.stderr)
  const entries = execFileSync('jar', ['tf', path.join(fixture, 'target/sql-packaging-1.0.jar')], {encoding: 'utf8'}).split(/\r?\n/)
  assert.ok(entries.includes('application.yml'), 'Normal application resources must ship')
  assert.ok(entries.includes('db/readme.txt'), 'Do not exclude the whole resource directory')
  assert.ok(!entries.includes('db/schema.sql'), 'Tracked retired schema must not ship, regardless of local deletion')
  assert.ok(!entries.includes('db/nested/manual.sql'), 'Nested manual SQL must also stay outside the JAR')
})
