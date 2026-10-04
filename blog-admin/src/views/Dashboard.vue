<template>
  <section class="page dashboard-page">
    <header class="page-header"><div><p class="eyebrow">内容工作台</p><h2 class="page-title">让你的下一篇文章，从这里开始</h2><p class="page-sub">管理博客内容与资源，保持公开站点的阅读体验。</p></div><router-link to="/article/new"><el-button type="primary" size="large" :icon="EditPen">写文章</el-button></router-link></header>
    <div v-if="loadError" class="dashboard-error" role="alert"><p>无法加载博客概览，请检查后端连接后重试。</p><el-button @click="loadDashboard">重新加载</el-button></div>
    <template v-else>
      <div class="stat-grid" :aria-busy="loading">
        <router-link to="/articles" class="stat-card"><div><span class="stat-label">已发布文章</span><strong class="stat-value">{{ loading ? '—' : stats.publishedArticles }}</strong><span class="stat-note">后台发布状态，非部署状态</span></div><el-icon><DocumentChecked/></el-icon></router-link>
        <router-link to="/articles" class="stat-card"><div><span class="stat-label">草稿文章</span><strong class="stat-value">{{ loading ? '—' : draftCount }}</strong><span class="stat-note">继续整理未发布的想法</span></div><el-icon><EditPen/></el-icon></router-link>
        <router-link to="/comments" class="stat-card"><div><span class="stat-label">历史评论</span><strong class="stat-value">{{ loading ? '—' : stats.totalComments }}</strong><span class="stat-note">公开端仅保留只读展示</span></div><el-icon><ChatLineSquare/></el-icon></router-link>
      </div>
      <div class="dashboard-columns">
        <section class="recent-section">
          <div class="section-header"><h3 class="section-title">最近文章</h3><router-link to="/articles" class="text-action">全部文章 <el-icon><ArrowRight/></el-icon></router-link></div>
          <el-skeleton v-if="loading" :rows="5" animated/>
          <div v-else-if="stats.recentArticles.length" class="recent-list">
            <router-link v-for="article in stats.recentArticles" :key="article.id" :to="`/article/${article.id}/edit`" class="recent-item">
              <img v-if="article.coverUrl" :src="article.coverUrl" alt="" class="recent-cover"/><div v-else class="recent-cover cover-placeholder"><el-icon><Document/></el-icon></div>
              <div class="recent-copy"><strong class="recent-title">{{ article.title }}</strong><span class="recent-meta">{{ article.categoryName || '未分类' }} · {{ article.viewCount || 0 }} 阅读</span></div>
              <span class="recent-status" :class="article.status === 1 ? 'pub' : 'draft'">{{ article.status === 1 ? '已发布' : '草稿' }}</span>
            </router-link>
          </div>
          <div v-else class="recent-empty"><p>还没有文章，先记录一个想法。</p><router-link to="/article/new" class="text-action">开始写作</router-link></div>
        </section>
        <aside class="dashboard-shortcuts">
          <h3 class="section-title">管理快捷入口</h3>
          <router-link to="/images" class="shortcut"><el-icon><Picture/></el-icon><div><strong>图片库</strong><span>正文、封面与头像 · Telegram 图床</span></div><el-icon><ArrowRight/></el-icon></router-link>
          <router-link to="/music" class="shortcut"><el-icon><Headset/></el-icon><div><strong>音乐管理</strong><span>维护歌单资料与授权状态</span></div><el-icon><ArrowRight/></el-icon></router-link>
          <router-link to="/account" class="shortcut"><el-icon><User/></el-icon><div><strong>博主设置</strong><span>个人资料、头像与密码</span></div><el-icon><ArrowRight/></el-icon></router-link>
          <div class="site-boundary"><strong>一个博客，一个博主</strong><p>内容编辑留在后台，公开站点专注阅读。AI 模块保留，当前不启用。</p></div>
        </aside>
      </div>
    </template>
  </section>
