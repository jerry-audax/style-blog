'use strict'
const fs = require('node:fs')
const args = process.argv.slice(2), scenario = process.env.TEST_NGINX_SCENARIO
if (args.includes('-t')) process.exit(scenario === 'validation-failure' ? 1 : 0)
if (scenario === 'early-exit') process.exit(1)
const filename = args[args.indexOf('-c') + 1]
const config = fs.readFileSync(filename, 'utf8')
const pid = /^pid "([^"]+)";/m.exec(config)?.[1]
if (!pid) process.exit(2)
fs.writeFileSync(pid, String(scenario === 'wrong-pid' ? 1 : process.pid))
const timer = setInterval(() => {}, 1000)
process.on('SIGTERM', () => {clearInterval(timer); process.exit(0)})
