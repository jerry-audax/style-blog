<template>
  <section class="page">
    <header class="hero">
      <p class="eyebrow">ESSAYS &amp; NOTES</p>
      <p class="hero-sub">
        这里分享个人在技术学习、系统设计、开发历程的文章。
      </p>

      <div class="hero-line"></div>
    </header>

    <div v-if="loading" class="state-box">
      <p class="state-text">正在加载文章...</p>
    </div>

    <div v-else class="layout-with-sidebar">
      <aside class="sidebar">
        <nav class="category-nav">
          <h3 class="sidebar-title">浏览</h3>
          <ul class="category-list">
            <li>
              <button
                type="button"
                class="category-item category-button"
                :class="{ active: activeMode === 'all' }"
                @click="switchMode('all')"
                :aria-pressed="activeMode === 'all'"
              >
                全部文章
              </button>
            </li>
            <li>
              <button
                type="button"
                class="category-item category-button"
                :class="{ active: activeMode === 'hot' }"
                @click="switchMode('hot')"
                :aria-pressed="activeMode === 'hot'"
              >
                🔥 热门文章
              </button>
            </li>
          </ul>
          <h3 class="sidebar-title" style="margin-top:16px">分类</h3>
          <p v-if="metaError && categories.length === 0" class="sidebar-error">分类加载失败</p>
          <ul v-else class="category-list">
            <li
              v-for="category in categories"
              :key="category.id"
            >
              <button
                type="button"
                class="category-item category-button"
                :class="{ active: activeMode === 'category' && activeCategoryId === category.id }"
                @click="switchCategory(category.id)"
                :aria-pressed="activeMode === 'category' && activeCategoryId === category.id"
              >
                {{ category.name }}
              </button>
            </li>
          </ul>
        </nav>
      </aside>

      <div class="main-area">
        <!-- 全部文章 / 分类筛选 模式 -->
        <section v-if="activeMode !== 'hot'" class="feed-section">
          <div class="section-head compact">
            <div>
              <p class="section-eyebrow">ALL ARTICLES</p>
              <h2>
                {{
                  activeMode === 'category'
                    ? categoryName(activeCategoryId)
                    : activeMode === 'tag'
                      ? tagName(activeTagId)
                      : '全部文章'
                }}
              </h2>
            </div>
            <span class="section-hint">{{ totalCount }} 篇文章 · 按阅读量排序</span>
          </div>

          <template v-if="list.length > 0">
            <article v-for="(item, idx) in list" :key="item.id" class="card">
              <div class="card-index">
                <span class="index-num">{{ String(idx + 1 + (pageNum - 1) * pageSize).padStart(2, '0') }}</span>
              </div>
              <div class="card-body">
                <div class="meta-row">
                  <span class="meta-date">{{ item.publishTime || '待发布' }}</span>
                  <span v-if="item.isTop" class="top-badge">置顶</span>
                  <span class="meta-cat">{{ categoryName(item.categoryId) }}</span>
                  <span class="meta-views">{{ item.likeCount || 0 }} 赞 · {{ item.viewCount || 0 }} 阅读</span>
                </div>
                <h2>
                  <router-link :to="`/article/${item.id}`">{{ item.title }}</router-link>
                </h2>
                <img v-if="item.coverUrl" :src="item.coverUrl" class="card-cover" alt="cover" />
                <p class="card-summary">{{ item.summary }}</p>
                <router-link class="read-more" :to="`/article/${item.id}`">
                  继续阅读 <span class="arrow">&rarr;</span>
                </router-link>
              </div>
            </article>

            <div class="pager">
              <button :disabled="pageNum <= 1" @click="pageNum--; loadArticles()">
                <span class="pager-arrow">&larr;</span> 上一页
              </button>
              <span class="pager-info">第 {{ pageNum }} 页 / 共 {{ pages || 1 }} 页</span>
              <button :disabled="pageNum >= pages" @click="pageNum++; loadArticles()">
                下一页 <span class="pager-arrow">&rarr;</span>
              </button>
            </div>
          </template>

          <div v-else class="state-box narrow">
            <p class="state-title">暂无文章</p>
            <p class="state-text">当前分类还没有内容，切换到其他分类看看吧。</p>
          </div>
        </section>

        <!-- 热门文章 模式 -->
        <section v-if="activeMode === 'hot'" class="hot-section">
          <div class="section-head">
            <div>
              <p class="section-eyebrow">HOT ARTICLES</p>
              <h2>热门文章</h2>
            </div>
            <span class="section-hint">按点赞量排序</span>
          </div>

          <div v-if="hotLoading" class="state-box narrow">
            <p class="state-text">正在加载热门文章...</p>
          </div>

          <div class="hot-grid" v-else-if="hotList.length > 0">
            <article v-for="item in hotList" :key="`hot-${item.id}`" class="hot-card">
              <p class="hot-meta">
                <span>
                  <span v-if="item.isTop" class="top-badge">置顶</span>
                  {{ categoryName(item.categoryId) }}
                </span>
                <span>{{ item.likeCount || 0 }} 赞 · {{ item.viewCount || 0 }} 阅读</span>
              </p>
              <h3>
                <router-link :to="`/article/${item.id}`">{{ item.title }}</router-link>
              </h3>
              <img v-if="item.coverUrl" :src="item.coverUrl" class="card-cover" alt="cover" />
              <p class="hot-summary">{{ item.summary }}</p>
              <router-link class="read-more" :to="`/article/${item.id}`">
                继续阅读 <span class="arrow">&rarr;</span>
              </router-link>
            </article>
          </div>
          <div v-else class="state-box narrow">
            <p class="state-title">暂无热门文章</p>
            <p class="state-text">还没有人点赞，去看看全部文章吧。</p>
          </div>
        </section>
      </div>

      <aside class="sidebar sidebar-right" v-if="tags.length > 0 || metaError">
        <nav class="category-nav">
          <h3 class="sidebar-title">热门标签</h3>
          <p v-if="metaError && tags.length === 0" class="sidebar-error">标签加载失败</p>
          <ul v-else class="category-list">
            <li
              v-for="tag in tags"
              :key="tag.id"
            >
              <button
                type="button"
                class="category-item category-button"
                :class="{ active: activeMode === 'tag' && activeTagId === tag.id }"
                @click="switchTag(tag.id)"
                :aria-pressed="activeMode === 'tag' && activeTagId === tag.id"
              >
                {{ tag.name }}
              </button>
            </li>
          </ul>
        </nav>
      </aside>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { articleList, categoryList, hotArticleList, tagList } from '../api'

