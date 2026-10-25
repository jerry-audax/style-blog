<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h2 class="page-title">标签管理</h2>
        <p class="page-sub">手动标记热门标签。首页按排序展示前三个热门；不足三个时，展示普通列表前三个。</p>
      </div>
    </header>

    <p v-if="tagsError" class="tag-error" role="alert">{{ tagsError }}</p>
    <el-button v-if="tagsError" @click="load" :disabled="loading">重新加载标签</el-button>
    <p v-if="loading" role="status">正在加载标签…</p>

    <div class="inline-form">
      <el-input v-model="form.name" placeholder="标签名称" size="large" style="flex:1" :disabled="busy"/>
      <el-input v-model="form.slug" placeholder="slug" size="large" style="flex:1" :disabled="busy"/>
      <label class="hot-toggle"><input v-model="form.isHot" type="checkbox" :true-value="1" :false-value="0" aria-label="热门标签" :disabled="busy">热门标签</label>
      <el-input-number v-model="form.sort" aria-label="标签排序" :min="0" :max="100000" :disabled="busy"/>
      <el-button type="primary" size="large" @click="submit" :disabled="!form.name.trim() || busy || !!tagsError" :loading="saving">
        {{ editingId ? '更新' : '新增' }}
      </el-button>
      <el-button v-if="editingId" size="large" @click="cancel" :disabled="saving">取消</el-button>
    </div>

    <div class="tag-grid">
      <div class="tag-card" v-for="t in tags" :key="t.id">
        <div class="tag-dot"></div>
        <div class="tag-info">
          <span class="tag-name">{{ t.name }}</span>
          <span class="tag-slug">{{ t.slug }}</span>
          <span v-if="t.isHot === 1" class="hot-badge" data-hot-tag>热门</span>
        </div>
        <div class="tag-actions">
          <el-button size="small" text @click="edit(t)" :disabled="busy">编辑</el-button>
          <el-button size="small" text type="danger" @click="remove(t.id)" :disabled="busy">删除</el-button>
        </div>
      </div>
    </div>
    <div v-if="!loading && !tagsError && !tags.length" class="empty">暂无标签</div>
  </section>
</template>

<script setup>
import {computed, onBeforeUnmount, onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {deleteTag, listTags, saveTag} from '../api'
const tagsError = ref('')
const tags = ref([])
const loading = ref(false), saving = ref(false), deleting = ref(false)
const busy = computed(() => loading.value || saving.value || deleting.value)
const editingId = ref(null)
const emptyForm = () => ({name:'',slug:'',sort:0,status:1,color:null,isHot:0})
const form = reactive(emptyForm())
let active = true, loadGeneration = 0

const load = async () => {
  const generation = ++loadGeneration
  loading.value = true
  try {
    const rows = await listTags()
    if (!active || generation !== loadGeneration) return
    if (!Array.isArray(rows)) throw new Error('标签数据无效')
    if (rows.some(row => row?.isHot !== 0 && row?.isHot !== 1)) {
      throw new Error('当前后端尚未支持热门标签，请先完成迁移并更新后端')
    }
    tags.value = rows; tagsError.value = ''
  } catch (cause) { if (active && generation === loadGeneration) tagsError.value = cause?.message || '无法加载标签' }
  finally { if (active && generation === loadGeneration) loading.value = false }
}

const edit = (row) => {
  if (busy.value) return
  Object.assign(form, {name:row.name,slug:row.slug,sort:row.sort ?? 0,status:row.status ?? 1,color:row.color ?? null,isHot:row.isHot === 1 ? 1 : 0})
  editingId.value = row.id
}

const cancel = () => {
  editingId.value = null;
  Object.assign(form, emptyForm())
}

const submit = async () => {
  if (busy.value || tagsError.value || !form.name.trim()) return
  if (!form.slug) form.slug = form.name.toLowerCase().replace(/\s+/g, '-')
  const wasEditing = editingId.value != null
  saving.value = true
  try {
    await saveTag({id: editingId.value, ...form})
    if (!active) return
    cancel()
    await load()
    if (active) ElMessage.success(wasEditing ? '已更新' : '已新增')
  } catch (e) {
    if (active) ElMessage.error(e.message)
  } finally { if (active) saving.value = false }
}

const remove = async (id) => {
  if (busy.value) return
  deleting.value = true
  try {
    await ElMessageBox.confirm('确定删除？', '确认', {type: 'warning'})
    if (!active) return
    await deleteTag(id)
    if (!active) return
    if (editingId.value === id) cancel()
    await load()
    if (active) ElMessage.success('已删除')
  } catch (e) {
    if (active && e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '失败')
  } finally { if (active) deleting.value = false }
}

onMounted(load)
onBeforeUnmount(() => {active = false; loadGeneration++})
</script>

<style scoped>
.page {
  max-width: 700px;
}

.page-header {
  margin-bottom: 20px;
}

.tag-error {color: var(--el-color-danger);}
.hot-toggle {display: flex; align-items: center; gap: 6px; color: var(--admin-text);}
.hot-badge {font-size: 12px; color: var(--admin-accent-3); margin-left: 8px;}

.page-title {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 700;
  color: var(--admin-text);
}

.page-sub {
  margin: 0;
  font-size: 14px;
  color: var(--admin-muted);
}

.inline-form {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 20px;
}

.tag-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.tag-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  background: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: 999px;
  box-shadow: var(--admin-shadow);
  transition: all .2s ease;
}

.tag-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--admin-shadow-lg);
}

.tag-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--admin-accent-3);
  flex-shrink: 0;
}

.tag-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--admin-text);
}

.tag-slug {
  font-size: 12px;
  color: var(--admin-muted);
}

.tag-actions {
  display: flex;
  gap: 2px;
  margin-left: auto;
}

.empty {
  text-align: center;
  padding: 60px 0;
  color: var(--admin-muted);
  font-size: 15px;
}
</style>
