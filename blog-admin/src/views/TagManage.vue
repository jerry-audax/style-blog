<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h2 class="page-title">标签管理</h2>
        <p class="page-sub">创建和编辑文章标签</p>
      </div>
    </header>

    <div class="inline-form">
      <el-input v-model="form.name" placeholder="标签名称" size="large" style="flex:1"/>
      <el-input v-model="form.slug" placeholder="slug" size="large" style="flex:1"/>
      <el-button type="primary" size="large" @click="submit" :disabled="!form.name">
        {{ editingId ? '更新' : '新增' }}
      </el-button>
      <el-button v-if="editingId" size="large" @click="cancel">取消</el-button>
    </div>

    <div class="tag-grid">
      <div class="tag-card" v-for="t in tags" :key="t.id">
        <div class="tag-dot"></div>
        <div class="tag-info">
          <span class="tag-name">{{ t.name }}</span>
          <span class="tag-slug">{{ t.slug }}</span>
        </div>
        <div class="tag-actions">
          <el-button size="small" text @click="edit(t)">编辑</el-button>
          <el-button size="small" text type="danger" @click="remove(t.id)">删除</el-button>
        </div>
      </div>
    </div>
    <div v-if="!tags.length" class="empty">暂无标签</div>
  </section>
</template>

<script setup>
import {onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {deleteTag, listTags, saveTag} from '../api'

const tags = ref([])
const editingId = ref(null)
const form = reactive({name: '', slug: ''})

const load = async () => {
  tags.value = await listTags()
}

const edit = (row) => {
  Object.assign(form, {name: row.name, slug: row.slug})
  editingId.value = row.id
}

const cancel = () => {
  editingId.value = null;
  Object.assign(form, {name: '', slug: ''})
}

const submit = async () => {
  if (!form.slug) form.slug = form.name.toLowerCase().replace(/\s+/g, '-')
  try {
    await saveTag({id: editingId.value, ...form})
    cancel()
    await load()
    ElMessage.success(editingId.value ? '已更新' : '已新增')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const remove = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除？', '确认', {type: 'warning'})
    await deleteTag(id)
    await load()
    ElMessage.success('已删除')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '失败')
  }
}

onMounted(load)
</script>

<style scoped>
.page {
  max-width: 700px;
}

.page-header {
  margin-bottom: 20px;
}

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