</template>
<script setup>
import {computed, onBeforeUnmount, onMounted, reactive, ref} from 'vue'
import {EditPen, DocumentChecked, ChatLineSquare, ArrowRight, Document, Picture, Headset, User} from '@element-plus/icons-vue'
import {getDashboard} from '../api'
const stats = reactive({totalArticles: 0, publishedArticles: 0, totalComments: 0, recentArticles: []})
const loading = ref(false), loadError = ref(false)
const draftCount = computed(() => Math.max(0, stats.totalArticles - stats.publishedArticles))
let generation = 0
const loadDashboard = async () => {
  const request = ++generation
  loading.value = true; loadError.value = false
  try {
    const data = await getDashboard()
    if (request !== generation) return
    if (!Array.isArray(data?.recentArticles)) throw new Error('概览数据格式错误')
    Object.assign(stats, data)
  } catch {
    if (request === generation) loadError.value = true
  } finally {
    if (request === generation) loading.value = false
  }
}
onMounted(loadDashboard)
onBeforeUnmount(() => generation++)
</script>
<style scoped>
.page-header {display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:26px;}
.eyebrow {margin:0 0 10px;color:var(--admin-accent);font-size:12px;font-weight:600;}
.page-title {margin:0 0 8px;font-size:27px;}
.page-sub {margin:0;font-size:13px;color:var(--admin-muted);}
.stat-grid {display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin-bottom:26px;}
.stat-card {padding:22px 24px;background:var(--admin-panel);border:1px solid var(--admin-line);border-radius:var(--admin-radius);display:flex;justify-content:space-between;gap:14px;}
.stat-label {display:block;font-size:13px;color:var(--admin-muted);}
.stat-value {display:block;font-size:34px;font-weight:650;margin:12px 0 6px;font-variant-numeric:tabular-nums;}
.stat-note {font-size:11px;color:var(--admin-muted);}
.stat-card > .el-icon {font-size:22px;color:var(--admin-accent);padding:10px;box-sizing:content-box;background:var(--admin-soft-accent);border-radius:12px;align-self:flex-start;}
.dashboard-columns {display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:24px;}
.recent-section,.dashboard-shortcuts {background:var(--admin-panel);border:1px solid var(--admin-line);border-radius:var(--admin-radius);padding:22px;}
.section-header {display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:14px;}
.section-title {font-size:16px;margin:0;font-weight:650;}
.section-header .text-action {display:flex;align-items:center;gap:5px;}
.recent-item {display:flex;align-items:center;gap:14px;padding:15px 0;border-bottom:1px solid var(--admin-line);}
.recent-item:last-child {border-bottom:0;}
.recent-item:hover .recent-title {color:var(--admin-accent);}
.recent-cover {width:66px;height:46px;object-fit:cover;border-radius:8px;flex-shrink:0;}
.cover-placeholder {display:grid;place-items:center;background:var(--admin-surface);color:var(--admin-muted);}
.recent-copy {flex:1;min-width:0;}
.recent-title {font-size:14px;display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.recent-meta {display:block;font-size:11px;color:var(--admin-muted);margin-top:7px;}
.recent-status {font-size:11px;padding:4px 9px;border-radius:6px;white-space:nowrap;}
.pub {background:var(--admin-soft-success);color:var(--admin-success);}
.draft {background:var(--admin-soft-warn);color:var(--admin-warning);}
.recent-empty {text-align:center;padding:40px 8px;color:var(--admin-muted);font-size:13px;}
.shortcut {display:flex;align-items:center;gap:12px;padding:18px 0;border-bottom:1px solid var(--admin-line);}
.shortcut > .el-icon:first-child {color:var(--admin-accent);font-size:21px;}
.shortcut > div {flex:1;}
.shortcut strong {font-size:13px;display:block;}
.shortcut span {font-size:11px;color:var(--admin-muted);display:block;margin-top:5px;}
.shortcut > .el-icon:last-child {color:var(--admin-muted);}
.site-boundary {padding:16px;background:var(--admin-surface);border-radius:10px;margin-top:20px;font-size:12px;}
.site-boundary p {margin:6px 0 0;line-height:1.7;color:var(--admin-muted);}
.dashboard-error {border:1px solid var(--admin-line);background:var(--admin-panel);border-radius:14px;padding:40px;text-align:center;color:var(--admin-danger);font-size:14px;}
@media(max-width:1100px) {.dashboard-columns{grid-template-columns:1fr;} .dashboard-shortcuts{max-width:none;}}
@media(max-width:680px) {.page-header{align-items:flex-start;flex-direction:column;} .page-title{font-size:23px;} .stat-grid{grid-template-columns:1fr;} .stat-value{margin:6px 0;} .recent-cover{width:48px;height:40px;}}
</style>
