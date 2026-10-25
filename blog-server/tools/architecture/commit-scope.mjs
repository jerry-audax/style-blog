export function assertSafeStage(paths, {dirtyPaths = [], baselineOnlyPaths = []} = {}) {
  const dirty = new Set(dirtyPaths)
  const transformed = new Set(baselineOnlyPaths)
  for (const input of paths) {
    const path = input.replaceAll('\\', '/')
    const forbidden = /(^|\/)(?:\.\.?|node_modules|target|data|env|\.idea|\.artifacts|\.superpowers)(\/|$)/.test(path)
      || /(^|\/)\.?[^/]*\.env(?:\.[^/]*)?$|(^|\/)\.env(?:\.|$)|\.(?:pem|key|p12|jks|keystore)$/i.test(path)
    const allowed = /^(?:blog-server\/(?:pom\.xml|Dockerfile|\.dockerignore$|src\/|tools\/|blog-(?:types|domain|application|infrastructure|trigger|app)\/)|migration\/|Jenkinsfile$|README\.md$)/.test(path)
    if (forbidden || !allowed) throw new Error(`outside the architecture commit scope: ${path}`)
    if (dirty.has(path) && !transformed.has(path)) throw new Error(`existing user change: ${path}`)
  }
}
