<template>
  <section class="page">
    <header class="page-header">
      <h2 class="page-title">图片库</h2>
      <p class="page-sub">Cloudflare ImgBed · Telegram 保存文件，PostgreSQL 保存登记元数据。</p>
      <p v-if="imageUpload.imageCapabilities.value?.storage === 'postgres'" class="page-sub">仅显示已登记图片；图床历史未登记文件未列出，不代表文件不存在。图片删除待受控引用保护接通，当前不会删除图床文件。</p>
    </header>
    <form class="filters" @submit.prevent="find">
      <el-input v-model="search" aria-label="搜索图片" placeholder="搜索图片名称" maxlength="100" clearable/>
      <el-button native-type="submit" :disabled="busy">搜索</el-button>
      <el-button @click="load" :disabled="busy">刷新</el-button>
      <el-select v-model="uploadPurpose" aria-label="上传图片用途" :disabled="busy" style="width:130px"><el-option label="正文图片" value="article"/><el-option label="文章封面" value="cover"/><el-option label="博主头像" value="avatar"/></el-select>
      <el-button type="primary" :disabled="busy || !imageUpload.enabled.value" @click="fileInput.click()">上传图片</el-button>
      <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/gif,image/webp" hidden @change="upload"/>
    </form>
    <p class="page-sub">JPEG / PNG / GIF / WebP · 头像最大 2 MiB，正文与封面最大 10 MiB。公开链接，请勿上传私人资料。</p>
    <p v-if="displayError" class="error" role="alert">{{ displayError }} — 可刷新列表核对结果。</p>
    <MediaUploadPanel :uploader="imageUpload.uploader"/>
    <p v-if="busy" role="status">处理中…</p>
    <div class="image-grid" :aria-busy="busy">
      <article class="image-card" v-for="image in images" :key="imageKey(image)">
        <el-image class="image-preview" :src="image.url" :preview-src-list="[image.url]" fit="contain" lazy
                  :alt="imageName(image)">
          <template #error><span class="page-sub">图片不可用，请核对图床访问设置</span></template>
        </el-image>
        <div class="image-info">
          <span class="image-kind">{{ purpose(image) }}{{ image.status ? ' · ' + image.status : '' }}</span>
          <p class="image-path" :title="imageName(image)">{{ imageName(image) }}</p>
          <p class="page-sub">{{ imageKey(image) }}</p>
          <p class="page-sub">{{ imageMime(image) }} · {{ formatSize(imageFileSize(image)) }}</p>
          <div class="image-actions">
            <el-button size="small" @click="copy(image.url)">复制链接</el-button>
            <el-button size="small" type="danger" :disabled="busy || !imageUpload.imageCapabilities.value?.deleteEnabled" :title="imageUpload.imageCapabilities.value?.storage === 'postgres' ? '待受控引用保护接通，当前保留文件' : ''" @click="remove(image)">删除图片</el-button>
          </div>
        </div>
      </article>
    </div>
    <p v-if="!busy && !displayError && !images.length" class="empty">暂无已登记图片。可以上传新图片，或调整搜索条件。</p>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total"
                   :disabled="busy" @current-change="load"/>
  </section>
</template>

<script setup>
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {listImages, deleteImage} from '../api'
import {useAdminAuthStore} from '../stores/auth'
import {useImageUpload} from '../composables/useImageUpload'
import MediaUploadPanel from '../components/MediaUploadPanel.vue'
import {imageKey,imageName,imageFileSize,imageMime,imagePurposeLabel as purpose, formatImageSize as formatSize} from '../utils/images'

const auth=useAdminAuthStore()
const images = ref([]), total = ref(0), page = ref(1), search = ref(''), loading = ref(false), deleting=ref(false), error = ref(''),
    fileInput = ref(null)