const route = useRoute()
const router = useRouter()

const list = ref([])
const hotList = ref([])
const categories = ref([])
const tags = ref([])
const pageNum = ref(1)
const pageSize = ref(10)
const pages = ref(1)
const loading = ref(false)
const hotLoading = ref(false)
const metaError = ref(false)
const total = ref(0)
const activeMode = ref('all')   // 'all' | 'hot' | 'category' | 'tag'
const activeCategoryId = ref(null)
const activeTagId = ref(null)

const categoryMap = computed(() => Object.fromEntries(categories.value.map(item => [item.id, item.name])))
const tagMap = computed(() => Object.fromEntries(tags.value.map(item => [item.id, item.name])))

const totalCount = computed(() => total.value)

const categoryName = (categoryId) => {
  if (!categoryId) return '未分类'
  return categoryMap.value[categoryId] || `分类 #${categoryId}`
}

const tagName = (tagId) => {
  if (!tagId) return '热门文章'
  return tagMap.value[tagId] || `标签 #${tagId}`
}

const syncFiltersFromRoute = () => {
  const isHot = route.query.hot === '1'
  activeCategoryId.value = route.query.category ? Number(route.query.category) : null
  activeTagId.value = route.query.tag ? Number(route.query.tag) : null
  if (isHot) {
    activeMode.value = 'hot'
  } else if (activeTagId.value) {
    activeMode.value = 'tag'
  } else if (activeCategoryId.value) {
    activeMode.value = 'category'
  } else {
    activeMode.value = 'all'
  }
}

