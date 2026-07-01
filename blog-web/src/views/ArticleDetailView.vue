<template>
  <div class="article-detail-page">
    <section class="page state-box" v-if="loading">
      <p class="state-text">加载中...</p>
    </section>

    <section class="page state-box error-box" v-else-if="loadError">
      <p class="state-text">{{ loadError }}</p>
      <router-link class="back-link" to="/">&larr; 返回列表</router-link>
    </section>

    <section class="page" v-else-if="article">
      <header class="article-hero">
        <router-link class="back-link" to="/">&larr; 返回列表</router-link>
        <p class="eyebrow">LONGFORM ENTRY</p>
        <h1>{{ article.title }}</h1>
        <div class="hero-meta">
          <span>{{ article.publishTime || '待发布' }}</span>
          <span v-if="article.isTop" class="top-badge">置顶</span>
          <span>阅读 {{ article.viewCount || 0 }}</span>
          <button
            class="like-btn"
            :class="{ liked: liked }"
            @click="doLike"
            :disabled="!auth.isLoggedIn"
            :title="auth.isLoggedIn ? '点赞' : '登录后可点赞'"
          >
            <span class="like-icon">{{ liked ? '♥' : '♡' }}</span>
            <span>{{ article.likeCount || 0 }}</span>
          </button>
        </div>
        <img v-if="article.coverUrl" :src="article.coverUrl" class="detail-cover" alt="cover" />
        <div class="hero-line"></div>
      </header>

      <section class="article-body">
        <div class="content" v-html="renderedMarkdown" @click="onImageClick"></div>
      </section>

      <section class="comment-card" v-if="article.isCommentEnabled !== 0">
        <div class="comment-head">
          <h2>评论区</h2>
          <span v-if="auth.isLoggedIn">{{ comments.length }} 条互动</span>
        </div>

        <!-- Guest mode: login to view comments -->
        <div v-if="!auth.isLoggedIn" class="guest-banner">
          <p>登录后可查看和发表评论。</p>
          <router-link :to="`/login?redirect=/article/${article.id}`" class="login-cta">立即登录 / 注册</router-link>
        </div>

        <!-- Logged in: full comment section -->
        <template v-else>
          <div class="composer">
            <textarea v-model="content" placeholder="写下你的看法..." rows="3"></textarea>
            <button class="primary-btn" @click="submitComment">发布评论</button>
          </div>

          <p v-if="msg" class="msg" :class="{ error: isError }">{{ msg }}</p>

          <ul class="comment-list" v-if="comments.length > 0">
            <li v-for="c in comments" :key="c.id" class="comment-item">
              <div class="comment-body">
                <div class="comment-left">
                  <img v-if="c.avatar" :src="c.avatar" class="comment-avatar-img" />
                  <span v-else class="comment-avatar">{{ (c.nickname || '用户')[0] }}</span>
                </div>
                <div class="comment-main">
                  <div class="comment-meta">
                    <span class="comment-author">{{ c.nickname || '用户' }}</span>
                    <span class="comment-time">{{ fmtTime(c.createdAt) }}</span>
                  </div>
                  <p class="comment-text">{{ c.content }}</p>
                  <div class="reply-row">
                    <input v-model="replyContent[c.id]" :placeholder="`回复 ${c.nickname || '用户'}...`" />
                    <button class="ghost-btn" @click="submitReply(c)">回复</button>
                  </div>
                </div>
              </div>
            </li>
          </ul>

          <div v-else class="empty-comments">
            <p>暂无评论，成为第一个参与讨论的人。</p>
          </div>
        </template>
      </section>
    </section>

    <!-- 图片点击放大预览 -->
    <Teleport to="body">
      <div class="img-preview-overlay" v-if="previewSrc" @click="previewSrc = ''">
        <img :src="previewSrc" @click.stop alt="preview" />
        <button class="img-preview-close" @click="previewSrc = ''">&times;</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { addComment, articleDetail, commentList, toggleLike } from '../api'
