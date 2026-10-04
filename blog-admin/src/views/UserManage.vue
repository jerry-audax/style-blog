<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-sub">管理注册用户和权限状态</p>
      </div>
      <el-select v-model="statusFilter" placeholder="全部状态" @change="load" style="width:130px">
        <el-option label="全部" value=""/>
        <el-option label="正常" :value="1"/>
        <el-option label="已禁用" :value="0"/>
      </el-select>
    </header>

    <div v-if="loadError" class="error-banner">
      <span class="error-text">加载失败</span>
      <el-button size="small" type="primary" @click="load">重试</el-button>
    </div>

    <div v-else-if="loading && !users.length" class="empty">加载中...</div>

    <div v-else-if="users.length" class="card-grid">
      <div class="user-card" v-for="u in users" :key="u.id">
        <div class="user-avatar">
          <img v-if="u.avatar" :src="u.avatar" alt="avatar"/>
          <span v-else class="avatar-text">{{ (u.nickname || u.username || 'U')[0] }}</span>
        </div>
        <div class="user-info">
          <div class="user-name">{{ u.nickname || u.username }}</div>
          <div class="user-meta">
            <span>{{ u.phone }}</span>
            <span v-if="u.email">{{ u.email }}</span>
            <span class="user-status" :class="u.status === 1 ? 'ok' : 'ban'">
              {{ u.status === 1 ? '正常' : '禁用' }}
            </span>
          </div>
        </div>
        <div class="user-actions">
          <el-button size="small" @click="toggle(u)">{{ u.status === 1 ? '禁用' : '启用' }}</el-button>
          <el-button size="small" type="danger" @click="remove(u.id)">删除</el-button>
        </div>
      </div>
    </div>
    <div v-else-if="!loadError" class="empty">暂无用户</div>

    <el-pagination v-if="pages > 1" v-model:current-page="pageNum" :page-size="10"
                   :total="pages * 10" layout="prev, pager, next" @current-change="load"/>
  </section>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {deleteUser, listUsers, updateUserStatus} from '../api'

const users = ref([])
const pageNum = ref(1)
const pages = ref(1)
const statusFilter = ref('')
const loading = ref(false)
const loadError = ref(false)

const load = async () => {
  loading.value = true
  loadError.value = false
  try {
    const page = await listUsers(pageNum.value, 10, statusFilter.value === '' ? undefined : Number(statusFilter.value))
    users.value = page.records
    pages.value = page.pages || 1
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const toggle = async (row) => {
  const s = row.status === 1 ? 0 : 1
  try {
    await updateUserStatus(row.id, s)
    await load()
    ElMessage.success(s === 1 ? '已启用' : '已禁用')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const remove = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除？', '确认', {type: 'warning'})
    await deleteUser(id)
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
  max-width: 800px;
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

.card-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  background: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
  transition: all .2s ease;
}

.user-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--admin-shadow-lg);
}

.user-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-text {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, var(--admin-accent), var(--admin-accent-3));
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-info {
  flex: 1;
  min-width: 0;
}

.user-name {
  font-weight: 600;
  font-size: 15px;
  color: var(--admin-text);
  margin-bottom: 4px;
}

.user-meta {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--admin-muted);
}

.user-status {
  padding: 1px 8px;
  font-size: 11px;
  border-radius: 999px;
  font-weight: 600;
}

.user-status.ok {
  background: var(--admin-soft-success);
  color: var(--admin-success);
}

.user-status.ban {
  background: var(--admin-soft-danger);
  color: var(--admin-danger);
}

.user-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
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
