import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {inspectM4a, probeSettings, uploadSample, verifySample, listProbeFiles} from './telegram-audio-probe-lib.mjs'

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const error = (code) => {
    throw new Error(code)
}

async function main() {
    const args = process.argv.slice(2)
    const fileIndex = args.indexOf('--file')
    if (fileIndex !== 0 || !args[1] || (args.length !== 2 && !(args.length === 3 && ['--allow-upload', '--reconcile'].includes(args[2]))))
        error('USAGE_FILE_AND_OPTIONAL_ALLOW_UPLOAD')
    const musicRoot = await fs.realpath(path.join(project, 'music'))
    const file = await fs.realpath(path.resolve(project, args[1]))
    const relative = path.relative(musicRoot, file)
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative) || path.extname(file).toLowerCase() !== '.m4a')
        error('FILE_MUST_BE_M4A_WITHIN_MUSIC')
    const stat = await fs.stat(file)
    if (!stat.isFile() || stat.size > 16 * 1024 * 1024) error('FILE_TOO_LARGE')
    const bytes = await fs.readFile(file)
    const source = {filename: relative, ...inspectM4a(bytes)}
    if (args.includes('--reconcile')) {
        const settings = probeSettings(process.env)
        const files = await listProbeFiles(settings)
        const receiptFile = path.join(project, '.artifacts/music', `${source.sha256}.probe.json`)
        let receipt
        try {
            receipt = JSON.parse(await fs.readFile(receiptFile, 'utf8'))
        } catch {
            error('NO_UPLOAD_RECEIPT_TO_RECONCILE')
        }
        if (receipt.source?.sha256 !== source.sha256) error('INVALID_RECEIPT')
        const attempted = Date.parse(receipt.attemptedAt)
        const candidates = files.filter((file) => file.bytes === source.bytes && file.mediaType === 'audio/mp4' &&
            /^[a-zA-Z0-9_-]+\.(?:m4a|mp4)$/.test(file.path.slice(`${settings.folder}/`.length)) &&
            Math.abs(Number(file.timestamp) - attempted) < 5 * 60 * 1000)
        if (candidates.length !== 1) {
            console.log(JSON.stringify({
                state: 'manual_reconciliation_required_no_upload',
                candidateCount: candidates.length,
                files
            }, null, 2))
            process.exitCode = 2
            return
        }
        const verification = await verifySample(candidates[0].publicUrl, bytes)
        if (verification.rangeSupported) {
            Object.assign(receipt, {
                state: 'http_range_verified', ...candidates[0], verification,
                reconciledAt: new Date().toISOString(), verifiedAt: new Date().toISOString()
            })
            delete receipt.error
            await fs.writeFile(receiptFile, `${JSON.stringify(receipt, null, 2)}\n`, {mode: 0o600})
        }
        console.log(JSON.stringify({
            state: verification.rangeSupported ? 'reconciled_without_reupload' : 'manual_reconciliation_required_no_upload',
            publicUrl: candidates[0].publicUrl, verification, receiptFile: path.relative(project, receiptFile)
        }, null, 2))
        if (!verification.rangeSupported) process.exitCode = 2
        return
    }
    if (!args.includes('--allow-upload')) {
        console.log(JSON.stringify({state: 'inspection_only', source}, null, 2))
        return
    }
    // Only the existing PowerShell launcher loads local credentials; this script reads process variables only.
    const settings = probeSettings(process.env)
    const receiptFile = path.join(project, '.artifacts/music', `${source.sha256}.probe.json`)
    await fs.mkdir(path.dirname(receiptFile), {recursive: true})
    let receipt
    try {
        receipt = JSON.parse(await fs.readFile(receiptFile, 'utf8'))
    } catch (e) {
        if (e.code !== 'ENOENT') error('INVALID_RECEIPT')
    }
    const persist = async () => fs.writeFile(receiptFile, `${JSON.stringify(receipt, null, 2)}\n`, {mode: 0o600})
    if (receipt) {
        if (receipt.source?.sha256 !== source.sha256 || !receipt.publicUrl) error('PREVIOUS_UPLOAD_OUTCOME_REQUIRES_MANUAL_RECONCILIATION')
        const url = new URL(receipt.publicUrl)
        const suffix = url.pathname.slice(`/file/${settings.folder}/`.length)
        if (url.origin !== settings.base.origin || url.search || url.hash || url.username || url.password ||
            !url.pathname.startsWith(`/file/${settings.folder}/`) || !/^[a-zA-Z0-9_-]+\.(?:m4a|mp4)$/.test(suffix)) error('INVALID_RECEIPT')
    } else {
        receipt = {
            state: 'uploading',
            source,
            attemptedAt: new Date().toISOString(),
            publicUseAuthorizedByOwner: true,
            scope: 'single_file_probe',
            channel: 'telegram',
            channelName: settings.channelName
        }
        await persist()
        try {
            Object.assign(receipt, await uploadSample(bytes, settings), {
                state: 'uploaded',
                uploadedAt: new Date().toISOString()
            })
            // Preserve the accepted upload before running read checks; subsequent runs must reuse it.
            await persist()
        } catch (e) {
            receipt.state = 'upload_outcome_requires_manual_reconciliation'
            receipt.error = /^[A-Z_0-9]+$/.test(e.message) ? e.message : 'UPLOAD_FAILED'
            await persist()
            throw e
        }
    }
    try {
        receipt.verification = await verifySample(receipt.publicUrl, bytes)
        receipt.verifiedAt = new Date().toISOString()
        receipt.state = receipt.verification.rangeSupported ? 'http_range_verified' : 'http_range_not_supported'
        await persist()
    } catch {
        receipt.state = 'uploaded_verification_failed'
        await persist()
    }
    console.log(JSON.stringify({...receipt, receiptFile: path.relative(project, receiptFile)}, null, 2))
    if (receipt.state !== 'http_range_verified') process.exitCode = 2
}

main().catch((e) => {
    console.error(/^[A-Z_0-9]+$/.test(e.message) ? e.message : 'PROBE_FAILED_CHECK_LOCAL_RECEIPT')
    process.exitCode = 1
})
