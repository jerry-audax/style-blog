<template>
  <section v-if="operations.length || error || preparing" class="upload-panel" aria-label="媒体上传操作">
    <header><h3>上传操作</h3><span>只有数据库登记完成才可使用；图片上传不会自动保存文章或资料。</span></header>
    <p v-if="error" class="upload-error" role="alert">{{ error }}</p>
    <p v-if="preparing" role="status">正在后台校验文件，请稍候…</p>
    <article v-for="op in operations" :key="op.operationId" class="upload-operation">
      <div class="operation-heading"><strong>{{ label(op) }}</strong><span>{{ slotNames[op.slot] || '资源' }} · {{ op.targetId || (op.targetType === 'IMAGE_CREATE' ? '新增图片' : '新增视频') }}</span></div>
      <code>操作编号：{{ op.operationId }}</code>
      <template v-if="op.totalBytes">
        <label v-if="activeId === op.operationId">浏览器发送：{{ bytes(browserBytes) }} / {{ bytes(op.totalBytes) }}
          <progress aria-label="浏览器发送进度" :max="op.totalBytes" :value="browserBytes"></progress>
        </label>
        <label>图床已确认：{{ bytes(op.confirmedBytes) }} / {{ bytes(op.totalBytes) }} · {{ op.confirmedChunks }} / {{ op.totalChunks }} 块
          <progress aria-label="图床已确认进度" :max="op.totalBytes" :value="op.confirmedBytes"></progress>
        </label>
      </template>
      <p v-if="op.status === 'PAUSED'">已暂停。请选择同一份原文件继续，已确认的分块不会重传。</p>
      <p v-if="op.status === 'UNKNOWN' || op.status === 'LOCAL_PENDING'">结果未确认。请按原编号查询或核对，勿重新上传。</p>
      <p v-if="op.status === 'CONFLICT'">资料已变化，未保存输入已保留。先刷新目标版本，再确认绑定已上传资源。</p>
      <small v-if="op.safeErrorCode">状态代码：{{ op.safeErrorCode }}</small>
      <small v-if="op.resolvedByOperationId">已由操作 {{ op.resolvedByOperationId }} 完成绑定</small>
      <div class="operation-actions">
        <slot name="result" :operation="op"/>
        <button type="button" :disabled="!enabled" @click="uploader.query(op)">重新查询</button>
        <button v-if="op.canPause" type="button" :disabled="!enabled || op.pauseRequested" @click="uploader.pause(op)">请求暂停</button>
        <label v-if="op.canResumeContent && ['READY', 'PAUSED'].includes(op.status)" class="resume-file">选择原文件继续<input type="file" :aria-label="'选择原文件继续 ' + op.operationId" :disabled="!enabled || busy" @change="resume(op, $event)"></label>
        <button v-if="op.canRecover" type="button" :disabled="!enabled || busy" @click="uploader.recover(op)">核对并恢复</button>
        <template v-if="op.canBind && loadVersion">
          <button type="button" :disabled="!enabled || busy" @click="refreshVersion(op)">刷新目标版本</button>
          <button v-if="versions[op.operationId] != null" type="button" :disabled="!enabled || busy" @click="confirmBind(op)">按版本 {{ versions[op.operationId] }} 绑定</button>
        </template>
      </div>
    </article>
  </section>
</template>
<script setup>
import {computed, ref, unref} from 'vue'
const props = defineProps({uploader: {type: Object, required: true}, loadVersion: Function})
const operations = computed(() => unref(props.uploader.operations)), error = computed(() => unref(props.uploader.error))
const busy = computed(() => unref(props.uploader.busy)), preparing = computed(() => unref(props.uploader.preparing))
const enabled = computed(() => unref(props.uploader.enabled))
const browserBytes = computed(() => unref(props.uploader.browserBytes)), activeId = computed(() => unref(props.uploader.activeOperationId))
const versions = ref({}), slotNames = {AUDIO: '音频', COVER: '封面', LYRICS: '歌词', VIDEO: '视频', IMAGE:'图片', AVATAR:'头像'}
const names = {LOCAL_PENDING: '等待查询', READY: '等待发送', UPLOADING: '上传中', PAUSED: '已暂停', UPLOADED: '登记绑定中', UNKNOWN: '结果未知', CONFLICT: '绑定冲突', FAILED: '未完成', SUCCEEDED: '已完成'}
const label = op => op.pauseRequested ? '请求暂停中，正在等待在途分块确认' : names[op.status] || '状态待核对'
const bytes = value => Number.isFinite(value) ? `${value.toLocaleString('zh-CN')} B` : '未知'
function resume(op, event) {const file = event.target.files?.[0]; event.target.value = ''; if (file) void props.uploader.resume(op, file)}
async function refreshVersion(op) {try {const version = await props.loadVersion(op); if (!Number.isSafeInteger(version) || version < 0) throw new Error(); versions.value[op.operationId] = version} catch {delete versions.value[op.operationId]; props.uploader.error.value = '目标版本无法确认，请刷新目录后重试；原输入未改变'}}
function confirmBind(op) {if (window.confirm(`确认按版本 ${versions.value[op.operationId]} 绑定已上传资源？不会重新上传，当前未保存资料不会自动提交。`)) void props.uploader.bind(op, versions.value[op.operationId])}
</script>
<style scoped>
.upload-panel{margin-top:18px;padding:18px;border:1px solid var(--admin-line);border-radius:12px;background:var(--admin-panel);color:var(--admin-text);font-family:var(--admin-font-body)}
header,.operation-heading,.operation-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}h3{margin:0;font-family:var(--admin-font-display);font-size:16px}header span,.operation-heading span,small{color:var(--admin-muted);font-size:12px}.upload-operation{display:grid;gap:10px;margin-top:16px;padding-top:16px;border-top:1px solid var(--admin-line)}code{overflow-wrap:anywhere;font:12px ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--admin-muted)}label{display:grid;gap:6px;font-size:12px}progress{width:100%;height:8px;accent-color:var(--admin-accent)}p{font-size:13px;margin:0;line-height:1.6}.upload-error{color:var(--admin-danger);margin:12px 0}.operation-actions button,.resume-file{padding:8px 11px;border:1px solid var(--admin-line-strong);border-radius:7px;background:transparent;color:inherit;font-size:12px;cursor:pointer}.resume-file input{max-width:220px;font-size:12px}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible{outline:2px solid var(--admin-accent);outline-offset:3px}
@media(max-width:600px){.upload-panel{padding:12px}.resume-file input{max-width:180px}}
</style>
