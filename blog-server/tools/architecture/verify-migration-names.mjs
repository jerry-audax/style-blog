import {readdirSync, lstatSync} from 'node:fs'
import {resolve, join} from 'node:path'
import {fileURLToPath} from 'node:url'

// Only these reviewed, published baselines are exempt. Never exempt a number range.
const historical = new Set(['001_schema_postgresql.sql', '002_assign_blog_owner.sql',
  '003_cleanup_unused_legacy_tables.sql', '004_reduce_owner_visitor_roles.sql',
  '005_add_media_metadata_tables.sql', '006_add_media_upload_operation_table.sql'])
const generic = new Set(['fix', 'update', 'new', 'change', 'migration', 'script', 'test'])

function validDate(value) {
  const year = Number(value.slice(0, 4)), month = Number(value.slice(4, 6)), day = Number(value.slice(6))
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return year > 0 && month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1]
}

try {
  const directory = process.argv[2] ? resolve(process.argv[2]) : fileURLToPath(new URL('../../../migration', import.meta.url))
  const sequences = new Set()
  let count = 0
  for (const name of readdirSync(directory).sort()) {
    if (!name.toLowerCase().endsWith('.sql')) continue
    const stat = lstatSync(join(directory, name))
    const match = /^(\d{3})_(\d{8})_([a-z][a-z0-9]*(?:_[a-z0-9]+)*)\.sql$/.exec(name)
    const words = match?.[3].split('_') ?? []
    if (!stat.isFile() || stat.isSymbolicLink() || (!historical.has(name) &&
      (!match || !validDate(match[2]) || words.length < 2 || words.every(word => generic.has(word))))) {
      throw new Error(`Invalid migration filename: ${name}`)
    }
    const sequence = name.slice(0, 3)
    if (sequences.has(sequence)) throw new Error(`Duplicate migration sequence: ${sequence}`)
    sequences.add(sequence)
    count++
  }
  console.log(`Validated ${count} migration filenames; SQL was not executed.`)
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