const loadArticles = async () => {
  loading.value = true
  try {
    const cid = activeMode.value === 'category' ? (activeCategoryId.value || undefined) : undefined
    const tid = activeMode.value === 'tag' ? (activeTagId.value || undefined) : undefined
    const page = await articleList(pageNum.value, pageSize.value, cid, tid)
    list.value = page.records
    pages.value = page.pages || 1
    total.value = page.total || 0
  } finally {
    loading.value = false
  }
}

const loadMeta = async () => {
  // Load categories independently
  try {
    const res = await categoryList()
    categories.value = res || []
  } catch {
    categories.value = []
    metaError.value = true
  }

  // Load tags independently
  try {
    const res = await tagList()
    tags.value = res || []
  } catch {
    tags.value = []
    metaError.value = true
  }

  // Load hot articles independently with separate loading state
  hotLoading.value = true
  try {
    const res = await hotArticleList(6)
    hotList.value = res || []
  } catch {
    hotList.value = []
  } finally {
    hotLoading.value = false
  }
}

const switchMode = async (mode) => {
  pageNum.value = 1
  activeMode.value = mode
  activeCategoryId.value = null
  activeTagId.value = null
  if (mode === 'all') {
    await router.push({ path: '/' })
    await loadArticles()
  } else if (mode === 'hot') {
    await router.push({ path: '/', query: { hot: '1' } })
  } else {
    await loadArticles()
  }
}

const switchCategory = async (categoryId) => {
  pageNum.value = 1
  activeMode.value = 'category'
  activeCategoryId.value = categoryId
  activeTagId.value = null
  await router.push({ path: '/', query: { category: String(categoryId) } })
  await loadArticles()
}

const switchTag = async (tagId) => {
  pageNum.value = 1
  activeMode.value = 'tag'
  activeTagId.value = tagId
  activeCategoryId.value = null
  await router.push({ path: '/', query: { tag: String(tagId) } })
  await loadArticles()
}

watch(() => route.query, async () => {
  syncFiltersFromRoute()
  if (activeMode.value === 'all' || activeMode.value === 'category' || activeMode.value === 'tag') {
    await loadArticles()
  }
}, { deep: true })

onMounted(() => {
  syncFiltersFromRoute()
  loadMeta()
  loadArticles()
})
</script>

<style scoped>
.page { max-width: 1280px; margin: 0 auto; padding: 72px 24px 100px; }

.hero .eyebrow {
  margin: 0 0 18px;
  font-size: 13px;
  letter-spacing: .20em;
  color: rgba(255, 255, 255, 0.4);
  font-weight: 600;
}
.hero h1 {
  margin: 0;
  font-family: var(--web-font-display);
  font-size: clamp(48px, 7vw, 96px);
  line-height: 1.1;
  letter-spacing: .03em;
  color: rgba(255, 255, 255, 0.96);
  text-shadow: 0 4px 18px rgba(0, 0, 0, 0.42);
}
.hero-sub {
  margin: 20px 0 0;
  color: rgba(255, 255, 255, 0.74);
  font-size: 18px;
  line-height: 1.8;
  max-width: 56ch;
}
.hero-line { margin-top: 32px; width: 60px; height: 4px; border-radius: 2px; background: transparent; }

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 28px;
  margin-bottom: 22px;
}
.section-head.compact { margin-bottom: 16px; }
.section-eyebrow {
  margin: 0 0 6px;
  font-size: 11px;
  letter-spacing: .22em;
  color: rgba(255, 255, 255, 0.4);
}
.section-head h2 {
  margin: 0;
  font-family: var(--web-font-display);
  font-size: clamp(28px, 4vw, 42px);
  line-height: 1.1;
  color: rgba(255, 255, 255, 0.96);
  text-shadow: 0 3px 12px rgba(0, 0, 0, 0.32);
}
.section-hint { color: rgba(255, 255, 255, 0.58); font-size: 14px; }