const uploadPurpose = ref('article')
let activeSearch = '', generation=0, deletionGeneration=0, disposed=false
const imageUpload=useImageUpload({auth,purpose:()=>uploadPurpose.value,onSucceeded:async()=>{
  page.value=1;activeSearch='';search.value='';await load(true)
}})
const busy=computed(()=>loading.value || deleting.value || imageUpload.busy.value)
const displayError=computed(()=>error.value || imageUpload.error.value)
const confirmedDeleted = new Set()
const fetchPage = async (current) => {
  const capabilities=await imageUpload.loadCapabilities()
  if(!current())return
  if(!capabilities)throw new Error(imageUpload.error.value || '图片能力未知，请核对前后端版本')
  const data = await listImages((page.value - 1) * 20, 20, activeSearch,capabilities)
  if(!current())return
  images.value = data.images.filter((image) => !confirmedDeleted.has(imageKey(image)))
  total.value = data.total
}
const load = async (force=false) => {
  if (loading.value && force!==true || disposed) return
  const epoch=++generation,who=auth.verifiedUserId,token=auth.token
  const current=()=>!disposed && epoch===generation && who===auth.verifiedUserId && token===auth.token
  loading.value = true
  error.value = ''
  try {
    await fetchPage(current)
  } catch (e) {
    if(current())error.value = e.message
  } finally {
    if(current())loading.value = false
  }
}
const find = () => {
  if (!busy.value) {
    activeSearch = search.value.trim();
    page.value = 1;
    load()
  }
}
const upload = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || busy.value) return
  error.value = ''
  const image=await imageUpload.start(file)
  if(image && !disposed)ElMessage.success('图片已登记，文件不会自动绑定到文章或头像')
}
const copy = async (url) => {
  try {
    await navigator.clipboard.writeText(url);
    ElMessage.success('链接已复制')
  } catch {
    ElMessageBox.alert(url, '图片链接', {confirmButtonText: '关闭'})
  }
}
const remove = async (image) => {
  const capabilities=imageUpload.imageCapabilities.value
  if (busy.value || capabilities?.storage!=='legacy-json' || !capabilities.deleteEnabled) return
  const who=auth.verifiedUserId,token=auth.token,epoch=++deletionGeneration
  const current=()=>!disposed && who===auth.verifiedUserId && token===auth.token && epoch===deletionGeneration
  deleting.value = true
  try {
    await ElMessageBox.confirm(`删除 ${image.path}？此操作不可恢复，引用它的历史文章、封面或头像将无法显示。请先确认不再使用。`, '删除图片', {
      confirmButtonText: '确认永久删除', cancelButtonText: '保留图片', type: 'warning', autofocus: false,
    })
    if(!current())return
    error.value = ''
    await deleteImage(image.path)
    if(!current())return
    confirmedDeleted.add(image.path)
    ElMessage.success('删除请求已确认；图床列表和公开缓存可能延迟更新，图片无法恢复')
    if (images.value.length === 1 && page.value > 1) page.value--
    await load(true)
  } catch (e) {
    if (current() && e !== 'cancel' && e !== 'close') error.value = e.message
  } finally {
    if(current())deleting.value = false
  }
}
onMounted(load)
watch(()=>imageUpload.uploader.operations.value.map(op=>op.operationId+':'+op.status).join('|'),()=>{
  if(imageUpload.uploader.operations.value.some(op=>op.status==='SUCCEEDED'))void load()
})
watch(()=>[auth.verifiedUserId,auth.token],()=>{generation++;deletionGeneration++;loading.value=false;deleting.value=false;images.value=[];error.value='';confirmedDeleted.clear();if(auth.verifiedUserId)void load(true)},{flush:'sync'})
onBeforeUnmount(()=>{disposed=true;generation++})
</script>

<style scoped>
.page {
  max-width: 1200px;
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  margin: 0 0 6px;
  font-size: 24px;
  color: var(--admin-text);
}

.page-sub {
  color: var(--admin-muted);
  font-size: 13px;
  margin: 8px 0;
}

.filters {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.filters .el-input {
  flex: 1;
  min-width: 180px;
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  margin-top: 24px;
}

.image-card {
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  overflow: hidden;
  background: var(--admin-panel);
  box-shadow: var(--admin-shadow);
}

.image-preview {
  width: 100%;
  height: 190px;
  background: var(--admin-surface);
}

.image-info {
  padding: 16px;
}

.image-kind {
  font-size: 12px;
  color: var(--admin-accent);
}

.image-path {
  margin: 8px 0;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.image-actions {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

.error {
  color: var(--admin-danger);
}

.empty {
  padding: 48px 12px;
  text-align: center;
  color: var(--admin-muted);
}

@media (max-width: 500px) {
  .image-grid {
    grid-template-columns: 1fr;
  }
}
</style>
