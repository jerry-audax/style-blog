<template>
  <section class="surprise-page">
    <header class="surprise-heading"><div><h1>惊喜视频</h1><p>上传后公开端会随机播放已启用的视频，视频直接有声播放，不需要封面。</p></div></header>
    <section class="surprise-card upload-card">
      <h2>添加视频</h2>
      <div class="surprise-form"><label>标题<input v-model.trim="newTitle" maxlength="120" placeholder="惊喜视频" :disabled="uploadPending"></label><label>排序<input v-model.number="newSort" type="number" min="0" max="100000" :disabled="uploadPending"></label><label class="check"><input v-model="newEnabled" type="checkbox" :disabled="uploadPending">立即启用</label><label class="upload-button primary" :class="{disabled: busy || !canUploadVideo}" :aria-disabled="busy || !canUploadVideo">选择视频<input type="file" accept="video/*" hidden :disabled="busy || !canUploadVideo" @change="upload"></label></div>
      <p class="muted">支持 MP4、WebM、MOV 等浏览器可播放格式，单个文件最大 200 MiB。</p>
      <p v-if="capabilityState === 'loading'" class="muted">正在检查资源操作能力…</p>
      <p v-else-if="capabilityState === 'error'" class="muted">能力检查失败，写操作已禁用，请刷新重试。</p>
      <p v-else-if="trackedDeletions" class="muted">支持移除回执查询；本站移除按服务端开放能力操作，图床文件保留。</p>
      <p v-else-if="trackedUploads" class="muted">支持可追踪上传与恢复；删除功能尚未开放。</p>
      <p v-else-if="!capabilities.mediaUpload || !capabilities.catalogDelete" class="muted">资源上传和删除尚未接入；当前可编辑已登记视频的资料。</p>
      <MediaUploadPanel :uploader="uploader" />
      <MediaDeletionPanel :deleter="deleter" />
    </section>
    <p v-if="message" class="surprise-message" :class="{error}">{{ message }}</p>
    <section class="surprise-card">
      <div class="card-title"><h2>视频列表</h2><button type="button" @click="refresh" :disabled="busy || deleter.busy.value">刷新</button></div>
      <p v-if="busy && !videos.length" class="empty">正在加载…</p>
      <p v-else-if="!videos.length" class="empty">{{ allowed('mediaUpload') ? '还没有视频，可以上传一条。' : '暂无已登记视频，上传功能将在后续开放' }}</p>
      <div v-else class="video-list">
        <article v-for="video in videos" :key="video.id" class="video-row">
          <video :src="video.url" controls preload="metadata"></video>
          <div class="video-fields">
            <label>标题<input v-model.trim="video.title" maxlength="120" :disabled="busy"></label>
            <label>排序<input v-model.number="video.sortOrder" type="number" min="0" max="100000" :disabled="busy"></label>
            <label class="check"><input v-model="video.enabled" type="checkbox" :disabled="busy">公开随机播放</label>
            <small>{{ formatSize(video.size) }} · {{ video.mediaType || '类型未知' }}</small>
            <small v-if="capabilities?.metadataVersionRequired && !validVersion(video.version)">版本不可用，请刷新</small>
          </div>
          <div class="video-actions">
            <button type="button" @click="save(video)" :disabled="busy || !canSave(video)">保存</button>
            <button type="button" class="danger" @click="remove(video)" :disabled="busy || !canRemove(video)">删除</button>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>