import { useAuthStore } from '../stores/auth'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const route = useRoute()
const auth = useAuthStore()
const article = ref(null)
const comments = ref([])
const content = ref('')
const liked = ref(false)
const msg = ref('')
const isError = ref(false)
const replyContent = ref({})
const loading = ref(false)
const loadError = ref('')

const md = new MarkdownIt({ html: true, breaks: true })

const fmtTime = (raw) => {
  if (!raw) return ''
  const d = new Date(raw)
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }) + ' ' +
         d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const renderedMarkdown = computed(() => {
  if (!article.value?.contentMd) return ''
  return DOMPurify.sanitize(md.render(article.value.contentMd))
})

const load = async () => {
  const id = route.params.id
  loading.value = true
  loadError.value = ''
  article.value = null
  comments.value = []
  try {
    article.value = await articleDetail(id)
    comments.value = await commentList(id)
  } catch (e) {
    loadError.value = e.message || '文章加载失败'
  } finally {
    loading.value = false
  }
}

const submitComment = async () => {
  if (!content.value.trim()) return
  try {
    await addComment({ articleId: Number(route.params.id), content: content.value })
    content.value = ''
    comments.value = await commentList(route.params.id)
    msg.value = '评论成功'
    isError.value = false
  } catch (e) {
    msg.value = e.message
    isError.value = true
  }
}

const doLike = async () => {
  try {
    const res = await toggleLike(route.params.id)
    liked.value = res.liked
    article.value = await articleDetail(route.params.id)
  } catch { /* ignore */ }
}

const submitReply = async (comment) => {
  const rc = replyContent.value[comment.id]
  if (!rc || !rc.trim()) return
  try {
    await addComment({ articleId: Number(route.params.id), parentId: comment.id, replyToUserId: comment.userId, content: rc })
    replyContent.value[comment.id] = ''
    comments.value = await commentList(route.params.id)
    msg.value = '回复成功'
    isError.value = false
  } catch (e) {
    msg.value = e.message
    isError.value = true
  }
}

const previewSrc = ref('')
const onImageClick = (e) => {
  if (e.target.tagName === 'IMG' && e.target.src) previewSrc.value = e.target.src
}

onMounted(load)

watch(
  () => route.params.id,
  async (nextId, prevId) => {
    if (nextId && nextId !== prevId) {
      await load()
    }
  }
)
</script>

<style scoped>
.page { max-width: 960px; margin: 0 auto; padding: 56px 24px 72px; }
.article-detail-page { min-height: 100%; }

