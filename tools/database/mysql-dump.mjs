// Strict, offline converter for mysqldump's CREATE TABLE / INSERT VALUES subset.
// Never executes source SQL. Unknown constructs fail closed; row values never enter errors.
import {createHash} from 'node:crypto'

const identifier = value => {
    if (!/^[a-z_][a-z0-9_]*$/i.test(value)) throw new Error('Unsupported identifier')
    return `"${value}"`
}
const literal = value => value === null ? 'NULL' : `'${value.replaceAll("'", "''")}'`

export function statements(source) {
    const result = []
    let buffer = '', quote = null
    for (let i = 0; i < source.length; i++) {
        const char = source[i], next = source[i + 1]
        if (quote) {
            buffer += char
            if (char === '\\') {
                if (next === undefined) throw new Error('Unterminated string')
                buffer += next
                i++
            } else if (char === quote) {
                if (next === quote) {
                    buffer += next;
                    i++
                } else quote = null
            }
        } else if (char === "'" || char === '"' || char === '`') {
            quote = char
            buffer += char
        } else if (char === '/' && next === '*') {
            const end = source.indexOf('*/', i + 2)
            if (end < 0) throw new Error('Unterminated comment')
            i = end + 1
            buffer += ' '
        } else if ((char === '-' && next === '-' && /\s/.test(source[i + 2] || '')) || char === '#') {
            const end = source.indexOf('\n', i)
            i = end < 0 ? source.length : end
            buffer += '\n'
        } else if (char === ';') {
            if (buffer.trim()) result.push(buffer.trim())
            buffer = ''
        } else buffer += char
    }
    if (quote) throw new Error('Unterminated string')
    if (buffer.trim()) throw new Error('Unterminated SQL statement')
    return result
}

function splitComma(source) {
    const parts = []
    let start = 0, depth = 0, quote = null
    for (let i = 0; i < source.length; i++) {
        const char = source[i]
        if (quote) {
            if (char === '\\') i++
            else if (char === quote) {
                if (source[i + 1] === quote) i++
                else quote = null
            }
        } else if (char === "'" || char === '"' || char === '`') quote = char
        else if (char === '(') depth++
        else if (char === ')') depth--
        else if (char === ',' && depth === 0) {
            parts.push(source.slice(start, i).trim());
            start = i + 1
        }
        if (depth < 0) throw new Error('Invalid parentheses')
    }
    if (quote || depth !== 0) throw new Error('Unterminated value list')
    parts.push(source.slice(start).trim())
    return parts
}

function decodeString(token) {
    if (!/^'(?:[^'\\]|\\[\s\S]|'')*'$/.test(token)) throw new Error('Unsupported value')
    const escapes = {'0': '\0', b: '\b', n: '\n', r: '\r', t: '\t', Z: '\x1a', '%': '\\%', _: '\\_'}
    let result = ''
    for (let i = 1; i < token.length - 1; i++) {
        const char = token[i]
        if (char === '\\') {
            const next = token[++i]
            result += escapes[next] ?? next
        } else if (char === "'" && token[i + 1] === "'") {
            result += "'";
            i++
        } else result += char
    }
    if (result.includes('\0')) throw new Error('NUL cannot be represented in PostgreSQL text')
    return result
}

function value(token, type) {
    if (/^NULL$/i.test(token)) return null
    if (token.startsWith("'")) return decodeString(token)
    if (/^[+-]?\d+$/.test(token)) return BigInt(token).toString()
    if (/^[+-]?(?:\d+\.\d*|\d*\.\d+)(?:e[+-]?\d+)?$/i.test(token) && /numeric|double/.test(type)) return token
    throw new Error('Unsupported value')
}

function keyColumns(text) {
    return splitComma(text).map(part => {
        const match = part.match(/^`([a-z_][a-z0-9_]*)`$/i)
        if (!match) throw new Error('Unsupported index column')
        return match[1]
    })
}

