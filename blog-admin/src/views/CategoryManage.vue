<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h2 class="page-title">分类管理</h2>
        <p class="page-sub">创建和编辑文章分类</p>
      </div>
    </header>

    <div class="inline-form">
      <el-input v-model="form.name" placeholder="分类名称" size="large" style="flex:1"/>
      <el-input v-model="form.slug" placeholder="slug" size="large" style="flex:1"/>
      <el-button type="primary" size="large" @click="submit" :disabled="!form.name">
        {{ editingId ? '更新' : '新增' }}
      </el-button>
      <el-button v-if="editingId" size="large" @click="cancel">取消</el-button>
    </div>

    <div class="card-grid">
      <div class="cat-card" v-for="c in categories" :key="c.id">
        <div class="cat-info">
          <span class="cat-id">#{{ c.id }}</span>
          <span class="cat-name">{{ c.name }}</span>
          <span class="cat-slug">{{ c.slug }}</span>
        </div>
        <div class="cat-actions">
          <el-button size="small" @click="edit(c)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(c.id)">删除</el-button>
        </div>
      </div>
    </div>
    <div v-if="!categories.length" class="empty">暂无分类</div>
  </section>
</template>

<script setup>
import {onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {deleteCategory, listCategories, saveCategory} from '../api'

const categories = ref([])
const editingId = ref(null)
const form = reactive({name: '', slug: ''})

const load = async () => {
  categories.value = await listCategories()
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
    await saveCategory({id: editingId.value, ...form})
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
    await deleteCategory(id)
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

.card-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cat-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  background: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
  transition: all .2s ease;
}

.cat-card:hover {
  transform: translateX(2px);
  box-shadow: var(--admin-shadow-lg);
}

.cat-info {
  display: flex;
  gap: 12px;
  align-items: center;
}

.cat-id {
  font-weight: 700;
  color: var(--admin-accent);
  font-size: 13px;
}

.cat-name {
  font-weight: 600;
  font-size: 15px;
  color: var(--admin-text);
}

.cat-slug {
  font-size: 13px;
  color: var(--admin-muted);
}

.cat-actions {
  display: flex;
  gap: 6px;
}

.empty {
  text-align: center;
  padding: 60px 0;
  color: var(--admin-muted);
  font-size: 15px;
}
</style>
