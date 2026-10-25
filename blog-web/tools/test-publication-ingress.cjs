'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const assert = require('node:assert/strict')
const {execFile} = require('node:child_process')
const {promisify} = require('node:util')
const {randomUUID, createHash} = require('node:crypto')
const {setTimeout: delay} = require('node:timers/promises')
const execute = promisify(execFile)
const TOKEN = 'synthetic-ingress-container-key-not-production'
const denied = /^(?:env|\.env.*|node_modules|\.runtime|\.git)$|\.(?:pem|key)$/i
async function write(root, name, data) {const filename = path.join(root, name); await fs.mkdir(path.dirname(filename), {recursive: true}); await fs.writeFile(filename, data)}
async function copySafe(source, target) {
    const info = await fs.lstat(source)
    if (info.isSymbolicLink() || denied.test(path.basename(source))) throw new Error('Unsafe test context input')
    if (info.isDirectory()) {
        await fs.mkdir(target, {recursive: true})
        for (const name of await fs.readdir(source)) {if (!denied.test(name)) await copySafe(path.join(source, name), path.join(target, name))}
    } else if (info.isFile()) {await fs.mkdir(path.dirname(target), {recursive: true}); await fs.copyFile(source, target)}
    else throw new Error('Unsafe test context input')
}
async function createContext({root, directory}) {
    if ((await fs.readdir(directory)).length) throw new Error('Test context must be empty')
    for (const name of ['Dockerfile', 'package.json', 'package-lock.json', 'docker-entrypoint.sh', 'nginx.conf.template', 'publication', 'tools', 'licenses',
        'app/package.json', 'app/index.html', 'app/vite.config.js', 'app/src', 'app/public']) {
        try {await copySafe(path.join(root, name), path.join(directory, name))}
        catch (error) {if (error.code !== 'ENOENT' || name !== 'app/public') throw error}
    }
    await write(directory, '_config.yml', 'url: https://fixture.invalid\nroot: /blog/\ntheme: false\npublic_dir: .build/blog\ntimezone: ""\n')
    await write(directory, '_config.anzhiyu.yml', '{}\n')
    await write(directory, 'source/index.html', '---\nlayout: false\n---\n<!doctype html><html><body>synthetic-ingress-fixture</body></html>\n')
    await write(directory, 'scripts/fixture.js', '// Synthetic fixture uses no external theme scripts.\n')
}
function assertOwned(value, id, task) {
    if (value?.Id !== id || (value.Config?.Labels || value.Labels)?.['blog.task'] !== task) throw new Error('Docker object is not owned by this test')
}
async function docker(args, timeout = 30000) {
    try {return (await execute('docker', args, {timeout, maxBuffer: 1024 * 1024, windowsHide: true})).stdout.trim()}
    catch (error) {
        // Docker output may contain build context or environment information; keep
        // the test console bounded and never replay it wholesale.
        const message = String(error.stderr || '')
        const category = /timeout|timed out|deadline|awaiting headers|request canceled/i.test(message) || error.killed ? 'DOCKER_TIMEOUT' : 'DOCKER_COMMAND_FAILED'
        throw Object.assign(new Error(category), {cause: error, category})
    }
}
async function runHarness() {
    const task = 'media05b5c-' + randomUUID(), imageName = 'blog-publication-ingress-test:' + task
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'publication-ingress-harness-'))
    const resources = [], cleanupFailures = []
    let imageId, checks = 0
    const inspect = async (type, id) => JSON.parse(await docker([type, 'inspect', id]))[0]
    const own = (type, id) => {resources.push({type, id}); return id}
    const runContainer = async args => own('container', await docker(['run', '-d', '--pull=never', '--label', 'blog.task=' + task, ...args]))
    const read = async url => {
        const response = await fetch(url, {redirect: 'manual', signal: AbortSignal.timeout(3000)})
        return {status: response.status, text: await response.text()}
    }
    const wait = async predicate => {
        const deadline = Date.now() + 120000
        do {try {if (await predicate()) return} catch {}; await delay(200)} while (Date.now() < deadline)
        throw new Error('ISOLATED_PUBLICATION_NOT_READY')
    }
    try {
        await createContext({root: path.resolve(__dirname, '..'), directory})
        // Exact production Dockerfile and current lockfile; never substitute an
        // older cached Node image for an unavailable approved base image.
        await docker(['build', '--label', 'blog.task=' + task, '-t', imageName, directory], 600000)
        imageId = (await inspect('image', imageName)).Id
        own('image', imageId)
        const network = own('network', await docker(['network', 'create', '--internal', '--label', 'blog.task=' + task, task]))
        await runContainer(['--network', network, '--network-alias', 'ingress-backend', '--entrypoint', 'node', imageId, '/app/tools/fixtures/publication-ingress-backend.cjs'])
        const web = await runContainer(['--network', network, '-p', '127.0.0.1::80', '-p', '127.0.0.1::8081',
            '-e', 'PUBLICATION_MEDIA_ENABLED=true', '-e', 'PUBLICATION_API_TOKEN=' + TOKEN,
            '-e', 'PUBLICATION_MEDIA_ENVIRONMENT_ID=test', '-e', 'PUBLICATION_MEDIA_PUBLISHER_ID=hexo',
            '-e', 'PUBLICATION_MEDIA_BACKEND_URL=http://ingress-backend:8080',
            '-e', 'PUBLICATION_MEDIA_TRUSTED_HTTP_ORIGIN=http://ingress-backend:8080',
            '-e', 'BLOG_API_URL=http://ingress-backend:8080', '-e', 'BACKEND_URL=http://ingress-backend:8080',
            '-e', 'PUBLICATION_POLL_MS=1000', imageId])
        const info = await inspect('container', web)
        assertOwned(info, web, task)
        const publicBase = 'http://127.0.0.1:' + info.NetworkSettings.Ports['80/tcp'][0].HostPort
        const privateBase = 'http://127.0.0.1:' + info.NetworkSettings.Ports['8081/tcp'][0].HostPort
        await wait(async () => (await read(publicBase + '/blog/')).status === 200)
        assert.match((await read(publicBase + '/blog/')).text, /synthetic-ingress-fixture/); checks++
        assert.equal((await read(publicBase + '/')).status, 302); checks++
        for (const uri of ['/internal', '/internal/status', '/internal/media-reference-state', '/uploads', '/uploads/test']) {
            assert.equal((await read(publicBase + uri)).status, 404); checks++
        }
        assert.equal(JSON.parse((await read(publicBase + '/api/probe')).text).data.syntheticBackend, true); checks++
        const exec = async script => {assertOwned(await inspect('container', web), web, task); return docker(['exec', web, 'node', '-e', script])}
        const original = JSON.parse(await exec(`const fs=require('node:fs');const c=require('node:crypto');const p=JSON.parse(fs.readFileSync('/var/lib/blog-publication/publication.json'));const root='/var/lib/blog-publication/releases/'+p.activeRelease;const file=fs.readdirSync(root+'/assets').find(x=>/-[A-Za-z0-9_-]{8,}\\.js$/.test(x));if(!file)throw Error('No hashed asset');console.log(JSON.stringify({releaseId:p.activeRelease,file,hash:c.createHash('sha256').update(fs.readFileSync(root+'/assets/'+file)).digest('hex')}))`))
        // The old nginx static directory deliberately contains different bytes:
        // a passing request proves the unified route did not use try_files there.
        await exec(`const fs=require('node:fs');fs.mkdirSync('/usr/share/nginx/html/assets',{recursive:true});fs.writeFileSync('/usr/share/nginx/html/assets/'+${JSON.stringify(original.file)},'UNPROTECTED_STATIC_BYPASS_SENTINEL')`)
        let response = await read(publicBase + '/assets/' + original.file)
        assert.equal(response.status, 200); assert.equal(createHash('sha256').update(response.text).digest('hex'), original.hash); checks++
        await exec(`const fs=require('node:fs');fs.renameSync('/app/.build/app','/app/.build/ingress-original-app');fs.mkdirSync('/app/.build/app/assets',{recursive:true});fs.mkdirSync('/app/.build/app/.vite');fs.writeFileSync('/app/.build/app/index.html','<!doctype html><body>new synthetic Vue</body>');fs.writeFileSync('/app/.build/app/assets/new-abcdefgh.js','export const newBundle=true;');fs.writeFileSync('/app/.build/app/.vite/manifest.json',JSON.stringify({'src/interaction-widget.js':{file:'assets/new-abcdefgh.js',isEntry:true},'src/site-background.js':{file:'assets/new-abcdefgh.js',isEntry:true}}));`)
        const retry = await fetch(privateBase + '/internal/retry', {method: 'POST', headers: {'X-Publication-Key': TOKEN}, signal: AbortSignal.timeout(3000)})
        assert.equal(retry.status, 202); await retry.body?.cancel()
        await wait(async () => (await read(publicBase + '/assets/new-abcdefgh.js')).status === 200)
        assert.equal((await read(publicBase + '/assets/new-abcdefgh.js')).text, 'export const newBundle=true;'); checks++
        response = await read(publicBase + '/assets/' + original.file)
        assert.equal(response.status, 200); assert.equal(createHash('sha256').update(response.text).digest('hex'), original.hash); checks++
        assert.equal((await read(publicBase + '/assets/not-registered-abcdefgh.js')).status, 404); checks++
        // Signal only the PID named by this container's own controlled config.
        await exec(`const fs=require('node:fs');const root='/var/lib/blog-publication/ingress';const names=fs.readdirSync(root);if(names.length!==1||!/^instance-[a-f0-9-]{36}$/.test(names[0]))throw Error('Unknown ingress');const dir=root+'/'+names[0];const pid=Number(fs.readFileSync(dir+'/nginx.pid'));const cmd=fs.readFileSync('/proc/'+pid+'/cmdline').toString();if(!Number.isSafeInteger(pid)||pid<2||!cmd.includes('nginx')||!cmd.includes(dir+'/nginx.conf'))throw Error('Wrong process');process.kill(pid,'SIGTERM');`)
        await wait(async () => !(await inspect('container', web)).State.Running); checks++
        console.log(JSON.stringify({task, imageId, checks, result: 'PASS'}))
        return {task, imageId, checks}
    } finally {
        // No host mounts, existing ports, current container IDs, or global prune.
        for (const item of resources.reverse()) {
            try {
                assertOwned(await inspect(item.type, item.id), item.id, task)
                if (item.type === 'container') await docker(['container', 'rm', '-f', item.id])
                else if (item.type === 'image') await docker(['image', 'rm', imageName])
                else await docker(['network', 'rm', item.id])
            } catch {cleanupFailures.push(item.type)}
        }
        assert.equal(path.dirname(directory), os.tmpdir()); assert.match(path.basename(directory), /^publication-ingress-harness-/)
        if ((await fs.lstat(directory)).isSymbolicLink()) throw new Error('Unsafe test context cleanup')
        await fs.rm(directory, {recursive: true, force: true})
        if (cleanupFailures.length) throw new Error('Owned Docker cleanup incomplete: ' + cleanupFailures.join(','))
    }
}
if (require.main === module) runHarness().catch(error => {console.error(JSON.stringify({result: 'NOT_PASSED', category: error.category || error.message})); process.exitCode = 1})
module.exports = {createContext, assertOwned, runHarness}