function parseTable(statement) {
    const match = statement.match(/^CREATE TABLE `([a-z_][a-z0-9_]*)`\s*\(([\s\S]*)\)\s*(ENGINE[\s\S]*)$/i)
    if (!match) throw new Error('Unsupported table definition')
    const table = {
        name: match[1],
        columns: [],
        indexes: [],
        rows: [],
        nextId: match[3].match(/AUTO_INCREMENT=(\d+)/i)?.[1] || '1'
    }
    for (const part of splitComma(match[2])) {
        const column = part.match(/^`([a-z_][a-z0-9_]*)`\s+(bigint|int|integer|tinyint|smallint|varchar\(\d+\)|longtext|mediumtext|text|datetime(?:\(\d+\))?|timestamp(?:\(\d+\))?|decimal\(\d+,\s*\d+\))(?=\s|$)([\s\S]*)$/i)
        if (column) {
            const originalType = column[2].toLowerCase()
            const types = {
                int: 'integer',
                tinyint: 'smallint',
                longtext: 'text',
                mediumtext: 'text',
                datetime: 'timestamp without time zone',
                timestamp: 'timestamp without time zone'
            }
            const type = originalType.startsWith('datetime(') || originalType.startsWith('timestamp(')
                ? `timestamp(${originalType.match(/\d+/)[0]}) without time zone`
                : originalType.startsWith('decimal') ? originalType.replace('decimal', 'numeric') : types[originalType] || originalType
            let options = column[3].replace(/\s+CHARACTER SET \w+/gi, '').replace(/\s+COLLATE \w+/gi, '')
            const auto = /AUTO_INCREMENT/i.test(options)
            const update = /ON UPDATE CURRENT_TIMESTAMP/i.test(options)
            options = options.replace(/AUTO_INCREMENT/gi, '').replace(/ON UPDATE CURRENT_TIMESTAMP(?:\(\d+\))?/gi, '').trim()
            if (!/^(?:NOT NULL|NULL|DEFAULT (?:NULL|CURRENT_TIMESTAMP(?:\(\d+\))?|'(?:[^'\\]|\\.|'')*'|[+-]?\d+)|\s)*$/i.test(options)) throw new Error(`Unsupported column options in ${table.name}`)
            // Convert string defaults with the same escaping rules as row values.
            options = options.replace(/DEFAULT ('(?:[^'\\]|\\[\s\S]|'')*')/gi, (_, token) => `DEFAULT ${literal(decodeString(token))}`)
            if (update && column[1] !== 'updated_at') throw new Error('Unsupported automatic timestamp column')
            table.columns.push({name: column[1], type, auto, update, options})
            continue
        }
        const primary = part.match(/^PRIMARY KEY\s*\((.*)\)(?: USING BTREE)?$/i)
        if (primary) {
            table.primary = keyColumns(primary[1]);
            continue
        }
        const index = part.match(/^(UNIQUE )?KEY `([a-z_][a-z0-9_]*)`\s*\((.*)\)(?: USING BTREE)?$/i)
        if (index) {
            table.indexes.push({unique: Boolean(index[1]), name: index[2], columns: keyColumns(index[3])});
            continue
        }
        throw new Error(`Unsupported column or constraint in ${table.name}`)
    }
    if (!table.columns.length || !table.primary) throw new Error('Table must have columns and a primary key')
    for (const col of [...table.primary, ...table.indexes.flatMap(index => index.columns)]) {
        if (!table.columns.some(column => column.name === col)) throw new Error('Index references an unknown column')
    }
    return table
}

export function parseDump(source) {
    const tables = []
    const byName = new Map()
    for (const statement of statements(source)) {
        if (/^CREATE TABLE /i.test(statement)) {
            const table = parseTable(statement)
            if (byName.has(table.name)) throw new Error('Duplicate table')
            tables.push(table);
            byName.set(table.name, table)
        } else if (/^INSERT INTO /i.test(statement)) {
            const match = statement.match(/^INSERT INTO `([a-z_][a-z0-9_]*)`(?:\s*\(([^)]*)\))?\s+VALUES\s+([\s\S]*)$/i)
            if (!match || !byName.has(match[1])) throw new Error('Unsupported INSERT statement')
            const table = byName.get(match[1])
            const columns = match[2] ? keyColumns(match[2]) : table.columns.map(c => c.name)
            if (columns.join(',') !== table.columns.map(c => c.name).join(',')) throw new Error('Unsupported INSERT column order')
            for (const tuple of splitComma(match[3])) {
                if (!tuple.startsWith('(') || !tuple.endsWith(')')) throw new Error('Invalid row tuple')
                const tokens = splitComma(tuple.slice(1, -1))
                if (tokens.length !== table.columns.length) throw new Error(`Column count mismatch in ${table.name}`)
                table.rows.push(tokens.map((token, i) => value(token, table.columns[i].type)))
            }
        } else if (!/^(DROP TABLE IF EXISTS `[a-z_][a-z0-9_]*`|LOCK TABLES `[a-z_][a-z0-9_]*` WRITE|UNLOCK TABLES)$/i.test(statement)) {
            throw new Error('Unsupported statement; conversion stopped without executing source SQL')
        }
    }
    if (!tables.length) throw new Error('No supported tables in dump')
    return {tables, sourceSha256: createHash('sha256').update(source).digest('hex')}
}

