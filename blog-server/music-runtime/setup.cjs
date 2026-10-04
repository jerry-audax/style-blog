'use strict'
// One-time backend provisioning. No secret values appear in output or shell commands.
const fs = require('node:fs')
const path = require('node:path')
const cp = require('node:child_process')
const crypto = require('node:crypto')
const {createHome, activate, environment} = require('./state.cjs')

function credentials(source) {
    const app = source.match(/^\s*AppID\s*=\s*(\S+)\s*$/mi)?.[1]
    let privateKey = source.match(/^\s*PrivateKey\s*=\s*([A-Za-z0-9+/=]+)\s*$/mi)?.[1]
        || source.match(/-----BEGIN PRIVATE KEY-----[\s\S]+?-----END PRIVATE KEY-----/)?.[0]
    if (!app || !privateKey) throw new Error('INVALID_CREDENTIALS')
    const key = crypto.createPrivateKey(privateKey.startsWith('-----') ? privateKey
        : {key: Buffer.from(privateKey, 'base64'), format: 'der', type: 'pkcs8'})
    if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails.modulusLength < 2048) throw new Error('INVALID_CREDENTIALS')
    return {appId: app, privateKey: key.export({type: 'pkcs8', format: 'der'}).toString('base64')}
}

function setup(root, credentialFile, source) {
    if (!path.isAbsolute(root) || fs.existsSync(path.join(root, 'home.json'))) throw new Error('STATE_ALREADY_EXISTS_OR_INVALID')
    const next = createHome(root, source)
    if (source) {
        const tokenFile = '.config/ncm-cli/tokens.enc.json'
        if (fs.existsSync(path.join(source, tokenFile))) {
            fs.copyFileSync(path.join(source, tokenFile), path.join(next.home, tokenFile), fs.constants.COPYFILE_EXCL)
            fs.chmodSync(path.join(next.home, tokenFile), 0o600)
        }
    } else {
        const values = credentials(fs.readFileSync(credentialFile, 'utf8'))
        const cli = require.resolve('@music163/ncm-cli/dist/index.js')
        for (const [name, value] of Object.entries(values)) {
            const result = cp.spawnSync(process.execPath, [cli, 'config', 'set', name, value], {
                cwd: next.home, env: environment(next.home), timeout: 15000, windowsHide: true, stdio: 'ignore',
            })
            if (result.status !== 0 || result.error) throw new Error('CONFIGURATION_FAILED')
        }
    }
    if (!fs.existsSync(path.join(next.home, '.config/ncm-cli/credentials.enc.json'))) throw new Error('CONFIGURATION_FAILED')
    activate(root, next.id)
}

if (require.main === module) {
    try {
        setup(...process.argv.slice(2));
        console.log('Music backend state provisioned; no credentials exported.')
    } catch {
        console.error('Music provisioning failed. Check credentials/state paths; existing state is never overwritten.');
        process.exitCode = 1
    }
}
module.exports = {credentials, setup}
