import {afterEach, beforeEach, expect, it, vi} from 'vitest'
import {createApp, h, nextTick, reactive} from 'vue'
import {useMediaDeletion} from '../composables/useMediaDeletion'
import MediaDeletionPanel from '../components/MediaDeletionPanel.vue'

vi.mock('../api/index.js', () => ({previewMediaDeletion: vi.fn(), submitMediaDeletion: vi.fn(), getMediaDeletionOperation: vi.fn(), listMediaDeletionOperations: vi.fn(), confirmMediaDeletionOwnership: vi.fn()}))
import * as api from '../api/index.js'

const assetId = '00000000-0000-0000-0000-000000000902'
const target = {kind: 'MUSIC_MEDIA', id: 'song', slot: 'COVER'}
const caps = {metadataWrite: true, metadataVersionRequired: true, mediaUpload: false, mediaReplace: false, mediaDelete: true, catalogDelete: true, deleteProtocol: 'tracked-delete-v1'}
const usage = (id = '1', label = '文章封面') => ({source: 'ARTICLE', businessId: id, label})
const candidate = extra => ({assetId, kind: 'IMAGE', assetVersion: '0', deletionScope: 'UNVERIFIED', exclusiveEnvironment: null, status: 'RETAINED', reason: 'OWNERSHIP_UNVERIFIED', usages: [usage()], usageCount: '1', sourceFailures: ['PUBLICATION'], ...extra})
const preview = extra => ({target: {...target}, expectedVersion: '7', referenceCoverageComplete: false, environmentId: 'local', candidates: [candidate(extra)]})
const flush = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }
let app, host, state, auth

async function mount() {
    auth = reactive({initialized: true, user: {owner: true, userId: 1}, verifiedUserId: '1', token: 'test-session', clear: vi.fn()})
    host = document.createElement('div'); document.body.append(host)
    app = createApp({setup() {
        state = useMediaDeletion({auth, capabilities: () => caps})
        return () => h(MediaDeletionPanel, {deleter: state})
    }})
    app.mount(host); await flush()
}
beforeEach(() => {
    vi.resetAllMocks()
    api.listMediaDeletionOperations.mockResolvedValue({records: [], total: 0})
    api.previewMediaDeletion.mockResolvedValue(preview())
})
afterEach(() => { app?.unmount(); host?.remove(); vi.restoreAllMocks() })

it('renders known usages and names the incomplete source rather than calling the resource unused', async () => {
    await mount(); await state.prepare(target); await flush()
    expect(host.textContent).toContain('已发现1处用途')
    expect(host.textContent).toContain('文章封面')
    expect(host.textContent).toContain('发布版本')
    expect(host.textContent).toContain('核对尚未完成')
    expect(host.querySelector('[aria-label="已发现用途"] li')?.textContent).toContain('1')
})

it('renders text safely without executing a label as HTML', async () => {
    api.previewMediaDeletion.mockResolvedValue(preview({usages: [usage('1', '<img src=x onerror=alert(1)>')]}))
    await mount(); await state.prepare(target); await flush()
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>')
    expect(host.querySelector('img')).toBeNull()
})

it('shows only the 50 supplied usages while preserving the true total', async () => {
    api.previewMediaDeletion.mockResolvedValue(preview({usages: Array.from({length: 50}, (_, i) => usage(String(i + 1))), usageCount: '51'}))
    await mount(); await state.prepare(target); await flush()
    expect(host.querySelectorAll('[aria-label="已发现用途"] li')).toHaveLength(50)
    expect(host.textContent).toContain('仅显示前50处，共51处')
})

it('never rounds a decimal-string total through Number', async () => {
    api.previewMediaDeletion.mockResolvedValue(preview({usages: Array.from({length: 50}, (_, i) => usage(String(i + 1))), usageCount: '9007199254740993'}))
    await mount(); await state.prepare(target); await flush()
    expect(host.textContent).toContain('9007199254740993')
    expect(host.textContent).not.toContain('9007199254740992')
})

it('an old response lacking every new field is unknown, not zero references', async () => {
    const old = preview(); delete old.candidates[0].usages; delete old.candidates[0].usageCount; delete old.candidates[0].sourceFailures
    api.previewMediaDeletion.mockResolvedValue(old)
    await mount(); await state.prepare(target); await flush()
    expect(host.textContent).toContain('服务尚未提供用途明细')
    expect(host.textContent).not.toContain('已发现0处用途')
    expect(host.querySelector('[aria-label="已发现用途"]')).toBeNull()
    expect(state.preview.value.target).toEqual(target)
})

it.each([
    {usageCount: 1}, {usageCount: '01'}, {usageCount: '0'}, {sourceFailures: ['RAW_SQL_ERROR']},
    {usages: undefined}, {sourceFailures: undefined},
    {usages: [usage(), usage()], usageCount: '2'},
    {usages: Array.from({length: 51}, (_, i) => usage(String(i + 1))), usageCount: '51'},
])('rejects inconsistent reference details without making a usable confirmation: %j', async extra => {
    api.previewMediaDeletion.mockResolvedValue(preview(extra))
    await mount(); await state.prepare(target); await flush()
    expect(state.preview.value).toBeNull()
    expect(state.canSubmit.value).toBe(false)
    expect(state.error.value).toBeTruthy()
    expect(api.submitMediaDeletion).not.toHaveBeenCalled()
})

it('late old-session details cannot reappear after identity resets', async () => {
    await mount(); let finish
    api.previewMediaDeletion.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const pending = state.prepare(target)
    auth.token = 'new-test-session'; await flush()
    finish(preview({usages: [usage('1', '旧会话用途')]})); await pending; await flush()
    expect(state.preview.value).toBeNull()
    expect(host.textContent).not.toContain('旧会话用途')
})

it('out-of-order details cannot overwrite the latest selected target', async () => {
    await mount(); let finish
    api.previewMediaDeletion.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const pending = state.prepare(target)
    const other = {kind: 'MUSIC_TRACK', id: 'another', slot: null}
    api.previewMediaDeletion.mockResolvedValue({...preview({usages: [usage('2', '新目标用途')]}), target: other})
    await state.prepare(other)
    finish(preview({usages: [usage('1', '旧目标用途')]})); await pending; await flush()
    expect(state.preview.value.target).toEqual(other)
    expect(host.textContent).toContain('新目标用途')
    expect(host.textContent).not.toContain('旧目标用途')
})
