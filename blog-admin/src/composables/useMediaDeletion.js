import {ref, computed, watch, onBeforeUnmount} from 'vue'
import * as api from '../api/index.js'
import {validateDeletionCapabilities} from '../media/deletionCapabilities'

const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const version = value => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n
const kinds = ['IMAGE', 'AUDIO', 'VIDEO', 'LYRICS']
const statuses = ['PENDING', 'PROCESSING', 'SUCCEEDED', 'RETRY_WAIT', 'FAILED', 'UNKNOWN', 'RETAINED', 'LINKED']
function owner(auth) {
    const id = auth.verifiedUserId
    return auth.initialized === true && auth.user?.owner === true && typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n && String(auth.user.userId) === id ? id : ''
}
function targetOf(raw) {
    if (!raw || !['MUSIC_TRACK', 'MUSIC_MEDIA', 'SURPRISE_VIDEO'].includes(raw.kind) || typeof raw.id !== 'string' || !raw.id.trim() || raw.id.length > 64 || /[\x00-\x1f\x7f]/.test(raw.id)) throw new Error('Invalid target')
    const slot = raw.slot ?? null
    if (raw.kind === 'MUSIC_MEDIA' ? !['AUDIO', 'COVER', 'LYRICS'].includes(slot) : slot !== null) throw new Error('Invalid slot')
    return {kind: raw.kind, id: raw.id, slot}
}
const sameTarget = (a, b) => a?.kind === b?.kind && a?.id === b?.id && (a?.slot ?? null) === (b?.slot ?? null)
const referenceSources = ['MUSIC', 'SURPRISE', 'ARTICLE', 'OWNER', 'PUBLICATION']
function checkedReferences(candidate) {
    const fields = ['usages', 'usageCount', 'sourceFailures']
    const present = field => Object.prototype.hasOwnProperty.call(candidate, field)
    if (fields.every(field => !present(field))) return null
    const {usages, usageCount, sourceFailures} = candidate
    if (!fields.every(present) || !Array.isArray(usages) || usages.length > 50 || !version(usageCount)
        || !Array.isArray(sourceFailures) || sourceFailures.length > 5 || new Set(sourceFailures).size !== sourceFailures.length
        || sourceFailures.some(source => !referenceSources.includes(source))) throw new Error('Invalid reference details')
    const total = BigInt(usageCount)
    if (BigInt(usages.length) !== (total < 50n ? total : 50n)) throw new Error('Inconsistent reference total')
    const seen = new Set()
    const checked = usages.map(usage => {
        if (!usage || !referenceSources.includes(usage.source) || typeof usage.businessId !== 'string' || !usage.businessId.trim()
            || usage.businessId.length > 256 || typeof usage.label !== 'string' || !usage.label.trim() || usage.label.length > 64
            || /[\x00-\x1f\x7f]/.test(usage.businessId + usage.label)) throw new Error('Invalid reference usage')
        const key = JSON.stringify([usage.source, usage.businessId, usage.label])
        if (seen.has(key)) throw new Error('Duplicate reference usage')
        seen.add(key)
        return {source: usage.source, businessId: usage.businessId, label: usage.label}
    })
    return {usages: checked, usageCount, sourceFailures: [...sourceFailures], truncated: total > 50n}
}
function checkedPreview(raw, target) {
    if (!raw || !sameTarget(targetOf(raw.target), target) || !version(raw.expectedVersion) || typeof raw.referenceCoverageComplete !== 'boolean' || !Array.isArray(raw.candidates) || raw.candidates.length > 3) throw new Error('Invalid preview')
    const seen = new Set(), candidates = []
    for (const c of raw.candidates) {
        if (!uuid(c.assetId) || seen.has(c.assetId) || !version(c.assetVersion) || !kinds.includes(c.kind) || !['UNVERIFIED', 'SHARED', 'EXCLUSIVE'].includes(c.deletionScope) || !statuses.includes(c.status)) throw new Error('Invalid candidate')
        seen.add(c.assetId)
        candidates.push({...c, referenceDetails: checkedReferences(c)})
    }
    return {...raw, candidates}
}
function checkedReceipt(raw, expected) {
    if (!raw || !uuid(raw.operationId) || !version(raw.originalVersion) || raw.localRemovalCommitted !== true || typeof raw.processingComplete !== 'boolean' || typeof raw.allFilesDeleted !== 'boolean' || !Array.isArray(raw.items) || raw.items.length > 3) throw new Error('Invalid receipt')
    targetOf(raw.target)
    if (expected && (!sameTarget(raw.target, expected.target) || raw.originalVersion !== expected.expectedVersion)) throw new Error('Wrong receipt')
    const seen = new Set()
    for (const item of raw.items) {
        if (!uuid(item.id) || !uuid(item.assetId) || seen.has(item.assetId) || !version(item.assetVersion) || !kinds.includes(item.kind) || !statuses.includes(item.status)) throw new Error('Invalid item')
        seen.add(item.assetId)
    }
    if (raw.allFilesDeleted && (!raw.items.length || raw.items.some(item => item.status !== 'SUCCEEDED'))) throw new Error('Inconsistent receipt')
    return raw
}