export function renderPostgres(dump, {schemaOnly = false} = {}) {
    const lines = [
        '-- Generated by tools/database/convert-mysql-dump.mjs. No original SQL is executed.',
        `-- Source SHA-256: ${dump.sourceSha256}`,
        '-- Requires an EMPTY public schema. Never overwrite an existing database.',
        'BEGIN;',
        "SET LOCAL search_path = public;",
        "SET LOCAL lock_timeout = '5s';",
        "SET LOCAL standard_conforming_strings = on;",
        "DO $guard$ BEGIN IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f')) THEN RAISE EXCEPTION 'Migration requires an empty target schema'; END IF; END $guard$;",
    ]
    const timestamps = dump.tables.some(t => t.columns.some(c => c.update))
    if (timestamps) lines.push(`CREATE FUNCTION blog_touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $touch$
BEGIN
  IF NEW IS DISTINCT FROM OLD AND NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
    NEW.updated_at := statement_timestamp();
  END IF;
  RETURN NEW;
END
$touch$;`)
    for (const table of dump.tables) {
        const name = identifier(table.name)
        const definitions = table.columns.map(c => `  ${identifier(c.name)} ${c.type}${c.auto ? ' GENERATED BY DEFAULT AS IDENTITY' : ''}${c.options ? ' ' + c.options : ''}`)
        definitions.push(`  PRIMARY KEY (${table.primary.map(identifier).join(', ')})`)
        lines.push(`CREATE TABLE ${name}
                    (
                        ${definitions.join(',\n')}
                    );`)
        for (const index of table.indexes) {
            const expressions = index.columns.map(column => {
                const type = table.columns.find(c => c.name === column).type
                return index.unique && /varchar|text/.test(type) ? `lower(${identifier(column)})` : identifier(column)
            })
            lines.push(`CREATE
            ${index.unique ? 'UNIQUE ' : ''}INDEX
            ${identifier(index.name)}
            ON
            ${name}
            (
            ${expressions.join(', ')}
            );`)
        }
        if (table.columns.some(c => c.update)) lines.push(`CREATE TRIGGER ${identifier('touch_' + table.name)} BEFORE UPDATE ON ${name} FOR EACH ROW EXECUTE FUNCTION blog_touch_updated_at();`)
        if (!schemaOnly && table.rows.length) {
            // Small batches keep psql error output bounded and the generated script readable.
            for (let offset = 0; offset < table.rows.length; offset += 100) {
                lines.push(`INSERT INTO ${name} (${table.columns.map(c => identifier(c.name)).join(', ')})
                            VALUES ${table.rows.slice(offset, offset + 100).map(row => '(' + row.map(literal).join(', ') + ')').join(',\n')};`)
            }
        }
        for (const c of table.columns.filter(c => c.auto)) {
            if (!schemaOnly) lines.push(`SELECT setval(pg_get_serial_sequence('${name}', '${c.name}'),
                                                       GREATEST(COALESCE(MAX(${identifier(c.name)}), 0) + 1, ${table.nextId}),
                                                       false)
                                         FROM ${name};`)
        }
    }
    lines.push('COMMIT;', '')
    return {
        sql: lines.join('\n'),
        report: {
            sourceSha256: dump.sourceSha256,
            schemaOnly,
            tables: dump.tables.map(table => ({
                name: table.name,
                rows: table.rows.length,
                columns: table.columns.map(c => c.name),
                nextId: table.nextId
            }))
        },
    }
}
