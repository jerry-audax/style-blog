import {ref, computed, watch, onBeforeUnmount} from 'vue'
import * as api from '../api/index.js'
import {validateUploadCapabilities} from '../media/uploadCapabilities'
import {rememberUpload, readTrackedUploads, forgetActiveUploads, forgetUpload} from '../media/uploadTracking'
import {createUploadHasher} from '../media/uploadHash'
import {validateImageCapabilities} from '../media/imageCapabilities'
import {validateImageUploadResult} from '../media/imageCatalog'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const statuses = ['READY', 'UPLOADING', 'PAUSED', 'UPLOADED', 'UNKNOWN', 'CONFLICT', 'FAILED', 'SUCCEEDED']
const pendingStatuses = ['LOCAL_PENDING', 'READY', 'UPLOADING', 'PAUSED', 'UPLOADED', 'UNKNOWN']
const validVersion = value => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n
const validBusinessVersion = value => Number.isSafeInteger(value) && value >= 0
const audioTypes = new Set(['audio/mpeg', 'audio/mp3', 'audio/mp4', 'audio/m4a', 'audio/x-m4a', 'audio/aac', 'audio/ogg', 'audio/wav', 'audio/x-wav', 'audio/flac', 'audio/webm'])
const imageTypes = new Set(['image/jpeg', 'image/png', 'image/gif', 'image/webp'])
function verifiedOwner(auth) {
    const id = auth.verifiedUserId
    return auth.initialized === true && auth.user?.owner === true && typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n && String(auth.user.userId) === id ? id : ''
}
function checkedOperation(raw, targetType) {
    if (!raw || !uuid.test(raw.operationId) || raw.targetType !== targetType || !statuses.includes(raw.status) || !validVersion(raw.operationVersion)) throw new Error('操作状态响应异常，请按原编号重新查询')
    if (!Number.isSafeInteger(raw.confirmedBytes) || !Number.isSafeInteger(raw.totalBytes) || raw.confirmedBytes < 0 || raw.totalBytes < 1 || raw.confirmedBytes > raw.totalBytes) throw new Error('操作进度响应异常，请重新查询')
    return raw
}

