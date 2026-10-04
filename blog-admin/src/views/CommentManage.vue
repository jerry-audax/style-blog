<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h2 class="page-title">评论审核</h2>
        <p class="page-sub">审核和管理所有用户评论</p>
      </div>
      <el-select v-model="statusFilter" placeholder="全部状态" @change="load" style="width:130px">
        <el-option label="全部" value=""/>
        <el-option label="已显示" :value="1"/>
        <el-option label="已隐藏" :value="0"/>
      </el-select>
    </header>

    <div v-if="loadError" class="error-banner">
      <span class="error-text">加载失败</span>
      <el-button size="small" type="primary" @click="load">重试</el-button>
    </div>

    <div v-else-if="loading && !comments.length" class="empty">加载中...</div>

    <div v-else-if="comments.length" class="card-list">
      <div class="comment-card" v-for="c in comments" :key="c.id">
        <div class="card-top">
          <div class="card-meta">
            <span class="card-id">#{{ c.id }}</span>
            <span class="card-article">文章 {{ c.articleId }}</span>
            <span class="card-status" :class="c.status === 1 ? 'show' : 'hide'">
              {{ c.status === 1 ? '显示' : '隐藏' }}
            </span>
          </div>
          <div class="card-actions">
            <el-button size="small" type="primary" text @click="audit(c.id, 1)">通过</el-button>
            <el-button size="small" text @click="audit(c.id, 0)">隐藏</el-button>
            <el-button size="small" text type="danger" @click="remove(c.id)">删除</el-button>
          </div>
        </div>
        <p class="card-content">{{ c.content }}</p>
      </div>
    </div>
    <div v-else-if="!loadError" class="empty">暂无评论</div>

    <el-pagination v-if="pages > 1" v-model:current-page="pageNum" :page-size="10"
                   :total="pages * 10" layout="prev, pager, next" @current-change="load"/>
  </section>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {auditComment, listAdminComments} from '../api'
import {deleteComment} from '../api'

const comments = ref([])
const pageNum = ref(1)
const pages = ref(1)
const statusFilter = ref('')
const loading = ref(false)
const loadError = ref(false)

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const page = await listAdminComments(pageNum.value, 10, statusFilter.value === '' ? undefined : Number(statusFilter.value))
    comments.value = page.records
    pages.value = page.pages || 1
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const audit = async (id, status) => {
  try {
    await auditComment(id, status)
    await load()
    ElMessage.success(status === 1 ? '评论已通过' : '评论已隐藏')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const remove = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除此评论？', '确认', {type: 'warning'})
    await deleteComment(id)
    await load()
    ElMessage.success('评论已删除')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.page {
  max-width: 900px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  gap: 16px;
  flex-wrap: wrap;
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

.card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.comment-card {
  padding: 18px 20px;
  background: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
  transition: all .2s ease;
}

.comment-card:hover {
  transform: translateY(-1px);
  box-shadow: var(--admin-shadow-lg);
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.card-meta {
  display: flex;
  gap: 12px;
  align-items: center;
}

.card-id {
  font-weight: 700;
  color: var(--admin-accent);
  font-size: 13px;
}

.card-article {
  font-size: 12px;
  color: var(--admin-muted);
}

.card-status {
  padding: 1px 8px;
  font-size: 11px;
  border-radius: 999px;
  font-weight: 600;
}

.card-status.show {
  background: var(--admin-soft-success);
  color: var(--admin-success);
}

.card-status.hide {
  background: var(--admin-soft-warn);
  color: #c9882c;
}

.card-actions {
  display: flex;
  gap: 4px;
}

.card-content {
  margin: 0;
  font-size: 15px;
  line-height: 1.7;
  color: var(--admin-text);
}

.empty {
  text-align: center;
  padding: 60px 0;
  color: var(--admin-muted);
  font-size: 15px;
}

.error-banner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 14px 20px;
  margin-bottom: 16px;
  background: var(--admin-soft-danger, #fef0f0);
  border: 1px solid var(--admin-danger, #f56c6c);
  border-radius: var(--admin-radius, 8px);
}

.error-text {
  font-size: 14px;
  color: var(--admin-danger, #f56c6c);
}
</style>
