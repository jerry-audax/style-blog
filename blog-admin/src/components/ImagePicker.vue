<template>
  <el-dialog :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)" :title="purpose === 'cover' ? '从图片库选择封面' : '从图片库插入正文图片'" width="760px" append-to-body>
    <p class="picker-hint">复用 Cloudflare ImgBed · Telegram 的现有图片。选择只修改当前编辑内容，不删除文件。</p>
    <form class="picker-toolbar" @submit.prevent="find"><el-input v-model="search" placeholder="搜索图片名称" aria-label="搜索图床图片" :disabled="uploading" clearable/><el-button native-type="submit" :disabled="uploading">搜索</el-button><el-button type="primary" :loading="uploading" @click="fileInput?.click()">上传并使用</el-button><input ref="fileInput" type="file" :accept="imageAccept" hidden @change="upload"/></form>
    <p v-if="error" class="picker-error" role="alert">{{ error }} <el-button size="small" :disabled="loading || uploading" @click="load">刷新核对</el-button></p>
    <el-skeleton v-if="loading" :rows="4" animated/>
    <div v-else-if="!error" class="picker-grid">
      <button v-for="image in images" :key="image.path" type="button" class="picker-image" :disabled="uploading" @click="choose(image)"><img :src="image.url" loading="lazy" alt=""/><span :title="image.path">{{ image.path.split('/').at(-1) }}</span><small>{{ imagePurposeLabel(image.path) }}</small></button>
      <p v-if="!images.length" class="picker-empty">没有匹配的图片。调整搜索条件，或上传一张新图片。</p>
    </div>
    <el-pagination v-if="total > 12" v-model:current-page="page" :page-size="12" :total="total" layout="prev, pager, next" :disabled="loading || uploading" @current-change="load"/>
    <template #footer><span class="picker-hint">JPEG / PNG / GIF / WebP · 最大 10 MiB</span><el-button @click="$emit('update:modelValue', false)">关闭</el-button></template>
  </el-dialog>
</template>
<script setup>
import {onBeforeUnmount, ref, watch} from 'vue'
import {listImages, uploadImage} from '../api'
import {imageAccept, imagePurposeLabel, validateImageUpload} from '../utils/images'
const props = defineProps({modelValue: Boolean, purpose: {type: String, default: 'article', validator: value => ['article', 'cover'].includes(value)}})
const emit = defineEmits(['update:modelValue', 'select'])
const images = ref([]), page = ref(1), total = ref(0), search = ref(''), loading = ref(false), uploading = ref(false), error = ref(''), fileInput = ref(null)
let generation = 0, activeSearch = '', disposed = false
const load = async () => {
  const request = ++generation
  loading.value = true; error.value = ''
  try {
    const result = await listImages((page.value - 1) * 12, 12, activeSearch)
    if (request !== generation || !props.modelValue) return
    if (!Array.isArray(result?.images)) throw new Error('图片列表格式错误')
    images.value = result.images; total.value = result.total
  } catch (e) {if (request === generation && props.modelValue) error.value = e?.message || '图片加载失败'}
  finally {if (request === generation) loading.value = false}
}
const find = () => {if (uploading.value) return; activeSearch = search.value.trim(); page.value = 1; load()}
const choose = image => {if (disposed || !props.modelValue) return; emit('select', image); emit('update:modelValue', false)}
const upload = async event => {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file || uploading.value) return
  error.value = ''
  try {validateImageUpload(file, props.purpose)} catch (e) {error.value = e.message; return}
  uploading.value = true
  // The request may finish after close. Never re-upload an uncertain result automatically.
  const intent = generation, purpose = props.purpose
  try {
    const image = await uploadImage(file, purpose)
    if (!disposed && props.modelValue && generation === intent) choose(image)
  } catch (e) {if (!disposed && props.modelValue && generation === intent) error.value = e?.message || '上传失败，请先刷新核对图床，勿重复上传'}
  finally {if (!disposed) uploading.value = false}
}
watch(() => props.modelValue, open => {
  if (open) {page.value = 1; search.value = ''; activeSearch = ''; load()}
  else {generation++; loading.value = false}
}, {immediate: true})
onBeforeUnmount(() => {disposed = true; generation++})
</script>
<style scoped>
.picker-hint {font-size:12px;line-height:1.7;color:var(--admin-muted);margin:0 0 16px;}
.picker-toolbar {display:flex;gap:10px;margin-bottom:18px;}
.picker-grid {display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;max-height:50vh;overflow:auto;}
.picker-image {text-align:left;border:1px solid var(--admin-line);border-radius:10px;background:var(--admin-panel);padding:8px;color:var(--admin-text);min-width:0;}
.picker-image:hover {border-color:var(--admin-accent);background:var(--admin-soft-accent);}
.picker-image img {width:100%;height:120px;object-fit:contain;background:var(--admin-surface);border-radius:6px;}
.picker-image span {display:block;font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin:9px 0 5px;}
.picker-image small {font-size:11px;color:var(--admin-muted);}
.picker-empty {grid-column:1/-1;text-align:center;padding:36px 10px;color:var(--admin-muted);font-size:13px;}
.picker-error {font-size:13px;line-height:1.7;color:var(--admin-danger);}
@media(max-width:600px) {.picker-grid{grid-template-columns:repeat(2,minmax(0,1fr));}.picker-toolbar{flex-wrap:wrap;}.picker-toolbar .el-input{flex-basis:100%;}}
</style>