.article-hero { margin-bottom: 36px; }
.back-link {
  display: inline-flex;
  margin-bottom: 18px;
  color: rgba(255, 255, 255, 0.85);
  font-size: 15px;
  transition: all .2s ease;
  padding: 6px 14px;
  border-radius: 999px;
}
.back-link:hover { color: var(--web-accent-3); background: rgba(255, 255, 255, 0.08); }
.eyebrow {
  margin: 0 0 12px;
  font-size: 12px;
  letter-spacing: .18em;
  color: var(--web-accent-3);
}
.article-hero h1 {
  margin: 0;
  font-family: var(--web-font-display);
  font-size: clamp(38px, 5vw, 68px);
  line-height: 1.1;
  letter-spacing: .03em;
  color: rgba(255, 255, 255, 0.98);
  text-shadow: 0 4px 18px rgba(0, 0, 0, 0.42);
}
.summary {
  margin-top: 16px;
  color: rgba(255, 255, 255, 0.65);
  font-size: 18px;
  line-height: 1.7;
  max-width: 58ch;
}
.hero-meta {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-top: 14px;
  color: rgba(255, 255, 255, 0.74);
  font-size: 14px;
  letter-spacing: .04em;
}
.like-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.65);
  font-size: 14px;
  border-radius: 999px;
  cursor: pointer;
  transition: all .18s ease;
}
.like-btn:hover:not(:disabled) {
  border-color: var(--web-accent-3);
  color: var(--web-accent-3);
  transform: scale(1.06);
}
.like-btn.liked {
  color: #e8618c;
  border-color: rgba(232, 97, 140, 0.3);
  background: rgba(255, 255, 255, 0.08);
}
.like-btn:disabled { opacity: .5; cursor: not-allowed; }
.like-icon { font-size: 16px; transition: transform .2s ease; }
.like-btn.liked .like-icon { transform: scale(1.2); }
.hero-line {
  margin-top: 28px;
  width: 48px;
  height: 3px;
  background: var(--web-accent);
}
.detail-cover {
  width: 100%;
  max-height: 420px;
  object-fit: cover;
  margin-top: 20px;
  border-radius: var(--web-radius);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.top-badge {
  display: inline-block;
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .06em;
  background: var(--web-accent);
  color: #fff;
  border-radius: 3px;
}

.article-body {
  margin: 32px 0 48px;
  padding: 36px 32px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--web-radius);
  background: rgba(0, 0, 0, 0.3); backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
  box-shadow: var(--web-shadow);
}
.content { line-height: 1.9; color: rgba(255, 255, 255, 0.85); font-size: 17px; }
.content { line-height: 1.9; color: rgba(255, 255, 255, 0.9); font-size: 17px; }
.content :deep(h1) { font-size: 36px; margin: 32px 0 16px; line-height: 1.15; }
.content :deep(h2) { font-size: 28px; margin: 28px 0 14px; line-height: 1.2; }
.content :deep(h3) { font-size: 22px; margin: 24px 0 12px; line-height: 1.25; }
.content :deep(p) { margin: 0 0 20px; }
.content :deep(pre) {
  background: rgba(255, 255, 255, 0.05);
  padding: 20px 24px;
  overflow-x: auto;
  font-size: 15px;
  line-height: 1.6;
  border-left: 4px solid var(--web-accent);
  margin: 20px 0;
  border-radius: 0 var(--web-radius) var(--web-radius) 0;
}
.content :deep(code) {
  font-family: "Consolas", "Monaco", monospace;
  font-size: 0.9em;
  background: rgba(255, 255, 255, 0.06);
  padding: 2px 6px;
  border-radius: 3px;
}
.content :deep(pre code) { background: none; padding: 0; }
.content :deep(blockquote) {
  border-left: 4px solid var(--web-accent);
  padding: 10px 20px;
  margin: 20px 0;
  color: rgba(255, 255, 255, 0.65);
  font-style: italic;
  background: rgba(255, 255, 255, 0.04);
  border-radius: 0 var(--web-radius) var(--web-radius) 0;
}
.content :deep(li) { margin: 6px 0; }
.content :deep(a) { color: rgba(255, 255, 255, 0.85); text-decoration: underline; }
.content :deep(strong) { font-weight: 700; }
.content :deep(img) { max-width: 100%; height: auto; margin: 20px 0; border-radius: 6px; display: block; cursor: zoom-in; }

.comment-card {
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--web-radius);
  background: rgba(0, 0, 0, 0.3); backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
  box-shadow: var(--web-shadow);
  padding: 32px;
}
.comment-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.comment-head h2 { margin: 0; font-size: 28px; }
.comment-head span { color: rgba(255, 255, 255, 0.65); font-size: 14px; }
.comment-head span { color: rgba(255, 255, 255, 0.74); font-size: 14px; }

.guest-banner {
  padding: 24px;
  text-align: center;
  background: rgba(47, 93, 80, 0.04);
  border: 1px dashed var(--web-line);
  border-radius: var(--web-radius);
  margin-bottom: 16px;
}
.guest-banner p { margin: 0 0 12px; color: rgba(255, 255, 255, 0.65); font-size: 16px; }
.login-cta {
  display: inline-block;
  padding: 12px 24px;
  background: var(--web-accent-2);
  color: #fff;
  font-weight: 700;
  border-radius: var(--web-radius);
  transition: all .18s ease;
}
.login-cta:hover { background: var(--web-accent); transform: translateY(-1px); }

