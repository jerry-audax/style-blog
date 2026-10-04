import {readFileSync, writeFileSync, existsSync, mkdirSync} from 'node:fs'
import {resolve, dirname} from 'node:path'
import {parseDump, renderPostgres} from './mysql-dump.mjs'

const args = process.argv.slice(2)
const option = name => args[args.indexOf(name) + 1]
if (!args.includes('--input') || !args.includes('--output')) {
    console.error('Usage: node tools/database/convert-mysql-dump.mjs --input <mysqldump.sql> --output <postgres.sql> [--schema-only]')
    process.exit(1)
}
try {
    const input = resolve(option('--input')), output = resolve(option('--output'))
    const reportFile = args.includes('--report') ? resolve(option('--report')) : output + '.report.json'
    if (input === output || existsSync(output) || existsSync(reportFile)) throw new Error('Output already exists; refusing overwrite')
    const dump = parseDump(readFileSync(input, 'utf8'))
    const {sql, report} = renderPostgres(dump, {schemaOnly: args.includes('--schema-only')})
    mkdirSync(dirname(output), {recursive: true})
    mkdirSync(dirname(reportFile), {recursive: true})
    writeFileSync(output, sql, {flag: 'wx'})
    writeFileSync(reportFile, JSON.stringify(report, null, 2) + '\n', {flag: 'wx'})
    console.log(JSON.stringify({
        output,
        reportFile,
        tables: report.tables.map(t => ({name: t.name, rows: t.rows})),
        sourceSha256: report.sourceSha256
    }, null, 2))
} catch (error) {
    console.error(error.message)
    process.exitCode = 1
}
