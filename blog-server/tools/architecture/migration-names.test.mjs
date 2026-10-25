import assert from 'node:assert/strict'
import {mkdtempSync, writeFileSync, rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import test from 'node:test'

const script = fileURLToPath(new URL('./verify-migration-names.mjs', import.meta.url))
const historical = ['001_schema_postgresql.sql', '002_assign_blog_owner.sql',
  '003_cleanup_unused_legacy_tables.sql', '004_reduce_owner_visitor_roles.sql',
  '005_add_media_metadata_tables.sql', '006_add_media_upload_operation_table.sql']

function check(t, names) {
  const directory = mkdtempSync(join(tmpdir(), 'blog-migration-names-'))
  t.after(() => rmSync(directory, {recursive: true}))
  for (const name of names) writeFileSync(join(directory, name), '-- fixture only\n')
  return spawnSync(process.execPath, [script, directory], {encoding: 'utf8', windowsHide: true})
}

test('accepts only exact historical exemptions and real dated changes', t => {
  const result = check(t, [...historical, '007_20261017_remove_unknown_music_tracks.sql',
    '008_20240229_add_music_index.sql'])
  assert.equal(result.status, 0, result.stdout + result.stderr)
})

for (const name of ['007_add_music_index.sql', '001_other_legacy.sql',
  '007_20260229_add_music_index.sql', '007_20261301_add_music_index.sql',
  '007_20260431_add_music_index.sql', '007_00000101_add_music_index.sql',
  '007_20261017_fix.sql', '007_20261017_update.sql', '007_20261017_new.sql',
  '007_20261017_fix_update.sql', '7_20261017_add_music_index.sql',
  '007_20261017_Add_music_index.SQL']) {
  test(`rejects malformed or nonspecific migration ${name}`, t => {
    const result = check(t, [name])
    assert.equal(result.status, 1, result.stdout + result.stderr)
    assert.match(result.stderr, /Invalid migration/)
  })
}

test('rejects duplicate migration sequence numbers', t => {
  const result = check(t, ['007_20261017_add_music_index.sql', '007_20261018_add_video_index.sql'])
  assert.equal(result.status, 1, result.stdout + result.stderr)
  assert.match(result.stderr, /Duplicate migration/)
})

test('checks the repository migration directory without executing SQL', () => {
  const result = spawnSync(process.execPath, [script], {encoding: 'utf8', windowsHide: true})
  assert.equal(result.status, 0, result.stdout + result.stderr)
})