.composer { display: flex; flex-direction: column; gap: 12px; margin-bottom: 18px; }
.composer textarea {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255,255,255,0.9);
  color: rgba(255, 255, 255, 0.85);
  font-size: 15px;
  border-radius: var(--web-radius);
  resize: vertical;
  min-height: 80px;
}
.composer textarea:focus {
  outline: 2px solid rgba(74, 144, 217, 0.18);
  border-color: rgba(255, 255, 255, 0.85);
}
.composer button { align-self: flex-end; min-width: 100px; }

input {
  width: 100%;
  padding: 12px 14px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255,255,255,0.88);
  color: rgba(255, 255, 255, 0.85);
  font-size: 15px;
  border-radius: var(--web-radius);
}
input:focus {
  outline: 2px solid rgba(159, 61, 34, 0.18);
  border-color: rgba(159, 61, 34, 0.32);
}

button {
  cursor: pointer;
  padding: 12px 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 14px;
  border-radius: var(--web-radius);
  transition: all .18s ease;
}
button:hover { transform: translateY(-1px); }

.primary-btn {
  background: var(--web-accent-3);
  color: #fff;
  border-color: var(--web-accent-3);
  font-weight: 700;
  box-shadow: 0 6px 20px rgba(240, 140, 160, 0.25);
}
.primary-btn:hover { background: #e47890; transform: scale(1.03); }

.ghost-btn { background: rgba(255, 255, 255, 0.12); color: rgba(255, 255, 255, 0.85); }
.ghost-btn:hover { background: rgba(255,255,255,0.95); }

.msg { margin: 12px 0; font-size: 14px; color: var(--web-accent-2); }
.msg.error { color: #c0392b; }

.empty-comments { padding: 32px 0; text-align: center; color: rgba(255, 255, 255, 0.65); }

.comment-list { list-style: none; padding: 0; margin: 0; }
.comment-item {
  border-top: 1px solid var(--web-line);
  padding: 20px 0;
}
.comment-body { display: flex; gap: 14px; align-items: flex-start; }
.comment-left { flex-shrink: 0; }
.comment-avatar {
  width: 38px; height: 38px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--web-accent), var(--web-accent-3));
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.comment-avatar-img {
  width: 38px; height: 38px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}
.comment-main { flex: 1; min-width: 0; }
.comment-meta { display: flex; gap: 10px; align-items: baseline; margin-bottom: 6px; }
.comment-author { font-weight: 600; font-size: 14px; color: rgba(255, 255, 255, 0.85); }
.comment-author { font-weight: 600; font-size: 14px; color: rgba(255, 255, 255, 0.92); }
.comment-time { font-size: 12px; color: rgba(255, 255, 255, 0.72); }
.comment-text { margin: 0 0 10px; line-height: 1.7; font-size: 15px; color: rgba(255, 255, 255, 0.9); }

.reply-row {
  margin-top: 4px;
  display: flex;
  gap: 10px;
}
.reply-row input { flex: 1; }

.state-box { text-align: center; padding: 120px 24px; }
.state-text { color: rgba(255, 255, 255, 0.65); font-size: 18px; }

@media (max-width: 720px) {
  .comment-head { flex-direction: column; align-items: flex-start; gap: 6px; }
  .reply-row { margin-left: 0; }
  .article-body { padding: 24px 20px; }
  .comment-card { padding: 24px 20px; }
}
@media (max-width: 480px) {
  .page { padding: 32px 14px 48px; }
  .article-body { padding: 20px 14px; }
  .comment-card { padding: 20px 14px; }
}
/* image click preview */
.img-preview-overlay {
  position: fixed; inset: 0; z-index: 9999;
  background: rgba(0,0,0,0.88);
  display: flex; align-items: center; justify-content: center;
  cursor: zoom-out;
}
.img-preview-overlay img {
  max-width: 92vw; max-height: 92vh;
  object-fit: contain; border-radius: 6px;
  box-shadow: 0 8px 48px rgba(0,0,0,0.5);
}
.img-preview-close {
  position: absolute; top: 20px; right: 24px;
  background: rgba(255,255,255,0.12); border: none;
  color: #fff; font-size: 30px; width: 40px; height: 40px;
  border-radius: 50%; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
}
.img-preview-close:hover { background: rgba(255,255,255,0.25); }
</style>
