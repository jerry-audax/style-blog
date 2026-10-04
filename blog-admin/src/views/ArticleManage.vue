<template>
  <section class="page">
    <header class="page-header"><div><h2 class="page-title">文章</h2><p class="page-sub">管理正文与首页卡片，让内容在安知鱼博客里完整呈现。</p></div><el-button type="primary" size="large" :icon="EditPen" @click="$router.push('/article/new')">写文章</el-button></header>
    <div class="content-panel">
      <div class="article-filters"><el-select v-model="statusFilter" aria-label="文章状态" @change="changeFilter" style="width:150px"><el-option label="全部状态" value=""/><el-option label="已发布" :value="1"/><el-option label="草稿" :value="0"/></el-select><el-input v-model="keyword" placeholder="筛选本页标题" aria-label="筛选本页标题" clearable style="max-width:250px"/><span class="result-count">共 {{ total }} 篇</span><el-button :loading="loading" @click="load">刷新</el-button></div>
      <div v-if="loadError" class="error-banner" role="alert"><span>文章加载失败，请检查后端连接。</span><el-button @click="load">重试</el-button></div>
      <el-table v-else :data="visibleArticles" v-loading="loading" size="large" row-key="id" style="width:100%">
        <el-table-column label="文章" min-width="290"><template #default="{row}"><div class="cell-title"><img v-if="row.coverUrl" :src="row.coverUrl" alt="" class="cell-cover"/><div v-else class="cell-cover cover-placeholder"><el-icon><Document/></el-icon></div><div class="article-copy"><button type="button" class="article-title" @click="$router.push(`/article/${row.id}/edit`)">{{ row.title }}</button><span class="article-meta">#{{ row.id }} · {{ row.categoryName || '未分类' }}<span v-if="row.isTop === 1"> · 置顶</span></span><p v-if="row.summary" class="article-summary">{{ row.summary }}</p></div></div></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{row}"><span class="cell-status" :class="row.status === 1 ? 'pub' : 'draft'">{{ row.status === 1 ? '已发布' : '草稿' }}</span></template></el-table-column>
        <el-table-column label="标签" min-width="140"><template #default="{row}"><div class="cell-tags" v-if="row.tagNames?.length"><span v-for="tag in row.tagNames" :key="tag" class="cell-tag">{{ tag }}</span></div><span v-else class="cell-none">—</span></template></el-table-column>
        <el-table-column prop="viewCount" label="阅读" width="80"/>
        <el-table-column label="操作" width="185" fixed="right"><template #default="{row}"><el-button size="small" text @click="$router.push(`/article/${row.id}/edit`)">编辑</el-button><a v-if="row.status === 1 && articlePublicUrl(publicBlogUrl, row.id)" :href="articlePublicUrl(publicBlogUrl, row.id)" target="_blank" rel="noopener noreferrer" class="public-article-link" title="查看已部署的静态页面，可能尚未更新">博客</a><el-button size="small" text type="danger" :disabled="deleting === row.id" @click="remove(row.id)">删除</el-button></template></el-table-column>
        <template #empty><div class="table-empty">{{ keyword ? '本页没有匹配的标题，清空筛选或翻页查看。' : '暂无此状态的文章，可以开始写作。' }}</div></template>
      </el-table>
      <el-pagination v-if="total > pageSize" v-model:current-page="pageNum" :page-size="pageSize" :total="total" :disabled="loading" layout="total, prev, pager, next" @current-change="load"/>
    </div>
  </section>
</template>
<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {EditPen, Document} from '@element-plus/icons-vue'
import {deleteArticle, listArticles} from '../api'
import {publicBlogUrl, articlePublicUrl} from '../config/blog'
const articles = ref([]), pageNum = ref(1), total = ref(0), statusFilter = ref(''), keyword = ref(''), loading = ref(false), loadError = ref(false), deleting = ref(null)
const pageSize = 10
let generation = 0
const visibleArticles = computed(() => articles.value.filter(row => !keyword.value.trim() || row.title?.toLocaleLowerCase().includes(keyword.value.trim().toLocaleLowerCase())))
const load = async () => {
  const request = ++generation
  loading.value = true; loadError.value = false
  try {
    const data = await listArticles(pageNum.value, pageSize, statusFilter.value === '' ? undefined : Number(statusFilter.value))
    if (request !== generation) return
    if (!Array.isArray(data?.records)) throw new Error('文章列表格式错误')
    articles.value = data.records; total.value = Number(data.total) || 0
  } catch { if (request === generation) loadError.value = true }
  finally { if (request === generation) loading.value = false }
}
const changeFilter = () => {pageNum.value = 1; keyword.value = ''; load()}
const remove = async id => {
  if (deleting.value !== null) return
  deleting.value = id
  try {
    await ElMessageBox.confirm('删除后将自动更新公开博客。生成完成前仍可能看到旧页面，请关注发布状态。确定删除？', '删除文章', {type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留'})
    await deleteArticle(id)
    if (articles.value.length === 1 && pageNum.value > 1) pageNum.value--
    await load()
    ElMessage.success('后台文章已删除，公开博客将自动更新')
  } catch (e) {if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '删除失败')}
  finally {deleting.value = null}
}
onMounted(load)
onBeforeUnmount(() => generation++)
</script>
<style scoped>
.page-header {display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:24px;flex-wrap:wrap;}
.page-title {margin:0 0 7px;}
.page-sub {margin:0;font-size:13px;color:var(--admin-muted);}
.content-panel {padding:18px 22px 24px;border:1px solid var(--admin-line);border-radius:var(--admin-radius);background:var(--admin-panel);}
.article-filters {display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:20px;}
.result-count {margin-left:auto;font-size:12px;color:var(--admin-muted);}
.cell-title {display:flex;align-items:center;gap:14px;padding:6px 0;}
.cell-cover {width:78px;height:56px;object-fit:cover;border-radius:8px;flex-shrink:0;}
.cover-placeholder {display:grid;place-items:center;background:var(--admin-surface);color:var(--admin-muted);}
.article-copy {min-width:0;}
.article-title {display:block;text-align:left;border:0;padding:0;background:transparent;color:var(--admin-text);font-size:14px;font-weight:600;line-height:1.6;}
.article-title:hover {color:var(--admin-accent);}
.article-meta {font-size:11px;color:var(--admin-muted);display:block;margin-top:5px;}
.article-summary {font-size:12px;line-height:1.6;color:var(--admin-muted);margin:6px 0 0;display:-webkit-box;-webkit-line-clamp:1;-webkit-box-orient:vertical;overflow:hidden;}
.cell-status {padding:4px 9px;font-size:11px;border-radius:6px;}
.pub {background:var(--admin-soft-success);color:var(--admin-success);}
.draft {background:var(--admin-soft-warn);color:var(--admin-warning);}
.cell-tags {display:flex;flex-wrap:wrap;gap:4px;}
.cell-tag {padding:2px 7px;background:var(--admin-soft-accent);color:var(--admin-accent);font-size:11px;border-radius:5px;}
.cell-none {color:var(--admin-muted);}
.public-article-link {color:var(--admin-accent);font-size:12px;margin:0 7px;}
.table-empty {padding:30px 12px;color:var(--admin-muted);font-size:13px;}
.error-banner {padding:24px;display:flex;gap:16px;align-items:center;justify-content:center;color:var(--admin-danger);font-size:13px;}
@media(max-width:680px) {.content-panel{padding:14px 10px;} .result-count{margin-left:0;} .cell-cover{display:none;}}
</style>