.layout-with-sidebar {
  display: grid;
  grid-template-columns: minmax(150px, 200px) 1fr minmax(150px, 200px);
  gap: clamp(18px, 3vw, 40px);
  align-items: start;
}

.sidebar {
  position: sticky;
  top: 80px;
  min-width: 0;
  max-height: calc(100vh - 100px);
  overflow-y: auto;
}

.sidebar-title {
  margin: 0 0 12px;
  font-family: var(--web-font-display);
  font-size: 15px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.96);
}

.sidebar-error {
  margin: 0;
  padding: 10px 16px;
  font-size: 13px;
  color: rgba(255, 180, 80, 0.85);
  border-radius: 8px;
  background: rgba(255, 180, 80, 0.1);
  border: 1px solid rgba(255, 180, 80, 0.2);
}

.category-list {
  list-style: none; padding: 0; margin: 0;
  display: flex; flex-direction: column; gap: 6px;
}

.category-item {
  padding: 10px 16px; border-radius: 999px; cursor: pointer;
  font-size: 14px; color: rgba(255, 255, 255, 0.74);
  transition: all .22s ease;
  display: flex; justify-content: space-between; align-items: center; font-weight: 500;
}
.category-button {
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  appearance: none;
}
.category-button:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.35);
  outline-offset: 2px;
}
.category-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.9);
  transform: translateX(3px);
}
.category-item.active {
  background: rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.95);
  font-weight: 700;
  box-shadow: 0 2px 8px rgba(0,0,0,0.2);
}
.item-count {
  font-size: 12px; color: rgba(255, 255, 255, 0.4);
  background: rgba(255, 255, 255, 0.1);
  padding: 2px 8px; border-radius: 999px; font-weight: 400;
}

.main-area {
  min-width: 0;
}

.hot-section,
.feed-section { margin-bottom: 56px; }

