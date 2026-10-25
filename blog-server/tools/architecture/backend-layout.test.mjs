import assert from 'node:assert/strict'
import {readFileSync, existsSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import test from 'node:test'

const root = new URL('../../../', import.meta.url)
const pom = path => readFileSync(new URL(path, root), 'utf8')
const modules = ['blog-types', 'blog-domain', 'blog-application', 'blog-infrastructure', 'blog-trigger', 'blog-app']

test('reactor has exactly six physical layers and one runnable application', () => {
  const parent = pom('blog-server/pom.xml')
  assert.deepEqual([...parent.matchAll(/<module>([^<]+)<\/module>/g)].map(match => match[1]).sort(), [...modules].sort())
  assert.match(parent, /<packaging>pom<\/packaging>/)
  for (const name of modules) {
    const child = pom(`blog-server/${name}/pom.xml`)
    if (name === 'blog-app') {
      assert.match(child, /<finalName>blog-app<\/finalName>/)
      assert.match(child, /<artifactId>spring-boot-maven-plugin<\/artifactId>/)
    } else {
      assert.doesNotMatch(child, /<artifactId>spring-boot-maven-plugin<\/artifactId>/)
    }
  }
})

test('layer modules depend only on their allowed inward modules', () => {
  const allowed = {
    'blog-types': [],
    'blog-domain': ['blog-types'],
    'blog-application': ['blog-domain', 'blog-types'],
    'blog-infrastructure': ['blog-domain', 'blog-types'],
    'blog-trigger': ['blog-application', 'blog-domain', 'blog-types'],
    'blog-app': ['blog-trigger', 'blog-application', 'blog-infrastructure', 'blog-domain', 'blog-types']
  }
  for (const name of modules) {
    assert.ok(existsSync(fileURLToPath(new URL(`blog-server/${name}/pom.xml`, root))), name)
    const child = pom(`blog-server/${name}/pom.xml`)
    const dependencies = [...child.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)]
    for (const match of dependencies) {
      const artifact = match[1].match(/<artifactId>([^<]+)<\/artifactId>/)?.[1]
      if (artifact?.startsWith('blog-')) assert.ok(allowed[name].includes(artifact), `${name} -> ${artifact}`)
    }
    if (name === 'blog-types' || name === 'blog-domain') {
      assert.doesNotMatch(child, /<groupId>(?:org\.springframework[^<]*|com\.baomidou|com\.fasterxml[^<]*)<\/groupId>/)
    }
  }
})

test('Docker context excludes nested module outputs and local dependency caches', () => {
  const patterns = pom('blog-server/.dockerignore').split(/\r?\n/)
  for (const pattern of ['**/target', '**/node_modules', '.artifacts']) assert.ok(patterns.includes(pattern), pattern)
})
