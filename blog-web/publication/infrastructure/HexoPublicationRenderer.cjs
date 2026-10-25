'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const {spawn} = require('node:child_process')
const {randomUUID} = require('node:crypto')
const {FrozenPublicationInputs} = require('./FrozenPublicationInputs.cjs')
const {validateStage} = require('../domain/PublicationAttempt.cjs')
const {publicationOutputDigest} = require('./PublicationManifestCollector.cjs')
const {captureRemoval, resumeRemoval} = require('./FilePublicationRetirement.cjs')
const {assembleUnified} = require('./UnifiedPublicationAssembler.cjs')

async function realDirectory(directory) {
    const info = await fs.lstat(directory)
    const real = await fs.realpath(directory)
    const matches = process.platform === 'win32' ? real.toLowerCase() === directory.toLowerCase() : real === directory
    if (!info.isDirectory() || info.isSymbolicLink() || !matches) throw new Error('Unsafe publication directory')
}

async function copyTree(source, target) {
    const info = await fs.lstat(source)
    if (info.isSymbolicLink()) throw new Error('Publication inputs cannot be symlinks')
    if (!info.isDirectory()) {await fs.mkdir(path.dirname(target), {recursive: true}); await fs.copyFile(source, target); return}
    await fs.mkdir(target, {recursive: true})
    for (const name of await fs.readdir(source)) await copyTree(path.join(source, name), path.join(target, name))
}

async function validateTree(directory) {
    for (const entry of await fs.readdir(directory, {withFileTypes: true})) {
        if (entry.isSymbolicLink()) throw new Error('Publication outputs cannot be symlinks')
        const filename = path.join(directory, entry.name)
        if (entry.isDirectory()) await validateTree(filename)
        else if (entry.name.endsWith('.html')) {
            const html = await fs.readFile(filename, 'utf8')
            if (!/<!doctype html>/i.test(html) || !/<body[\s>]/i.test(html)) throw new Error('Invalid generated HTML')
        }
    }
}

