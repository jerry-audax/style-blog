<template>
  <section class="tag-articles" aria-live="polite">
    <header><h1>{{ tagName || '标签文章' }}</h1><a href="/blog/tags/">全部标签</a></header>
    <p v-if="state === 'loading'" role="status">正在加载文章…</p>
    <p v-else-if="state === 'not-found'">标签不存在或已停用。</p>
    <div v-else-if="state === 'error'" role="alert">暂时无法加载标签文章。<button @click="load">重试</button></div>
    <template v-else>
      <p v-if="!articles.length">该标签下暂无已发布文章。</p>
      <article v-for="article in articles" :key="article.id" class="tag-article-card">
        <a v-if="article.cover" :href="article.href" tabindex="-1" aria-hidden="true" class="tag-article-cover"><img :src="article.cover" alt="" loading="lazy" decoding="async"></a>
        <div><h2><a class="tag-article-title" :href="article.href">{{ article.title }}</a></h2>
          <p v-if="article.summary" class="tag-article-summary">{{ article.summary }}</p></div>
      </article>
      <nav v-if="pages > 1" class="tag-article-pagination" aria-label="标签文章分页">
        <button :disabled="page <= 1" @click="go(page - 1)">上一页</button><span>{{ page }} / {{ pages }}</span>
        <button :disabled="page >= pages" @click="go(page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>

<script setup>
import {onMounted, onUnmounted, ref, watch} from 'vue'
import {publicContent, validContentId} from '../content/public-content'
import {visibleSummary} from '../content/summary'

const props = defineProps({tagId: {type: String, required: true}})
const state = ref('loading'), tagName = ref(''), articles = ref([]), page = ref(1), pages = ref(0)
let controller, generation = 0, active = true
const asId = value => typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : value
function coverUrl(value) {
    if (typeof value !== 'string') return ''
    try {const url = new URL(value, location.origin); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : ''}
    catch {return ''}
}
async function load() {
    controller?.abort(); controller = new AbortController()
    const signal = controller.signal, epoch = ++generation
    const current = () => active && epoch === generation && !signal.aborted
    state.value = 'loading'; articles.value = []
    if (!validContentId(props.tagId)) {state.value = 'not-found'; return}
    try {
        const tags = await publicContent('/api/content/tag/list', signal)
        if (!current()) return
        if (!Array.isArray(tags)) throw new Error('invalid tags')
        const tag = tags.find(tag => asId(tag.id) === props.tagId && tag.status === 1 && tag.deleted !== 1)
        if (!tag) {state.value = 'not-found'; return}
        tagName.value = tag.name
        const query = new URLSearchParams({tagId: props.tagId, status: '1', pageNum: String(page.value), pageSize: '10'})
        const data = await publicContent(`/api/content/article/list?${query}`, signal)
        if (!current()) return
        if (!data || !Array.isArray(data.records) || !Number.isSafeInteger(data.total) || data.total < 0 || data.current !== page.value ||
            data.size !== 10 || data.pages !== Math.ceil(data.total / 10) || data.records.length > 10 ||
            data.records.some(row => !validContentId(asId(row.id)) || row.status !== 1 || row.deleted === 1 || typeof row.title !== 'string'))
            throw new Error('invalid published page')
        articles.value = data.records.map(row => ({id: asId(row.id), title: row.title, summary: visibleSummary(row.summary), cover: coverUrl(row.coverUrl), href: `/blog/article/${asId(row.id)}/`}))
        pages.value = data.pages; state.value = 'content'
    } catch {if (current()) state.value = 'error'}
}
function go(value) {if (value < 1 || value > pages.value) return; page.value = value; void load()}
onMounted(load)
watch(() => props.tagId, () => {page.value = 1; tagName.value = ''; void load()})
onUnmounted(() => {active = false; generation++; controller?.abort()})
</script>

<style scoped>
.tag-articles {text-align: left; color: var(--anzhiyu-fontcolor);}
header {display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin-bottom: 24px;}
h1 {margin: 0; font-size: 1.8rem;}
.tag-article-card {display: flex; gap: 24px; padding: 24px 0; border-bottom: 1px solid var(--anzhiyu-card-border);}
.tag-article-cover {flex: 0 0 32%;}
.tag-article-cover img {width: 100%; aspect-ratio: 16 / 10; object-fit: cover; border-radius: 8px;}
h2 {margin: 0; font-size: 1.25rem;}
.tag-article-summary {margin: 12px 0 0; white-space: pre-wrap; overflow-wrap: anywhere;}
.tag-article-pagination {display: flex; align-items: center; justify-content: center; gap: 16px; margin-top: 24px;}
button {cursor: pointer; padding: 6px 12px; color: inherit; background: transparent; border: 1px solid var(--anzhiyu-card-border); border-radius: 6px;}
button:disabled {opacity: .45; cursor: default;}
@media (max-width: 600px) {.tag-article-card {gap: 14px;} .tag-article-cover {flex-basis: 28%;} h2 {font-size: 1rem;}}
</style>