/** Browser state coordinates explicit commands; only server checkpoints constitute durable progress. */
export function useMediaUpload({auth, capabilities, targetType, onSucceeded}) {
    const busy = ref(false), preparing = ref(false), browserBytes = ref(0), operations = ref([]), error = ref(''), activeOperationId = ref('')
    let generation = 0, serial = 0, disposed = false, timer = null, pollingStopped = false, restorePromise = null, pollCount = 0
    let hasher = createUploadHasher()
    const watched = new Set(), notified = new Set()
    const settings = () => {
        if (targetType !== 'IMAGE_CREATE') return validateUploadCapabilities(capabilities())
        const c = validateImageCapabilities(capabilities())
        return c?.storage === 'postgres' && c.uploadEnabled && c.uploadProtocol === 'tracked-image-v1'
            ? {protocol:c.uploadProtocol, limits:{imageBytes:c.imageBytes,avatarBytes:c.avatarBytes}} : null
    }
    const enabled = computed(() => Boolean(verifiedOwner(auth) && settings() && (targetType === 'IMAGE_CREATE' || capabilities().mediaUpload || capabilities().mediaReplace)))
    const current = (owner, epoch) => !disposed && owner === verifiedOwner(auth) && epoch === generation
    const stopPolling = () => { clearTimeout(timer); timer = null }
    function failure(cause, owner, epoch) {
        if (!current(owner, epoch)) return
        if (cause?.status === 401) { auth.clear?.(); return }
        error.value = cause?.status === 409 ? '操作或资料已变化，请按原编号查询并核对版本；未保存输入已保留' : '操作结果未确认，请按原编号重新查询，勿重复上传'
        pollingStopped = true; stopPolling()
    }
    function upsert(raw, notify = true) {
        const value = checkedOperation(raw, targetType)
        const index = operations.value.findIndex(row => row.operationId === value.operationId), old = operations.value[index]
        if (old && validVersion(old.operationVersion) && (BigInt(old.operationVersion) > BigInt(value.operationVersion) || old.status === 'SUCCEEDED' && value.status !== 'SUCCEEDED')) return old
        if (index < 0) operations.value = [value, ...operations.value].slice(0, 20)
        else operations.value = operations.value.map((row, i) => i === index ? value : row)
        if (value.status === 'SUCCEEDED' && notify && watched.has(value.operationId) && !notified.has(value.operationId)) {
            try {
                if (targetType === 'IMAGE_CREATE') validateImageUploadResult(value.result,value)
                else {
                    const saved = targetType === 'MUSIC_TRACK' ? value.result?.music : targetType === 'SURPRISE_CREATE' ? value.result?.video : null
                    const other = targetType === 'MUSIC_TRACK' ? value.result?.video : value.result?.music
                    if (value.result?.targetType !== targetType || !saved || saved.id !== value.targetId || !validBusinessVersion(saved.version) || other != null || value.result?.image != null) throw new Error('Invalid result')
                }
            } catch { error.value = '上传已完成，但回执类型、身份或版本无法安全显示，请刷新目录，勿重复上传'; return value }
            notified.add(value.operationId)
            try {
                const result = onSucceeded?.(value.result, value)
                result?.catch?.(() => { if (!disposed) error.value = '上传已完成，页面更新失败，请刷新目录，勿重新上传' })
            } catch { error.value = '上传已完成，页面更新失败，请刷新目录，勿重新上传' }
            if (activeOperationId.value === value.operationId) { busy.value = false; preparing.value = false; activeOperationId.value = '' }
        }
        return value
    }
    function schedule() {
        if (disposed || pollingStopped || timer || !verifiedOwner(auth) || !settings() || !operations.value.some(row => ['READY', 'UPLOADING', 'UPLOADED'].includes(row.status))) return
        if (pollCount >= 30) { pollingStopped = true; error.value = '自动查询已暂停，请按原编号重新查询或核对，勿重复上传'; return }
        timer = setTimeout(async () => {
            timer = null
            const owner = verifiedOwner(auth), epoch = generation
            if (!settings() || !owner) return
            if (document.hidden) { schedule(); return }
            pollCount++
            for (const row of [...operations.value].filter(row => ['READY', 'UPLOADING', 'UPLOADED'].includes(row.status))) {
                try {
                    const updated = await api.getMediaUploadOperation(row.operationId)
                    if (!current(owner, epoch)) return
                    upsert(updated)
                } catch (cause) { failure(cause, owner, epoch); return }
            }
            schedule()
        }, 2000)
    }
    async function query(operation) {
        const owner = verifiedOwner(auth), epoch = generation
        if (!owner || !settings() || disposed || !uuid.test(operation?.operationId)) return null
        if (operation.status !== 'SUCCEEDED') watched.add(operation.operationId)
        try {
            const value = await api.getMediaUploadOperation(operation.operationId)
            if (!current(owner, epoch)) return null
            pollingStopped = false; pollCount = 0; error.value = ''; const result = upsert(value); schedule(); return result
        } catch (cause) {
            const local = operations.value.find(row => row.operationId === operation.operationId)
            if (current(owner, epoch) && cause?.status === 404 && local?.status === 'LOCAL_PENDING' && !(busy.value && activeOperationId.value === local.operationId)) {
                operations.value = operations.value.filter(row => row.operationId !== local.operationId)
                forgetUpload(owner, local.operationId); watched.delete(local.operationId)
                error.value = '原编号未找到已登记操作，本地待确认提示已解除；请核对资料版本后重新选择文件'
                pollingStopped = false; schedule(); return null
            }
            failure(cause, owner, epoch); return null
        }
    }
    async function restore() {
        const owner = verifiedOwner(auth), epoch = generation
        if (!owner || !settings() || disposed) return
        if (restorePromise) return restorePromise
        const task = (async () => {
            for (const row of readTrackedUploads(owner).filter(row => row.targetType === targetType)) if (!operations.value.some(op => op.operationId === row.operationId)) operations.value.push({...row, status: 'LOCAL_PENDING'})
            try {
                const page = await api.listMediaUploadOperations(0, 20)
                if (!current(owner, epoch)) return
                if (!Array.isArray(page?.records) || page.records.length > 20) throw new Error('Invalid history')
                const seen = new Set()
                for (const row of page.records.filter(row => row.targetType === targetType)) { upsert(row, false); seen.add(row.operationId); if (row.status !== 'SUCCEEDED') watched.add(row.operationId) }
                for (const row of [...operations.value].filter(row => row.status === 'LOCAL_PENDING' && !seen.has(row.operationId))) {
                    const value = await api.getMediaUploadOperation(row.operationId)
                    if (!current(owner, epoch)) return
                    upsert(value, false); if (value.status !== 'SUCCEEDED') watched.add(value.operationId)
                }
                pollingStopped = false; error.value = ''; schedule()
            } catch (cause) { failure(cause, owner, epoch) }
        })()
        restorePromise = task
        try { await task } finally { if (restorePromise === task) restorePromise = null }
    }
    function sourceFile(target, file, config) {
        if (!file || !Number.isSafeInteger(file.size) || file.size < 1) throw new Error('请选择非空文件')
        const field = {AUDIO: 'audioBytes', COVER: 'coverBytes', LYRICS: 'lyricsBytes', VIDEO: 'videoBytes', IMAGE:'imageBytes', AVATAR:'avatarBytes'}[target.slot]
        if (!field || file.size > config.limits[field]) throw new Error('文件超过当前资源大小上限')
        let type = (file.type || '').toLowerCase()
        if (target.slot === 'LYRICS' && !type && /\.lrc$/i.test(file.name)) type = 'text/plain'
        const matches = target.slot === 'AUDIO' ? audioTypes.has(type) : ['COVER','IMAGE','AVATAR'].includes(target.slot) ? imageTypes.has(type) : target.slot === 'LYRICS' ? type === 'text/plain' && /\.lrc$/i.test(file.name) : target.slot === 'VIDEO' && type.startsWith('video/')
        if (!matches) throw new Error('文件类型与所选资源不匹配')
        return type === file.type ? file : new File([file], file.name, {type})
    }
    function newId() {
        if (typeof globalThis.crypto?.randomUUID !== 'function') throw new Error('浏览器不支持安全上传编号')
        const id = crypto.randomUUID(); if (!uuid.test(id)) throw new Error('上传编号生成失败'); return id
    }
    function isTargetBusy(id) { return operations.value.some(row => row.targetId === id && pendingStatuses.includes(row.status)) }
    async function transfer(target, source, operation) {
        const owner = verifiedOwner(auth), epoch = generation, config = settings()
        if (!enabled.value || !owner || !config || disposed || busy.value) { error.value = '请先确认博主身份和上传能力，并等待当前文件处理完成'; return null }
        if (target.targetType !== targetType || !operation && targetType === 'MUSIC_TRACK' && !validBusinessVersion(target.expectedVersion)) { error.value = '资料版本不可用，请先刷新确认'; return null }
        const token = ++serial; busy.value = true; preparing.value = true; browserBytes.value = 0; error.value = ''
        let id = operation?.operationId
        try {
            const file = sourceFile(target, source, config)
            if (!operation && isTargetBusy(target.targetId ?? null)) throw new Error('此资源已有待确认操作，请先查询或恢复')
            if (!operation) id = newId()
            activeOperationId.value = id
            const hash = await hasher.hash(file)
            if (!current(owner, epoch) || token !== serial) return null
            if (!settings() || !enabled.value) throw new Error('上传能力已变化，请刷新后再选择文件')
            preparing.value = false; watched.add(id)
            if (!operation) {
                const hint = {operationId: id, targetType, targetId: target.targetId ?? null, slot: target.slot, createdAt: new Date().toISOString()}
                rememberUpload(owner, hint); operations.value = [{...hint, status: 'LOCAL_PENDING'}, ...operations.value].slice(0, 20)
                const payload = {operationId: id, targetType, slot: target.slot, fileName: file.name, fileSize: file.size, mediaType: file.type, sourceSha256: hash}
                if (targetType === 'MUSIC_TRACK') { payload.targetId = target.targetId; payload.expectedVersion = target.expectedVersion }
                else if (targetType === 'SURPRISE_CREATE') { payload.title = target.title; payload.enabled = target.enabled; payload.sortOrder = target.sortOrder }
                else if (targetType === 'IMAGE_CREATE') payload.imagePurpose = target.imagePurpose
                else throw new Error('不支持的上传目标')
                const created = await api.createMediaUploadOperation(payload)
                if (!current(owner, epoch) || token !== serial) return null
                const state = upsert(created)
                if (!state.canResumeContent || !['READY', 'PAUSED'].includes(state.status)) { schedule(); return state }
            }
            if (!settings() || !enabled.value) throw new Error('上传能力已变化，请按原编号核对')
            pollingStopped = false; pollCount = 0; schedule()
            const value = await api.putMediaUploadContent(id, file, {onUploadProgress: event => { if (current(owner, epoch) && token === serial) browserBytes.value = Math.min(file.size, Math.max(0, event.loaded || 0)) }})
            if (!current(owner, epoch) || token !== serial) return null
            const result = upsert(value); schedule(); return result
        } catch (cause) {
            if (current(owner, epoch) && token === serial && !operations.value.some(row => row.operationId === id && row.status === 'SUCCEEDED')) {
                if (id && watched.has(id)) failure(cause, owner, epoch)
                else error.value = cause.message || '文件预处理失败'
            }
            return null
        } finally { if (current(owner, epoch) && token === serial) { busy.value = false; preparing.value = false } }
    }
    const start = (target, file) => transfer(target, file)
    const resume = (operation, file) => operation?.canResumeContent && ['READY', 'PAUSED'].includes(operation.status)
        ? transfer({targetType, targetId: operation.targetId, slot: operation.slot}, file, operation) : Promise.resolve(null)
    async function command(operation, action) {
        const owner = verifiedOwner(auth), epoch = generation
        if (!enabled.value || !owner || !settings() || disposed || !validVersion(operation?.operationVersion)) return null
        try { const value = await action(); if (!current(owner, epoch)) return null; watched.add(value.operationId); pollingStopped = false; pollCount = 0; error.value = ''; const result = upsert(value); schedule(); return result }
        catch (cause) { failure(cause, owner, epoch); return null }
    }
    const pause = operation => operation?.canPause ? command(operation, () => api.pauseMediaUploadOperation(operation.operationId, operation.operationVersion)) : Promise.resolve(null)
    const recover = operation => operation?.canRecover ? command(operation, () => api.recoverMediaUploadOperation(operation.operationId, operation.operationVersion)) : Promise.resolve(null)
    async function bind(operation, expectedVersion) {
        if (!operation?.canBind || !validBusinessVersion(expectedVersion) || busy.value) return null
        let id
        try { id = newId(); if (id === operation.operationId) throw new Error('绑定必须使用新的操作编号') } catch (cause) { error.value = cause.message; return null }
        const owner = verifiedOwner(auth)
        if (!owner || !settings()) return null
        rememberUpload(owner, {operationId: id, targetType, targetId: operation.targetId, slot: operation.slot, createdAt: new Date().toISOString()}); watched.add(id)
        return command(operation, () => api.bindMediaUploadOperation(operation.operationId, {newOperationId: id, expectedVersion, operationVersion: operation.operationVersion}))
    }
    async function completeCover(target) {
        const owner = verifiedOwner(auth), epoch = generation
        if (targetType !== 'MUSIC_TRACK' || !enabled.value || !owner || !settings() || disposed || busy.value || isTargetBusy(target.targetId) || !validBusinessVersion(target.expectedVersion)) return null
        let id
        try { id = newId() } catch (cause) { error.value = cause.message; return null }
        const token = ++serial
        busy.value = true; error.value = ''; activeOperationId.value = id
        const hint = {operationId:id,targetType,targetId:target.targetId,slot:'COVER',createdAt:new Date().toISOString()}
        rememberUpload(owner,hint); watched.add(id)
        operations.value = [{...hint,status:'LOCAL_PENDING'},...operations.value].slice(0,20)
        try {
            const value = await api.completeMusicCover(target.targetId,{operationId:id,expectedVersion:target.expectedVersion})
            if (!current(owner,epoch) || token !== serial) return null
            checkedOperation(value,targetType)
            if (value.operationId !== id) {
                forgetUpload(owner,id); watched.delete(id)
                operations.value = operations.value.filter(row => row.operationId !== id)
            }
            const result = upsert(value); schedule(); return result
        } catch (cause) { failure(cause,owner,epoch); return null }
        finally { if(current(owner,epoch) && token === serial) busy.value = false }
    }
    function reset() { generation++; serial++; stopPolling(); hasher.dispose(); hasher = createUploadHasher(); operations.value = []; busy.value = false; preparing.value = false; browserBytes.value = 0; activeOperationId.value = ''; error.value = ''; watched.clear(); notified.clear(); restorePromise = null; pollingStopped = false }
    const stopIdentity = watch(() => [verifiedOwner(auth),auth.token], ([owner], previous) => { if (previous?.[0]) forgetActiveUploads(previous[0]); reset(); pollCount = 0; if (owner && settings()) void restore() }, {flush: 'sync'})
    function dispose() { if (disposed) return; disposed = true; generation++; serial++; stopPolling(); hasher.dispose(); stopIdentity() }
    onBeforeUnmount(dispose)
    const suspend = () => { pollingStopped = true; stopPolling() }
    return {enabled, busy, preparing, browserBytes, operations, error, activeOperationId, start, resume, pause, recover, bind, query, restore, dispose, suspend, isTargetBusy, completeCover}
}