<script setup>
import {onMounted, onBeforeUnmount, computed, ref} from 'vue'
import {useAdminAuthStore} from '../stores/auth'
import {useMediaUpload} from '../composables/useMediaUpload'
import {useMediaDeletion} from '../composables/useMediaDeletion'
import {validateDeletionCapabilities} from '../media/deletionCapabilities'
import MediaDeletionPanel from '../components/MediaDeletionPanel.vue'
import {validateUploadCapabilities, canUseLegacyUploads} from '../media/uploadCapabilities'
import MediaUploadPanel from '../components/MediaUploadPanel.vue'
import {createSurpriseVideo, deleteSurpriseVideo, getSurpriseVideos, updateSurpriseVideo, getMediaCapabilities} from '../api/index.js'
const videos = ref([]), busy = ref(false), message = ref(''), error = ref(false)
const newTitle = ref('惊喜视频'), newSort = ref(0), newEnabled = ref(true)
const capabilities = ref(null), capabilityState = ref('loading')
const fields = ['metadataWrite', 'metadataVersionRequired', 'mediaUpload', 'mediaReplace', 'mediaDelete', 'catalogDelete']
const allowed = field => capabilityState.value === 'ready' && capabilities.value?.[field] === true && (!['mediaDelete','catalogDelete'].includes(field) || canUseLegacyUploads(capabilities.value))
const trackedUploads = computed(() => validateUploadCapabilities(capabilities.value))
const uploader = useMediaUpload({auth:useAdminAuthStore(),capabilities:()=>capabilities.value,targetType:'SURPRISE_CREATE',onSucceeded:mergeUploadedVideo})
const deleter = useMediaDeletion({auth:useAdminAuthStore(),capabilities:()=>capabilities.value,onCommitted:mergeRemovedVideo})
const trackedDeletions = computed(() => validateDeletionCapabilities(capabilities.value))
const deletionTarget = video => ({kind:'SURPRISE_VIDEO',id:video.id,slot:null})
const canRemove = video => !uploadPending.value && !deleter.busy.value && (trackedDeletions.value ? deleter.canDelete(deletionTarget(video)) : allowed('catalogDelete'))
const uploadPending = computed(() => uploader.busy.value || uploader.operations.value.some(op=>['LOCAL_PENDING','READY','UPLOADING','PAUSED','UPLOADED','UNKNOWN'].includes(op.status)))
const canUploadVideo = computed(() => !deleter.busy.value && !uploadPending.value && allowed('mediaUpload') && (trackedUploads.value ? uploader.enabled.value : canUseLegacyUploads(capabilities.value)))
const validVersion = version => Number.isSafeInteger(version) && version >= 0
const canSave = video => !deleter.isTargetBusy(video.id) && allowed('metadataWrite') && (!capabilities.value.metadataVersionRequired || validVersion(video.version))
let alive = true, messageTimer = null, capabilityRevision = 0, catalogReadRevision = 0
const show = (text, failed = false) => {
  if (!alive) return
  clearTimeout(messageTimer); message.value = text; error.value = failed
  if (!failed) messageTimer = window.setTimeout(() => { if (alive) message.value = '' }, 4000)
}
async function loadCapabilities() {
  const current = ++capabilityRevision
  capabilityState.value = 'loading'; capabilities.value = null
  try {
    const result = await getMediaCapabilities()
    if (!result || fields.some(field => typeof result[field] !== 'boolean')) throw new Error('资源能力响应异常')
    if (alive && current === capabilityRevision) { capabilities.value = result; capabilityState.value = 'ready'; void uploader.restore(); void deleter.restore() }
  } catch { if (alive && current === capabilityRevision) capabilityState.value = 'error' }
}
async function load() {
  if (busy.value) return
  const current = ++catalogReadRevision
  busy.value = true
  try {
    const rows = await getSurpriseVideos()
    if (!Array.isArray(rows)) throw new Error('视频列表响应异常')
    if (alive && current === catalogReadRevision) videos.value = rows
  } catch (e) { if (current === catalogReadRevision) show(e.message || '视频列表加载失败', true) } finally { if (alive) busy.value = false }
}
async function refresh() { if (!busy.value && !deleter.busy.value) await Promise.all([loadCapabilities(), load()]) }
async function upload(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  if (busy.value || !canUploadVideo.value) { show('资源上传和删除尚未接入', true); return }
  if (trackedUploads.value) {
    const sortOrder=Number(newSort.value)
    if (!Number.isSafeInteger(sortOrder) || sortOrder<0 || sortOrder>100000) { show('排序须为0至100000的整数',true); return }
    await uploader.start({targetType:'SURPRISE_CREATE',slot:'VIDEO',title:newTitle.value||'惊喜视频',enabled:newEnabled.value,sortOrder},file)
    return
  }
  busy.value = true
  try {
    const uploaded = await createSurpriseVideo(file, newTitle.value || '惊喜视频', newEnabled.value, Number(newSort.value) || 0)
    if (alive) videos.value.push(uploaded)
    show('视频已上传')
  } catch (e) { show(`${e.message || '视频上传失败'}；结果未确认，请先刷新核对`, true) } finally { if (alive) busy.value = false }
}
function mergeUploadedVideo(result) {
  const saved=result.video
  if (!alive || !saved || !validVersion(saved.version)) return
  const existing=videos.value.find(item=>item.id===saved.id)
  if (!existing) videos.value.push(saved)
  else if (!validVersion(existing.version) || saved.version>=existing.version) Object.assign(existing,{url:saved.url,path:saved.path,mediaType:saved.mediaType,size:saved.size,version:saved.version})
  show('视频已登记并绑定，公开端将自动更新')
}
async function save(video) {
  if (busy.value || !canSave(video)) return
  const versioned = capabilities.value.metadataVersionRequired
  const sortOrder = Number(video.sortOrder)
  if (!Number.isSafeInteger(sortOrder) || sortOrder < 0 || sortOrder > 100000) { show('排序须为0至100000的整数', true); return }
  busy.value = true
  try {
    const payload = {title: video.title, enabled: video.enabled, sortOrder}
    if (versioned) payload.expectedVersion = video.version
    const saved = await updateSurpriseVideo(video.id, payload)
    if (versioned && !validVersion(saved?.version)) { video.version = null; show('保存结果版本未确认，请刷新核对', true); return }
    if (alive) Object.assign(video, saved)
    show('已保存')
  } catch (e) { show(e.status === 409 ? '资料已变化，请刷新；未保存输入已保留' : e.message || '保存失败', true) } finally { if (alive) busy.value = false }
}
async function remove(video) {
  if (busy.value || !canRemove(video)) { show('资源上传和删除尚未接入', true); return }
  if (trackedDeletions.value) { await deleter.prepare(deletionTarget(video)); return }
  if (!window.confirm(`确定删除“${video.title}”吗？`)) return
  busy.value = true
  try { await deleteSurpriseVideo(video.id); if (alive) videos.value = videos.value.filter(item => item.id !== video.id); show('已删除') }
  catch (e) { show(e.message || '删除失败', true) } finally { if (alive) busy.value = false }
}
function mergeRemovedVideo(operation) {
  if (!alive || operation.target.kind !== 'SURPRISE_VIDEO') return
  catalogReadRevision++
  videos.value = videos.value.filter(row => row.id !== operation.target.id)
  show('已从本站移除，图床文件保留')
}
const formatSize = bytes => {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '未知'
  const units = ['B', 'KiB', 'MiB', 'GiB']; let value = bytes, index = 0
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++ }
  return `${value.toFixed(index ? 1 : 0)} ${units[index]}`
}
onMounted(() => { loadCapabilities(); load() })
onBeforeUnmount(() => { alive = false; clearTimeout(messageTimer) })
</script>

