<template>
  <section v-if="preview || receipts.length || error || busy" class="deletion-panel" aria-label="本站移除与回执">
    <header><h3>本站移除与回执</h3><button type="button" :disabled="busy" @click="deleter.restore()">刷新回执</button></header>
    <p class="boundary">本站移除不等于图床删除。当前阶段保留所有图床文件，不会自动物理清理。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="preview" class="deletion-preview">
      <h4>确认移除{{ targetName(preview.target) }}</h4>
      <p>目标：{{ preview.target.id }} · 版本 {{ preview.expectedVersion }}</p>
      <p v-if="!preview.referenceCoverageComplete">引用核对尚未完整，不代表资源无人使用。本站移除不提供恢复按钮。</p>
      <article v-for="asset in preview.candidates" :key="asset.assetId" class="candidate">
        <strong>{{ mediaNames[asset.kind] || '资源' }} · {{ reason(asset.reason) }}</strong>
        <code>{{ asset.assetId }} · 版本 {{ asset.assetVersion }}</code>
        <div v-if="asset.referenceDetails" class="reference-details">
          <p>已发现{{ asset.referenceDetails.usageCount }}处用途</p>
          <ul v-if="asset.referenceDetails.usages.length" class="reference-usages" aria-label="已发现用途">
            <li v-for="usage in asset.referenceDetails.usages" :key="usageKey(usage)">{{ sourceNames[usage.source] }} · {{ usage.label }} · {{ usage.businessId }}</li>
          </ul>
          <p v-if="asset.referenceDetails.truncated">仅显示前50处，共{{ asset.referenceDetails.usageCount }}处</p>
          <p v-if="asset.referenceDetails.sourceFailures.length">{{ asset.referenceDetails.sourceFailures.map(source => sourceNames[source]).join('、') }}核对尚未完成，以上不是完整引用清单。</p>
        </div>
        <p v-else>服务尚未提供用途明细，不能据此判断资源无人使用。</p>
        <details>
          <summary>声明资源归属（可选，不会授权本阶段物理删除）</summary>
          <p>当前环境：{{ preview.environmentId || '未配置' }}；归属：{{ asset.deletionScope }}</p>
          <button type="button" :disabled="busy || needsPreview" @click="deleter.confirmOwnership(asset, 'SHARED', null)">标记共享</button>
          <label><input v-model="acknowledged[asset.assetId]" type="checkbox" :disabled="busy || needsPreview">我已核对其他环境没有使用此文件；独占声明不是跨环境核验。</label>
          <label>独占确认依据<input v-model="notes[asset.assetId]" aria-label="独占确认依据" maxlength="500" :disabled="busy || needsPreview" placeholder="不要填写媒体链接或凭据"></label>
          <button type="button" :disabled="busy || needsPreview || !preview.environmentId || !acknowledged[asset.assetId] || !notes[asset.assetId]?.trim()" @click="deleter.confirmOwnership(asset, 'EXCLUSIVE', notes[asset.assetId])">确认独占归属</button>
        </details>
      </article>
      <p v-if="!preview.candidates.length">没有本站托管资源；只移除本站信息或已保存的外部封面引用。</p>
      <div class="actions">
        <button v-if="needsPreview" type="button" :disabled="busy" @click="deleter.prepare(preview.target)">重新预览</button>
        <button v-else type="button" class="remove" :disabled="!canSubmit" @click="deleter.submit()">{{ uncertain ? '按原幂等键重发' : '确认从本站移除' }}</button>
        <button type="button" @click="deleter.cancel()">取消预览</button>
      </div>
    </div>
    <article v-for="op in receipts" :key="op.operationId" class="deletion-receipt">
      <strong>{{ label(op) }}</strong>
      <code>操作编号：{{ op.operationId }}</code>
      <span>{{ targetName(op.target) }} · {{ op.target.id }}</span>
      <small v-for="item in op.items" :key="item.id">{{ mediaNames[item.kind] || '资源' }}：{{ reason(item.reason) }}</small>
      <button type="button" :disabled="busy" @click="deleter.query(op.operationId)">查询回执</button>
    </article>
  </section>
