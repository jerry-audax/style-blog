import assert from 'node:assert/strict'
import test from 'node:test'
import {assertSafeStage} from './commit-scope.mjs'

test('scope preserves user dirty deletes and rejects private or generated files', () => {
  for (const path of ['local.env', '.env.imgbed.local', 'keys/owner.pem', 'blog-web/env/private.txt',
    'blog-admin/node_modules/pkg/index.js', '.idea/misc.xml', 'data/music/music-catalog.json']) {
    assert.throws(() => assertSafeStage([path]), /outside the architecture commit scope/)
  }
  assert.throws(() => assertSafeStage(['blog-server/src/main/java/Owner.java'], {
    dirtyPaths: ['blog-server/src/main/java/Owner.java']
  }), /existing user change/)
  assert.doesNotThrow(() => assertSafeStage(['blog-server/blog-domain/pom.xml', 'blog-server/.dockerignore', 'blog-server/tools/architecture/backend-layout.test.mjs']))
})

test('a dirty source can only be staged after an explicit baseline-only transformation', () => {
  const path = 'blog-server/tools/start-local-backend.ps1'
  assert.throws(() => assertSafeStage([path], {dirtyPaths: [path]}), /existing user change/)
  assert.doesNotThrow(() => assertSafeStage([path], {dirtyPaths: [path], baselineOnlyPaths: [path]}))
  assert.throws(() => assertSafeStage(['../other/repo.java']), /outside the architecture commit scope/)
})
