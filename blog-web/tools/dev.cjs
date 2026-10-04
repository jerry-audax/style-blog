'use strict'
const {spawn} = require('node:child_process')
const path = require('node:path')
const project = path.resolve(__dirname, '..')
const children = [
    spawn(
        process.execPath,
        [
            path.join(path.dirname(require.resolve('vite/package.json')), 'bin/vite.js'),
            '--host',
            '127.0.0.1',
            '--port',
            '5173',
            '--strictPort',
        ],
        {cwd: path.join(project, 'app'), stdio: 'inherit', windowsHide: true},
    ),
    spawn(
        process.execPath,
        [
            path.join(project, 'publication/interfaces/server.cjs'),
        ],
        {cwd: project, env: {...process.env, BLOG_DEV: '1'}, stdio: 'inherit', windowsHide: true},
    ),
]
let stopping = false
const stop = (code) => {
    if (stopping) return
    stopping = true
    children.forEach((child) => child.kill())
    process.exitCode = code
}
children.forEach((child) => {
    child.on('error', (error) => {
        console.error(error.message)
        stop(1)
    })
    child.on('exit', (code) => stop(code || 0))
})
process.on('SIGINT', () => stop(0))
process.on('SIGTERM', () => stop(0))