</template>
<script setup>
import {computed, ref, unref, watch} from 'vue'
const props = defineProps({deleter: {type: Object, required: true}})
const preview = computed(() => unref(props.deleter.preview)), error = computed(() => unref(props.deleter.error)), busy = computed(() => unref(props.deleter.busy))
const needsPreview = computed(() => unref(props.deleter.requiresPreview)), uncertain = computed(() => unref(props.deleter.uncertain)), canSubmit = computed(() => unref(props.deleter.canSubmit))
const receipts = computed(() => {const current = unref(props.deleter.operation), history = unref(props.deleter.history) || []; return current ? [current, ...history.filter(row => row.operationId !== current.operationId)] : history})
const notes = ref({}), acknowledged = ref({})
watch(preview, () => {notes.value = {}; acknowledged.value = {}}, {flush: 'sync'})
const mediaNames = {AUDIO: '音频', IMAGE: '封面', VIDEO: '视频', LYRICS: '歌词'}
const sourceNames = {MUSIC: '歌曲', SURPRISE: '惊喜视频', ARTICLE: '文章', OWNER: '头像资料', PUBLICATION: '发布版本'}
const usageKey = usage => JSON.stringify([usage.source, usage.businessId, usage.label])
const reasons = {OWNERSHIP_UNVERIFIED: '归属尚未确认，文件保留', SHARED: '共享资源，文件保留', ENVIRONMENT_MISMATCH: '属于其他环境，文件保留', REFERENCE_COVERAGE_INCOMPLETE: '引用核对未完成，文件保留', IN_USE: '仍被引用，文件保留', WRITE_GUARDS_INCOMPLETE: '引用写入保护未完成，文件保留', ASSET_NOT_ACTIVE: '资源状态需核对，文件保留', NONE: '请查看回执处理状态', PROVIDER_FAILURE: '图床操作失败，需核对', OUTCOME_UNKNOWN: '结果未知，需核对'}
const reason = code => reasons[code] || '状态待核对，不能认定文件已删除'
const targetName = target => target.kind === 'MUSIC_TRACK' ? '歌曲' : target.kind === 'SURPRISE_VIDEO' ? '惊喜视频' : {AUDIO: '歌曲音频', COVER: '歌曲封面', LYRICS: '歌曲歌词'}[target.slot] || '资源'
const label = op => !op.items.length ? '本站移除完成，无本站托管文件需处理' : op.items.every(item => item.status === 'RETAINED') ? '已从本站移除，图床文件保留' : op.allFilesDeleted ? '本站已移除，图床文件已删除' : '本站已移除，文件处理状态待核对'
</script>
<style scoped>
.deletion-panel{margin-top:18px;padding:18px;border:1px solid var(--admin-line);border-radius:12px;background:var(--admin-panel);color:var(--admin-text);font-family:var(--admin-font-body)}header,.actions{display:flex;gap:12px;justify-content:space-between;align-items:center;flex-wrap:wrap}h3{font:600 16px var(--admin-font-display);margin:0}h4{font-size:14px;margin:0}.boundary{color:var(--admin-muted)}p{font-size:13px;line-height:1.7}p[role=alert],.remove{color:var(--admin-danger)}.deletion-preview,.deletion-receipt,.candidate{display:grid;gap:10px;border-top:1px solid var(--admin-line);margin-top:16px;padding-top:16px}code{font:12px ui-monospace,SFMono-Regular,Consolas,monospace;overflow-wrap:anywhere}small,code{color:var(--admin-muted)}button,input:not([type=checkbox]){border:1px solid var(--admin-line-strong);border-radius:7px;background:transparent;color:inherit;padding:8px 11px;font:inherit}button{cursor:pointer;justify-self:start;font-size:12px}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,summary:focus-visible{outline:2px solid var(--admin-accent);outline-offset:3px}label{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:12px 0;font-size:12px}input:not([type=checkbox]){flex:1;min-width:160px;max-width:100%}summary{cursor:pointer;color:var(--admin-muted);font-size:12px}details{padding-top:4px}.actions{justify-content:flex-start}@media(max-width:600px){.deletion-panel{padding:12px}}
.reference-details p{margin:6px 0}.reference-usages{margin:0;padding-left:20px;max-height:220px;overflow:auto;font-size:12px;line-height:1.7;overflow-wrap:anywhere}
</style>
