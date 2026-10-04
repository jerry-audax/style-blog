'use strict'
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')

function atomicJson(file, value) {
    const temp = file + '.' + crypto.randomUUID() + '.tmp'
    fs.writeFileSync(temp, JSON.stringify(value), {mode: 0o600, flag: 'wx'})
    fs.renameSync(temp, file)
}

function currentHome(root) {
    const value = JSON.parse(fs.readFileSync(path.join(root, 'home.json'), 'utf8'))
    if (!/^[a-f0-9-]{36}$/.test(value.active)) throw new Error('NOT_CONFIGURED')
    return path.join(root, value.active)
}

function createHome(root, source) {
    fs.mkdirSync(root, {recursive: true, mode: 0o700})
    const id = crypto.randomUUID(), home = path.join(root, id)
    const config = path.join(home, '.config', 'ncm-cli')
    fs.mkdirSync(config, {recursive: true, mode: 0o700})
    // No logs/cache/QR state. CLI stores encrypted credentials and encrypted tokens.
    if (source) {
        for (const name of ['.netease_mcp_device.json', '.config/ncm-cli/credentials.enc.json']) {
            const from = path.join(source, name)
            if (fs.existsSync(from)) {
                fs.copyFileSync(from, path.join(home, name), fs.constants.COPYFILE_EXCL)
                fs.chmodSync(path.join(home, name), 0o600)
            }
        }
    }
    return {id, home}
}

function activate(root, id) {
    atomicJson(path.join(root, 'home.json'), {active: id})
}

function environment(home) {
    const env = {}
    // Do not inherit DB, image tokens or other application secrets into the CLI.
    for (const key of ['PATH', 'SystemRoot', 'WINDIR', 'ComSpec', 'TEMP', 'TMP', 'PATHEXT'])
        if (process.env[key]) env[key] = process.env[key]
    return Object.assign(env, {
        HOME: home, USERPROFILE: home, APPDATA: home, LOCALAPPDATA: home,
        XDG_CONFIG_HOME: path.join(home, '.config'), NO_COLOR: '1'
    })
}

module.exports = {atomicJson, currentHome, createHome, activate, environment}
