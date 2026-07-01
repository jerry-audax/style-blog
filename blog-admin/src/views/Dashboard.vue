<template>
  <section class="page" v-loading="loading">
    <header class="page-header">
      <h2 class="page-title">仪表盘</h2>
      <p class="page-sub">系统运行概览</p>
    </header>

    <!-- Error state -->
    <div v-if="loadError" class="error-banner">
      <p class="error-text">加载失败，请刷新重试</p>
      <el-button type="primary" size="default" @click="loadDashboard">重试</el-button>
    </div>

    <!-- Normal content -->
    <template v-else>
      <div class="stat-grid">
        <div class="stat-card">
          <span class="stat-icon">&#9998;</span>
          <div>
            <p class="stat-value">{{ stats.publishedArticles }}<span class="stat-hint"> / {{ stats.totalArticles }}</span></p>
            <p class="stat-label">已发布 / 全部</p>
          </div>
        </div>
        <div class="stat-card">
          <span class="stat-icon">&#9993;</span>
          <div>
            <p class="stat-value">{{ stats.totalComments }}<span class="stat-hint"> / 待审 {{ stats.pendingComments }}</span></p>
            <p class="stat-label">全部 / 待审</p>
          </div>
        </div>
        <div class="stat-card">
          <span class="stat-icon">&#9787;</span>
          <div>
            <p class="stat-value">{{ stats.totalUsers }}</p>
            <p class="stat-label">注册用户</p>
          </div>
        </div>
      </div>

      <div class="recent-section">
        <h3 class="section-title">最近文章</h3>
        <div class="recent-list" v-if="stats.recentArticles?.length">
          <div class="recent-item" v-for="a in stats.recentArticles" :key="a.id">
            <span class="recent-status" :class="a.status === 1 ? 'pub' : 'draft'">{{ a.status === 1 ? '已发布' : '草稿' }}</span>
            <span class="recent-title">{{ a.title }}</span>
            <span class="recent-meta">{{ a.viewCount || 0 }} 阅读 · {{ a.likeCount || 0 }} 赞</span>
          </div>
        </div>
        <div v-else class="recent-empty">暂无文章</div>
      </div>
    </template>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const stats = reactive({ totalArticles: 0, publishedArticles: 0, totalComments: 0, pendingComments: 0, totalUsers: 0, recentArticles: [] })
const loading = ref(false)
const loadError = ref(false)

const loadDashboard = async () => {
  loading.value = true
  loadError.value = false
  try {
    const data = await http.get('/api/admin/dashboard')
    Object.assign(stats, data)
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)
</script>

<style scoped>
.page { max-width: 800px; }
.page-header { margin-bottom: 28px; }
.page-title { margin: 0 0 4px; font-size: 24px; font-weight: 700; color: var(--admin-text); }
.page-sub { margin: 0; font-size: 14px; color: var(--admin-muted); }

.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 36px; }
.stat-card {
  display: flex; align-items: center; gap: 16px;
  padding: 24px; background: var(--admin-panel);
  border: 1px solid var(--admin-line); border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
}
.stat-icon { font-size: 28px; flex-shrink: 0; }
.stat-value { font-size: 24px; font-weight: 700; color: var(--admin-text); margin: 0; }
.stat-hint { font-size: 14px; color: var(--admin-muted); font-weight: 400; }
.stat-label { margin: 4px 0 0; font-size: 13px; color: var(--admin-muted); }

.recent-section { background: var(--admin-panel); border: 1px solid var(--admin-line); border-radius: var(--admin-radius); padding: 20px 24px; box-shadow: var(--admin-shadow); }
.section-title { margin: 0 0 16px; font-size: 16px; color: var(--admin-text); font-weight: 700; }

.recent-list { display: flex; flex-direction: column; gap: 0; }
.recent-item { display: flex; align-items: center; gap: 14px; padding: 12px 0; border-bottom: 1px solid var(--admin-line); }
.recent-item:last-child { border-bottom: none; }
.recent-status { padding: 2px 10px; font-size: 11px; border-radius: 999px; font-weight: 600; flex-shrink: 0; }
.recent-status.pub { background: var(--admin-soft-success); color: var(--admin-success); }
.recent-status.draft { background: var(--admin-soft-warn); color: #c9882c; }
.recent-title { flex: 1; font-size: 14px; color: var(--admin-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.recent-meta { font-size: 12px; color: var(--admin-muted); white-space: nowrap; }
.recent-empty { text-align: center; padding: 32px 0; color: var(--admin-muted); font-size: 14px; }

.error-banner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 48px 24px;
  background: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
}
.error-text { margin: 0; font-size: 15px; color: var(--admin-danger); }
</style>
