// Read-only verification against an explicitly selected local Docker PostgreSQL.
// Raw user data is captured in memory only; stdout contains table counts/hashes.
import {readFileSync, writeFileSync, existsSync} from 'node:fs'
import {spawnSync} from 'node:child_process'
import {createHash} from 'node:crypto'
import {resolve} from 'node:path'
import {parseDump} from './mysql-dump.mjs'

const args = process.argv.slice(2)
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback

function query(sql) {
    const result = spawnSync('docker', [
        'exec', option('--container', 'unruffled_bartik'), 'psql',
        '-X', '-U', option('--user', 'postgres'), '-d', option('--database', 'blog_system'),
        '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql,
    ], {encoding: 'utf8', maxBuffer: 32 * 1024 * 1024})
    if (result.error || result.status !== 0) throw new Error('PostgreSQL read-only verification query failed')
    return result.stdout.trim()
}

const hash = rows => createHash('sha256').update(JSON.stringify(rows)).digest('hex')
try {
    if (!args.includes('--input')) throw new Error('Required: --input <original mysqldump.sql>')
    const reportFile = option('--report') && resolve(option('--report'))
    if (reportFile && existsSync(reportFile)) throw new Error('Report already exists; refusing overwrite')
    const dump = parseDump(readFileSync(resolve(option('--input')), 'utf8'))
    const tables = dump.tables.map(table => {
        const expressions = table.columns.map(c => `"${c.name}"::text`).join(', ')
        const result = query(`SELECT json_build_array(${expressions})
                              FROM "${table.name}"
                              ORDER BY "id";`)
        const actual = result ? result.split('\n').map(row => JSON.parse(row)) : []
        const expected = [...table.rows].sort((a, b) => BigInt(a[0]) < BigInt(b[0]) ? -1 : BigInt(a[0]) > BigInt(b[0]) ? 1 : 0)
        return {
            table: table.name,
            rows: actual.length,
            expectedRows: expected.length,
            sha256: hash(actual),
            matches: hash(actual) === hash(expected)
        }
    })
    const report = {
        sourceSha256: dump.sourceSha256,
        allMatch: tables.every(t => t.matches),
        totalRows: tables.reduce((sum, t) => sum + t.rows, 0),
        tables
    }
    if (reportFile) writeFileSync(reportFile, JSON.stringify(report, null, 2) + '\n', {flag: 'wx'})
    console.log(JSON.stringify(report, null, 2))
    if (!report.allMatch) process.exitCode = 1
} catch (error) {
    console.error(error.message)
    process.exitCode = 1
}