<style scoped>
.upload-button.disabled { opacity: .5; cursor: not-allowed; }
.surprise-page{max-width:1100px;margin:auto}.surprise-heading{margin-bottom:22px}.surprise-heading h1{margin:0 0 8px}.surprise-heading p,.muted,.empty{color:var(--admin-muted,#73809a)}.surprise-card{background:var(--admin-card,#fff);border:1px solid var(--admin-border,#e5e9f2);border-radius:16px;padding:24px;margin-bottom:18px}.surprise-form{display:flex;gap:14px;align-items:end;flex-wrap:wrap}.surprise-form label,.video-fields label{display:flex;flex-direction:column;gap:7px;font-size:13px;color:var(--admin-muted,#73809a)}input{border:1px solid var(--admin-border,#dfe4ef);border-radius:8px;padding:9px 11px;color:inherit;background:transparent}.check{flex-direction:row!important;align-items:center;align-self:center}.check input{accent-color:#4f46e5}.upload-button{cursor:pointer}.upload-button.primary,button.primary{background:#4f46e5;color:#fff;border:0;border-radius:8px;padding:10px 16px}button{border:1px solid var(--admin-border,#dfe4ef);background:transparent;border-radius:8px;padding:9px 14px;cursor:pointer;color:inherit}.card-title{display:flex;justify-content:space-between;align-items:center}.video-list{display:grid;gap:14px}.video-row{display:grid;grid-template-columns:260px 1fr auto;gap:18px;align-items:center;border-top:1px solid var(--admin-border,#e5e9f2);padding-top:14px}.video-row video{width:260px;max-height:150px;background:#111;border-radius:10px}.video-fields{display:flex;gap:12px;align-items:end;flex-wrap:wrap}.video-fields small{color:var(--admin-muted,#73809a)}.video-actions{display:flex;gap:8px}.danger{color:#d14343}@media(max-width:800px){.video-row{grid-template-columns:1fr}.video-row video{width:100%}}
</style>