.hot-grid {
  display: flex;
  flex-direction: column;
  gap: 28px;
  max-width: 640px;
}
.hot-card {
  padding: 32px 28px;
  background: rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-left: none;
  border-radius: var(--web-radius);
  box-shadow: 0 4px 24px rgba(0,0,0,0.2);
  display: flex; flex-direction: column;
  transition: all .25s ease;
}
.hot-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 32px rgba(0,0,0,0.3);
  border-color: rgba(255, 255, 255, 0.2);
}
.hot-meta {
  display: flex; justify-content: space-between; gap: 10px;
  margin: 0 0 16px; font-size: 12px;
  color: rgba(255, 255, 255, 0.65);
}
.hot-card h3 {
  margin: 0 0 16px; font-size: 22px; line-height: 1.3;
}
.hot-card h3 a { color: rgba(255, 255, 255, 0.96); }
.hot-card h3 a:hover { color: var(--web-accent-3); }
.hot-summary {
  margin: 0 0 auto;
  color: rgba(255, 255, 255, 0.74);
  font-size: 14px; line-height: 1.75;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.hot-card .read-more {
  margin-top: 14px; display: inline-flex; align-items: center; gap: 8px;
  font-size: 14px; font-weight: 700; color: rgba(255, 255, 255, 0.85);
}
.hot-card .read-more:hover { color: var(--web-accent-3); }
.hot-card .read-more .arrow { display: inline-block; transition: transform .25s ease; }
.hot-card:hover .read-more .arrow { transform: translateX(4px); }

.top-badge {
  display: inline-block;
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .06em;
  background: var(--web-accent);
  color: #fff;
  border-radius: 3px;
  margin-right: 6px;
}
.card-cover {
  width: 100%;
  max-height: 260px;
  object-fit: cover;
  margin: 10px 0;
  border-radius: var(--web-radius);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.section-head-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.category-small {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.category-chip {
  padding: 5px 14px;
  font-size: 12px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.65);
  border-radius: 999px;
  cursor: pointer;
  transition: all .18s ease;
}
.category-chip:hover,
.category-chip.active {
  background: var(--web-accent-2);
  border-color: rgba(255, 255, 255, 0.85);
  color: rgba(255, 255, 255, 0.9);
}

.state-box { padding: 64px 0; text-align: center; }
.state-box.narrow { padding: 36px 0 12px; }
.state-title { margin: 0; font-size: 28px; color: rgba(255, 255, 255, 0.8); }
.state-text { margin: 12px 0 0; color: rgba(255, 255, 255, 0.65); font-size: 16px; }

.card {
  display: grid;
  grid-template-columns: 60px 1fr;
  gap: 28px;
  padding: 36px 28px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--web-radius);
  background: rgba(0, 0, 0, 0.25);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  box-shadow: 0 2px 16px rgba(0,0,0,0.15);
  margin-bottom: 20px;
  transition: all .25s ease;
}
.card:hover {
  transform: translateX(4px);
  border-color: rgba(255, 255, 255, 0.2);
  box-shadow: 0 4px 24px rgba(0,0,0,0.25);
}

.card-index { padding-top: 2px; }
.index-num {
  font-size: 18px;
  color: rgba(255, 255, 255, 0.4);
  font-family: var(--web-font-display);
}

.meta-row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.meta-date, .meta-views { font-size: 13px; color: rgba(255, 255, 255, 0.65); }
.meta-cat {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
  font-weight: 600;
  padding: 3px 12px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 999px;
}

.card h2 {
  margin: 0 0 16px;
  font-size: clamp(26px, 3.5vw, 40px);
  line-height: 1.2;
}
.card h2 a { color: rgba(255, 255, 255, 0.95); transition: color .2s ease; }
.card h2 a { color: rgba(255, 255, 255, 0.96); transition: color .2s ease; }
.card h2 a:hover { color: var(--web-accent-3); }

.card-summary {
  margin: 0;
  color: rgba(255, 255, 255, 0.74);
  font-size: 15px;
  line-height: 1.7;
  max-width: 58ch;
}

.read-more {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  font-size: 14px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.85);
  transition: color .2s ease;
}
.read-more .arrow {
  display: inline-block;
  transition: transform .25s ease;
}
.read-more:hover { color: var(--web-accent-3); }
.card:hover .read-more .arrow { transform: translateX(4px); }

.pager {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 48px;
}
.pager button {
  padding: 10px 22px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(0, 0, 0, 0.3);
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
  cursor: pointer;
  border-radius: 999px;
  transition: all .22s ease;
  display: flex;
  align-items: center;
  gap: 6px;
}
.pager button:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.3);
  color: #fff;
  border-color: var(--web-accent-3);
  transform: scale(1.04);
}
.pager button:disabled { opacity: .35; cursor: not-allowed; }
.pager-info { font-size: 14px; color: rgba(255, 255, 255, 0.65); }
.pager-info { font-size: 14px; color: rgba(255, 255, 255, 0.72); }

@media (min-width: 1100px) {
  .hot-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    max-width: none;
  }
}

@media (max-width: 860px) {
  .hot-grid { max-width: none; }
  .layout-with-sidebar {
    grid-template-columns: 1fr;
    gap: 28px;
  }
  .sidebar {
    position: static;
    max-height: none;
  }
  .sidebar-right { order: 3; }
  .category-list, .sidebar-right .category-list {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 6px;
  }
  .category-item {
    padding: 6px 12px;
    font-size: 13px;
  }
  .item-count { display: none; }
  .sidebar-title { margin-bottom: 10px; font-size: 14px; }
}

@media (max-width: 720px) {
  .hero h1 { font-size: 40px; }
  .card { grid-template-columns: 1fr; gap: 8px; }
  .card-index { display: none; }
  .hero-sub { font-size: 16px; }
  .section-head { flex-direction: column; align-items: flex-start; }
}
@media (max-width: 480px) {
  .hot-card { padding: 20px 16px; }
  .card { padding: 24px 14px; }
  .pager { flex-direction: column; gap: 8px; }
}
</style>