class HexoPublicationRenderer {
    #children = new Set()
    constructor({root, directory, dev = false, timeoutMs = 120000, inputs, copy = copyTree, layoutVersion = 1}) {
        if (![1, 2].includes(layoutVersion)) throw new Error('Invalid publication layout')
        this.layoutVersion = layoutVersion
        this.root = path.resolve(root); this.directory = path.resolve(directory); this.dev = dev; this.timeoutMs = timeoutMs
        this.inputs = inputs || new FrozenPublicationInputs({root: this.root, directory: this.directory, dev})
        this.copy = copy
        this.stopped = false
    }
    async render(posts) {
        const snapshot = await this.inputs.freeze(posts)
        try {
            return await this.renderFrozen(snapshot)
        } finally {await this.inputs.dispose(snapshot)}
    }
    async renderFrozen(snapshot, {onStageReady = async () => {}} = {}) {
        if (this.stopped) throw new Error('Publication renderer stopped')
        await this.inputs.verify(snapshot)
        const work = snapshot.workRoot, release = snapshot.releaseId
        await realDirectory(this.directory)
        const releases = path.join(this.directory, 'releases')
        await fs.mkdir(releases, {recursive: true}); await realDirectory(releases)
        await this.inputs.beginBuild(snapshot)
        await new Promise((resolve, reject) => {
            if (this.stopped) {reject(new Error('Publication renderer stopped')); return}
            const runner = path.join(__dirname, 'run-frozen-hexo.cjs')
            const child = spawn(process.execPath, [runner], {cwd: work,
                env: {...process.env, BLOG_DEV: this.dev ? '1' : '0'}, windowsHide: true, stdio: 'ignore'})
            let timedOut = false
            this.#children.add(child)
            const timer = setTimeout(() => {timedOut = true; child.kill()}, this.timeoutMs)
            child.on('error', error => {this.#children.delete(child); clearTimeout(timer); reject(error)})
            child.on('close', code => {this.#children.delete(child); clearTimeout(timer); !timedOut && code === 0 ? resolve() : reject(new Error('Publication build failed or timed out'))})
        })
        await this.inputs.completeBuild(snapshot)
        const tree = path.join(work, '.build/blog')
        await fs.access(path.join(tree, 'index.html'))
        let expectedOutputDigest = this.layoutVersion === 1 ? await publicationOutputDigest(tree) : null
        await validateTree(tree)
        for (const name of snapshot.postNames) {
            const id = name.match(/^database-article-(\d+)\.md$/)[1]
            await fs.access(path.join(tree, 'article', id, 'index.html'))
        }
        await realDirectory(this.directory); await realDirectory(releases)
        const stages = path.join(this.directory, 'stages')
        await fs.mkdir(stages, {recursive: true}); await realDirectory(stages)
        const stageName = 'stage-' + randomUUID(), stage = path.join(stages, stageName)
        await fs.mkdir(stage)
        const stamp = await fs.lstat(stage, {bigint: true})
        let checkpointStarted = false
        try {
            if (this.layoutVersion === 2) expectedOutputDigest = await assembleUnified(snapshot, tree, stage, {copy: this.copy})
            else await this.copy(tree, stage)
            await validateTree(stage)
            const outputDigest = await publicationOutputDigest(stage)
            if (outputDigest !== expectedOutputDigest) throw new Error('Incomplete publication output copy')
            const output = validateStage({releaseId: release, stageName, outputDigest, ...(this.layoutVersion === 2 ? {layoutVersion: 2} : {}),
                device: String(stamp.dev), inode: String(stamp.ino)})
            // 回调一旦开始，落盘结果可能未知；保留已完成 stage，重启按日志核对，不猜测删除。
            checkpointStarted = true
            await onStageReady(output)
            await this.#verifyArtifact(stage, output)
            await this.recoverStage(output)
            return release
        } catch (error) {
            if (!checkpointStarted) {
                await realDirectory(stages)
                await realDirectory(stage)
                const current = await fs.lstat(stage, {bigint: true})
                await publicationOutputDigest(stage)
                if (current.dev === stamp.dev && current.ino === stamp.ino
                        && path.dirname(stage) === stages && path.resolve(stage) === stage) await fs.rm(stage, {recursive: true, force: false})
            }
            throw error
        }
    }
    async #roots() {
        await realDirectory(this.directory)
        const releases = path.join(this.directory, 'releases'), stages = path.join(this.directory, 'stages')
        await realDirectory(releases); await realDirectory(stages)
        return {releases, stages}
    }
    async #exists(filename) {
        try {await fs.lstat(filename); return true} catch (error) {if (error.code === 'ENOENT') return false; throw error}
    }
    async #verifyArtifact(filename, output) {
        await realDirectory(filename)
        const info = await fs.lstat(filename, {bigint: true})
        if (String(info.dev) !== output.device || String(info.ino) !== output.inode
                || await publicationOutputDigest(filename) !== output.outputDigest) throw new Error('Publication output identity changed')
        await realDirectory(filename)
        const after = await fs.lstat(filename, {bigint: true})
        if (info.dev !== after.dev || info.ino !== after.ino) throw new Error('Publication output identity changed')
    }
    async recoverStage(input) {
        const output = validateStage(input), {releases, stages} = await this.#roots()
        const stage = path.join(stages, output.stageName), target = path.join(releases, output.releaseId)
        const hasStage = await this.#exists(stage), hasTarget = await this.#exists(target)
        if (hasStage && hasTarget || !hasStage && !hasTarget) throw new Error('Ambiguous publication output ownership')
        if (hasTarget) {await this.#verifyArtifact(target, output); return output.releaseId}
        await this.#verifyArtifact(stage, output)
        // 单写者协议；在 rename 前再次拒绝既存目标，绝不以同名覆盖或认领目录。
        if (await this.#exists(target)) throw new Error('Publication release already exists')
        await fs.rename(stage, target)
        await this.#verifyArtifact(target, output)
        return output.releaseId
    }
    async verifyOutput(input) {
        const output = validateStage(input), {releases} = await this.#roots()
        await this.#verifyArtifact(path.join(releases, output.releaseId), output)
    }
    async outputExists(input) {
        const output = validateStage(input), {releases} = await this.#roots()
        return this.#exists(path.join(releases, output.releaseId))
    }
    stop() {this.stopped = true; for (const child of this.#children) child.kill()}
    async prepareRemoval(input) {
        const output = validateStage(input), {releases} = await this.#roots()
        const target = path.join(releases, output.releaseId)
        await this.#verifyArtifact(target, output)
        const intent = await captureRemoval(target, output)
        await this.#verifyArtifact(target, output)
        return intent
    }
    async removeOutput(input, intent) {
        const output = validateStage(input), {releases} = await this.#roots()
        const target = path.join(releases, output.releaseId)
        if (path.dirname(target) !== releases || path.resolve(target) !== target) throw new Error('Unsafe publication retirement')
        await resumeRemoval(target, output, intent)
    }
}
module.exports = {HexoPublicationRenderer, validateTree}
