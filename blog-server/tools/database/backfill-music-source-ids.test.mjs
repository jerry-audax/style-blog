import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {randomUUID} from 'node:crypto'
import {spawnSync} from 'node:child_process'
import test from 'node:test'

const migration = new URL('../../../migration/008_20261017_backfill_verified_music_source_ids.sql', import.meta.url)
const enabled = process.env.MEDIA_MIGRATION_TEST === '1'
const songs = [
  ['YmxvZy9tdXNpYy8xNzkxMTE2ODQzNTIwLngtbTRh', 'Call of Silence', '澤野弘之/R!N/Gemie', '482636090'],
  ['YmxvZy9tdXNpYy8xNzkxMTE2ODAyNTU4LngtbTRh', 'Call of Silence', 'Clear Sky', '1980337835'],
  ['YmxvZy9tdXNpYy8xNzkxMTE2ODU4NDQxLngtbTRh', 'Call your name _Gv_', '澤野弘之/R!N/Gemie', '1381723556'],
  ['YmxvZy9tdXNpYy8xNzkxMTE2NzMyNjQ5LngtbTRh', '悪魔の子', 'ヒグチアイ', '1910623420']
]
function sql(input) {
  return spawnSync('docker', ['exec', '-i', 'unruffled_bartik', 'sh', '-c',
    'exec psql -U "$POSTGRES_USER" -d blog_media_test -X -v ON_ERROR_STOP=1 -At'],
  {input, encoding: 'utf8', windowsHide: true})
}
function fixture(t) {
  const schema = 'media_it_' + randomUUID().replaceAll('-', '')
  assert.match(schema, /^media_it_[a-f0-9]{32}$/)
  const setup = sql(`CREATE SCHEMA ${schema}; SET search_path TO ${schema};
    CREATE TABLE music_track(id text PRIMARY KEY,title text,artist text,source_id text,
      version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),deleted_at timestamptz,
      cover_asset_id uuid);
    INSERT INTO music_track(id,title,artist,source_id) VALUES
      ${songs.map(([id, title, artist]) => `('${id}','${title}','${artist}',NULL)`).join(',')},
      ('unrelated','進撃gt20130218巨人','澤野弘之',NULL);`)
  assert.equal(setup.status, 0, setup.stderr)
  t.after(() => {
    assert.match(schema, /^media_it_[a-f0-9]{32}$/)
    assert.equal(sql(`DROP SCHEMA ${schema} CASCADE;`).status, 0)
  })
  return input => sql(`SET search_path TO ${schema};\n${input}`)
}

test('binds only four verified song identities, increments versions once and preserves ambiguous songs', {skip: !enabled}, t => {
  const run = fixture(t), script = readFileSync(migration, 'utf8')
  assert.equal(run(script).status, 0)
  assert.equal(run("SELECT string_agg(source_id,',' ORDER BY source_id) FROM music_track;").stdout.trim().split('\n').at(-1), '1381723556,1910623420,1980337835,482636090')
  assert.equal(run(script).status, 0)
  assert.equal(run("SELECT sum(version),count(source_id),count(cover_asset_id) FROM music_track;").stdout.trim().split('\n').at(-1), '4|4|0')
})

test('a manually edited source aborts every candidate instead of overwriting or partially applying', {skip: !enabled}, t => {
  const run = fixture(t), script = readFileSync(migration, 'utf8')
  assert.equal(run(`UPDATE music_track SET source_id='123456',version=1 WHERE id='${songs[2][0]}';`).status, 0)
  const result = run(script)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /Verified source target changed/)
  assert.equal(run('SELECT count(source_id),sum(version) FROM music_track;').stdout.trim().split('\n').at(-1), '1|1')
})

test('changed title, artist or metadata version prevents backfilling stale matches', {skip: !enabled}, t => {
  const run = fixture(t), script = readFileSync(migration, 'utf8')
  for (const change of ["title='Different version'", "artist='Other artist'", 'version=1', 'deleted_at=now()']) {
    assert.equal(run(`UPDATE music_track SET title='Call of Silence',artist='Clear Sky',version=0,deleted_at=NULL WHERE id='${songs[1][0]}';
      UPDATE music_track SET ${change} WHERE id='${songs[1][0]}';`).status, 0)
    const result = run(script)
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /Verified source target changed/)
    assert.equal(run('SELECT count(source_id) FROM music_track;').stdout.trim().split('\n').at(-1), '0')
  }
})
