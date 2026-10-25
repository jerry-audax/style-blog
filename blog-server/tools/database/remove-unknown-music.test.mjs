import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {randomUUID} from 'node:crypto'
import {spawnSync} from 'node:child_process'
import test from 'node:test'

const first = 'YmxvZy9tdXNpYy8xNzkxNDYwNDAyNDU1LngtbTRh'
const second = 'YmxvZy9tdXNpYy8xNzkxNDYyMzY2MTk0LngtbTRh'
const migration = new URL('../../../migration/007_20261017_remove_unknown_music_tracks.sql', import.meta.url)
const enabled = process.env.MEDIA_MIGRATION_TEST === '1'
function sql(input) {
  return spawnSync('docker', ['exec', '-i', 'unruffled_bartik', 'sh', '-c',
    'exec psql -U "$POSTGRES_USER" -d blog_media_test -X -v ON_ERROR_STOP=1 -At'],
  {input, encoding: 'utf8', windowsHide: true})
}
function fixture(t) {
  const schema = 'media_it_' + randomUUID().replaceAll('-', '')
  assert.match(schema, /^media_it_[a-f0-9]{32}$/)
  const result = sql(`CREATE SCHEMA ${schema}; SET search_path TO ${schema};
    CREATE TABLE music_track(id varchar(64) PRIMARY KEY,title text,artist text,source_id text,
      cover_asset_id uuid,lyrics_asset_id uuid,enabled boolean);
    INSERT INTO music_track VALUES
      ('${first}','0f503461-6f6b-4682-9cc8-6868d0289ea9','','',NULL,NULL,true),
      ('${second}','2bee4570-0b12-470b-8177-86dffe153973','','',NULL,NULL,true),
      ('known','Known song','Artist','123',NULL,NULL,true),
      ('unrelated','43cc3944-7d1c-4b07-ad5d-987249436be1','','',NULL,NULL,true);`)
  assert.equal(result.status, 0, 'isolated schema setup failed')
  t.after(() => {
    assert.match(schema, /^media_it_[a-f0-9]{32}$/)
    assert.equal(sql(`DROP SCHEMA ${schema} CASCADE;`).status, 0, 'isolated schema cleanup failed')
  })
  return {run: input => sql(`SET search_path TO ${schema};\n${input}`)}
}

test('removes only the two approved unknown tracks; rerun does not remove other metadata', {skip: !enabled}, t => {
  const f = fixture(t), script = readFileSync(migration, 'utf8')
  assert.equal(f.run(script).status, 0)
  assert.equal(f.run('SELECT string_agg(id,\',\' ORDER BY id) FROM music_track;').stdout.trim().split('\n').at(-1), 'known,unrelated')
  assert.equal(f.run(script).status, 0)
})

test('a corrected target aborts the entire removal instead of deleting edited metadata', {skip: !enabled}, t => {
  const f = fixture(t), script = readFileSync(migration, 'utf8')
  assert.equal(f.run(`UPDATE music_track SET artist='Corrected artist' WHERE id='${second}';`).status, 0)
  const result = f.run(script)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /Target metadata changed/)
  assert.equal(f.run('SELECT count(*) FROM music_track;').stdout.trim().split('\n').at(-1), '4')
})
