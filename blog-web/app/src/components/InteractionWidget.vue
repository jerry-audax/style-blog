<template>
  <div class="blog-interactions">
    <header class="interaction-header"><h2>交流与讨论</h2><span v-if="article">{{
        article.viewCount || 0
      }} 次阅读 · {{ article.likeCount || 0 }} 人赞同</span></header>
    <p v-if="loading" role="status">正在加载评论…</p>
    <p v-if="error" class="feedback error" role="alert">{{ error }}
      <button @click="load">重新加载</button>
    </p>
    <template v-if="article">
      <p v-if="article.isCommentEnabled === 0">这篇文章暂未开放评论。</p>
      <template v-else>
        <p class="comment-empty">本站不再提供访客账号，历史评论仅供阅读。</p>
        <ol v-if="comments.length" class="comments">
          <li v-for="comment in comments" :key="comment.id">
            <div class="comment-meta"><strong>{{ comment.nickname || '读者' }}</strong>
              <time>{{ comment.createdAt }}</time>
            </div>
            <p v-if="comment.replyToNickname" class="reply-hint">回复 {{ comment.replyToNickname }}</p>
            <p class="comment-content">{{ comment.content }}</p>
          </li>
        </ol>
      </template>
    </template>
  </div>
</template>
<script setup>
import {ref, onMounted, onUnmounted} from 'vue'
import {articleDetail, commentList} from '../api'

const props = defineProps({articleId: {type: String, required: true}})
const article = ref(null), comments = ref([]), loading = ref(true), error = ref('')
let active = true
onUnmounted(() => {
  active = false
})

async function load() {
  loading.value = true;
  error.value = ''
  try {
    const result = await articleDetail(props.articleId)
    if (!active) return
    article.value = result
    const list = result.isCommentEnabled === 0 ? [] : await commentList(props.articleId)
    if (active) comments.value = list
  } catch (err) {
    if (active) error.value = err.message || '评论暂时不可用，仍可阅读正文。'
  } finally {
    if (active) loading.value = false
  }
}

onMounted(load)
</script>
<style scoped>
.blog-interactions {
  margin-top: 40px;
  padding-top: 28px;
  border-top: 1px solid var(--anzhiyu-card-border);
  color: var(--anzhiyu-fontcolor);
  font-family: inherit;
}

.interaction-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 16px;
}

.interaction-header h2 {
  margin: 0 !important;
  font-size: 22px;
}

.interaction-header span,
.form-footer span,
time,
.reply-hint,
.comment-empty {
  color: var(--anzhiyu-secondtext);
  font-size: 13px;
}

.interaction-actions {
  margin: 20px 0;
}

button,
.interaction-actions a {
  display: inline-block;
  border: 1px solid var(--anzhiyu-card-border);
  border-radius: 8px;
  padding: 9px 15px;
  background: var(--anzhiyu-main);
  color: var(--site-accent-foreground, white);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.comment-form {
  display: grid;
  gap: 10px;
}

.comment-form label {
  font-weight: 600;
}

textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 14px;
  font: inherit;
  border: 1px solid var(--anzhiyu-card-border);
  border-radius: 10px;
  background: var(--anzhiyu-background);
  color: var(--anzhiyu-fontcolor);
  resize: vertical;
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.feedback {
  font-size: 14px;
}

.error {
  color: var(--anzhiyu-red);
}

.comments {
  list-style: none !important;
  padding: 0 !important;
}

.comments li {
  padding: 20px 0;
  border-bottom: 1px solid var(--anzhiyu-card-border);
}

.comment-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: baseline;
}

.comment-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.reply-button,
.reply-hint button {
  background: transparent;
  color: var(--anzhiyu-main);
  padding: 4px 10px;
}
</style>