/** Durable truth stays on the server. Only the current in-memory confirmation keeps an intent. */
export function useMediaDeletion({auth, capabilities, onCommitted}) {
    const preview = ref(null), operation = ref(null), history = ref([]), busy = ref(false), error = ref(''), requiresPreview = ref(false), uncertain = ref(false)
    let epoch = 0, selection = 0, disposed = false, intent = null, restorePromise = null, selectedTarget = null
    const requests = new Set(), targetRequests = new Set(), notified = new Set()
    const settings = () => validateDeletionCapabilities(capabilities())
    const readable = () => !disposed && Boolean(owner(auth) && settings())
    function canDelete(target) {
        const config = settings()
        return readable() && (target?.kind === 'MUSIC_MEDIA' ? config.mediaDelete : ['MUSIC_TRACK', 'SURPRISE_VIDEO'].includes(target?.kind) && config.catalogDelete)
    }
    const enabled = computed(() => readable() && Boolean(settings().mediaDelete || settings().catalogDelete))
    const canSubmit = computed(() => Boolean(preview.value && !requiresPreview.value && !busy.value && canDelete(preview.value.target)))
    function begin(bound = true) {
        const controller = new AbortController(), who = owner(auth), generation = epoch, chosen = selection
        requests.add(controller); if (bound) targetRequests.add(controller)
        return {signal: controller.signal, current: () => !disposed && who === owner(auth) && generation === epoch && (!bound || chosen === selection), done: () => {requests.delete(controller); targetRequests.delete(controller)}}
    }
    function invalidateTarget() {selection++; for (const request of targetRequests) {request.abort(); requests.delete(request)} targetRequests.clear(); intent = null; busy.value = false; uncertain.value = false}
    function cancel() {invalidateTarget(); selectedTarget = null; preview.value = null; operation.value = null; requiresPreview.value = false; error.value = ''}
    function fail(cause, request) {
        if (!request.current()) return
        if (cause?.status === 401) {auth.clear?.(); return}
        if (cause?.status === 409 || cause?.status === 428) {requiresPreview.value = true; intent = null; uncertain.value = false; error.value = '资料或资源版本已变化，请重新预览；未保存输入已保留'}
        else error.value = '操作结果未确认，请保留原幂等键重新查询或重发，不要重复移除'
    }
    function upsert(value) {history.value = [value, ...history.value.filter(row => row.operationId !== value.operationId)].slice(0, 20)}
    async function prepare(raw) {
        if (!canDelete(raw)) return null
        cancel()
        let target
        try {target = targetOf(raw)} catch {error.value = '移除目标无效，请刷新目录'; return null}
        selectedTarget = target
        const request = begin(); busy.value = true
        try {
            const value = await api.previewMediaDeletion(target, {signal: request.signal})
            if (!request.current()) return null
            preview.value = checkedPreview(value, target); return preview.value
        } catch (cause) {fail(cause, request); return null}
        finally {if (request.current()) busy.value = false; request.done()}
    }
    async function submit() {
        if (!canSubmit.value) return null
        const request = begin(); busy.value = true; error.value = ''
        try {
            if (!intent) {
                const id = globalThis.crypto?.randomUUID?.()
                if (!uuid(id)) throw new Error('Missing secure id')
                const p = preview.value
                intent = {key: id, payload: Object.freeze({target: Object.freeze(targetOf(p.target)), expectedVersion: p.expectedVersion, assetVersions: Object.freeze(Object.fromEntries(p.candidates.map(c => [c.assetId, c.assetVersion])))})}
            }
            const submitted = intent
            const raw = await api.submitMediaDeletion(submitted.payload, submitted.key, {signal: request.signal})
            if (!request.current()) return null
            const value = checkedReceipt(raw, submitted.payload)
            operation.value = value; upsert(value); uncertain.value = false
            preview.value = null; intent = null
            if (!notified.has(value.operationId)) {
                notified.add(value.operationId)
                try {await onCommitted?.(value)} catch {if (request.current()) error.value = '本站移除已提交，页面更新失败，请刷新目录，不要重复提交'}
            }
            return value
        } catch (cause) {if (request.current()) uncertain.value = true; fail(cause, request); return null}
        finally {if (request.current()) busy.value = false; request.done()}
    }
    async function restore() {
        if (!readable()) return null
        if (restorePromise) return restorePromise
        const request = begin(false)
        const task = (async () => {
            try {
                const page = await api.listMediaDeletionOperations(0, 20, {signal: request.signal})
                if (!request.current()) return null
                if (!page || !Array.isArray(page.records) || page.records.length > 20 || !Number.isSafeInteger(page.total) || page.total < 0) throw new Error('Invalid history')
                history.value = page.records.map(value => checkedReceipt(value)); return history.value
            } catch (cause) {fail(cause, request); return null}
            finally {request.done()}
        })()
        restorePromise = task
        try {return await task} finally {if (restorePromise === task) restorePromise = null}
    }
    async function query(id) {
        if (!readable() || busy.value || !uuid(id)) return null
        cancel(); const request = begin(); busy.value = true
        try {const raw = await api.getMediaDeletionOperation(id, {signal: request.signal}); if (!request.current()) return null; const value = checkedReceipt(raw); if (value.operationId !== id) throw new Error('Wrong receipt'); operation.value = value; upsert(value); return value}
        catch (cause) {fail(cause, request); return null}
        finally {if (request.current()) busy.value = false; request.done()}
    }
    async function confirmOwnership(asset, scope, note) {
        if (!enabled.value || busy.value || requiresPreview.value || !preview.value || !preview.value.candidates.some(c => c.assetId === asset?.assetId && c.assetVersion === asset?.assetVersion) || !['SHARED', 'EXCLUSIVE'].includes(scope) || scope === 'EXCLUSIVE' && (!note?.trim() || !preview.value.environmentId)) return null
        const request = begin(); busy.value = true; error.value = ''
        try {
            const value = await api.confirmMediaDeletionOwnership(asset.assetId, {scope, expectedVersion: asset.assetVersion, note: note?.trim() || null}, {signal: request.signal})
            if (!request.current()) return null
            if (!value || value.assetId !== asset.assetId || !version(value.assetVersion) || BigInt(value.assetVersion) !== BigInt(asset.assetVersion) + 1n || value.deletionScope !== scope) throw new Error('Invalid ownership response')
            requiresPreview.value = true; intent = null; uncertain.value = false; error.value = '归属声明已保存，请重新预览后再确认本站移除'; return value
        } catch (cause) {fail(cause, request); return null}
        finally {if (request.current()) busy.value = false; request.done()}
    }
    function reset() {epoch++; for (const request of requests) request.abort(); requests.clear(); targetRequests.clear(); cancel(); history.value = []; restorePromise = null; notified.clear()}
    const stopIdentity = watch(() => [owner(auth), auth.token], () => {reset(); if (readable()) void restore()}, {flush: 'sync'})
    function dispose() {if (disposed) return; disposed = true; reset(); stopIdentity()}
    onBeforeUnmount(dispose)
    const isTargetBusy = id => busy.value && selectedTarget?.id === id
    return {preview, operation, history, busy, error, requiresPreview, uncertain, enabled, canSubmit, canDelete, prepare, submit, query, restore, confirmOwnership, cancel, dispose, isTargetBusy}
}
