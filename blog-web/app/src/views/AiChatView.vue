<template>
  <section class="page">
    <div class="chat-panel">
      <div class="chat-header">
        <h2>AI 技术助手</h2>
        <p>小J · 技术问答与博客知识检索</p>
        <button
            class="clear-btn"
            @click="clearMemory"
            :disabled="!sessionId || loading || clearing"
        >
          {{ clearing ? '清除中…' : '清除记忆' }}
        </button>
      </div>

      <div class="chat-body" ref="chatBody">
        <div v-if="messages.length === 0" class="welcome">
          <span class="welcome-icon"><SiteIcon name="robot" :size="36"/></span>
          <h3>你好，我是小J</h3>
          <p>我是 Javerry 的 AI 技术助手，可以回答编程、架构、数据库等问题。</p>
          <div class="suggestions">
            <button
                v-for="s in suggestions"
                :key="s"
                @click="sendMessage(s)"
                :disabled="loading || clearing"
            >
              {{ s }}
            </button>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <img
              v-if="m.role === 'user' && auth.user?.avatar"
              :src="auth.user.avatar"
              class="msg-avatar"
              alt="你的头像"
          />
          <div v-else class="msg-avatar">{{ m.role === 'user' ? 'U' : 'J' }}</div>
          <div class="msg-content">
            <div class="msg-role">
              {{ m.role === 'user' ? auth.user?.nickname || '你' : '小J' }}
            </div>
            <div
                class="msg-text"
                v-html="renderMd(m.content)"
                :class="{ streaming: m.streaming }"
            ></div>
          </div>
        </div>

        <div v-if="loading" class="msg assistant">
          <div class="msg-avatar">J</div>
          <div class="msg-content">
            <div class="msg-text">思考中...</div>
          </div>
        </div>
      </div>

      <p v-if="error" class="chat-error" role="alert">{{ error }}</p>
      <div class="chat-input">
        <label class="sr-only" for="ai-question">技术问题</label>
        <textarea
            id="ai-question"
            v-model="input"
            @keydown.enter.exact="onEnter"
            placeholder="输入技术问题，Enter 发送，Shift+Enter 换行"
            rows="2"
            :disabled="loading || clearing"
        ></textarea>
        <button class="send-btn" @click="send" :disabled="loading || clearing || !input.trim()">
          {{ loading ? '回复中…' : '发送' }}
        </button>
      </div>
    </div>
  </section>
</template>

<script setup>
import {nextTick, onMounted, onUnmounted, ref} from 'vue'
import {useAuthStore} from '../stores/auth'
import {useRouter} from 'vue-router'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import SiteIcon from '../components/SiteIcon.vue'
import {createSseParser} from '../utils/sse'

const auth = useAuthStore()
const router = useRouter()
const md = new MarkdownIt({html: false, breaks: true})

const messages = ref([])
const input = ref('')
const loading = ref(false)
const clearing = ref(false)
const error = ref('')
let controller
let active = true
onUnmounted(() => {
  active = false
  controller?.abort()
})
const sessionId = ref('')
const chatBody = ref(null)

const suggestions = [
  'Java 中 HashMap 的实现原理？',
  '如何优化 MySQL 查询性能？',
  'Vue 3 的 Composition API 有什么优势？',
  '微服务架构的优缺点是什么？',
]

const renderMd = (text) => DOMPurify.sanitize(md.render(text || ''))

const scrollDown = async () => {
  await nextTick()
  if (chatBody.value) chatBody.value.scrollTop = chatBody.value.scrollHeight
}

const sendMessage = (msg) => {
  input.value = msg
  send()
}

const onEnter = (event) => {
  if (event.isComposing) return
  event.preventDefault()
  send()
}

async function checkResponse(resp) {
  if (resp.status === 401) {
    auth.logout()
    router.push('/login?redirect=/ai')
  }
  if (!resp.ok) {
    const body = await resp.json().catch(() => ({}))
    throw new Error(body.message || `服务暂时不可用（${resp.status}）`)
  }
}

const send = async () => {
  const text = input.value.trim()
  if (!text || loading.value || clearing.value) return
  loading.value = true
  error.value = ''
  input.value = ''
  const userMsg = {role: 'user', content: text}
  messages.value.push(userMsg)
  messages.value.push({role: 'assistant', content: '', streaming: true})
  const aiMsg = messages.value[messages.value.length - 1]
  await scrollDown()

  try {
    controller = new AbortController()
    const token = localStorage.getItem('blog_token')
    const resp = await fetch('/api/ai/chat', {
      method: 'POST',
      signal: controller.signal,
      headers: {'Content-Type': 'application/json', Authorization: token || ''},
      body: JSON.stringify({message: text, sessionId: sessionId.value}),
    })
    await checkResponse(resp)
    if (!resp.headers.get('content-type')?.includes('text/event-stream') || !resp.body)
      throw new Error('AI 服务返回的响应格式无效')
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    const parser = createSseParser((chunk) => {
      if (active) aiMsg.content += chunk
    })
    while (true) {
      const {done, value} = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, {stream: true}))
      await nextTick()
      await scrollDown()
    }
    parser.push(decoder.decode())
    parser.finish()
  } catch (err) {
    if (active && err.name !== 'AbortError') {
      error.value = err.message || '请求失败，请稍后再试。'
      if (!aiMsg.content) aiMsg.content = '本次未能取得回复，请重试。'
    }
  } finally {
    aiMsg.streaming = false
    loading.value = false
  }
}

const clearMemory = async () => {
  if (loading.value || clearing.value) return
  clearing.value = true
  error.value = ''
  try {
    const token = localStorage.getItem('blog_token')
    const resp = await fetch(`/api/ai/memory/${encodeURIComponent(sessionId.value)}`, {
      method: 'DELETE',
      headers: {Authorization: token || ''},
    })
    await checkResponse(resp)
    const result = await resp.json()
    if (result.code !== 0) throw new Error(result.message || '清除失败')
    messages.value = []
    sessionId.value = crypto.randomUUID()
  } catch (err) {
    error.value = err.message || '清除失败'
  } finally {
    clearing.value = false
  }
}

onMounted(() => {
  if (!auth.isLoggedIn) router.push('/login?redirect=/ai')
  sessionId.value = crypto.randomUUID()
})
</script>

<style scoped>
.page {
  width: min(960px, calc(100% - 48px));
  margin: 28px auto 48px;
}

.chat-panel {
  display: flex;
  flex-direction: column;
  height: min(750px, calc(100dvh - 160px));
  min-height: 440px;
  background: var(--web-paper);
  border: 1px solid var(--web-line);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: var(--web-shadow);
}

.chat-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--web-line);
}

.chat-header h2 {
  margin: 0;
  font-size: 18px;
}

.chat-header p {
  flex: 1;
  margin: 0;
  font-size: 12px;
  color: var(--web-muted);
}

.clear-btn {
  padding: 7px 12px;
  min-height: 36px;
  border: 1px solid var(--web-line);
  background: var(--web-soft);
  color: var(--web-muted);
  border-radius: 8px;
  font-size: 12px;
}

.chat-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px;
}

.welcome {
  padding: 48px 16px;
  text-align: center;
}

.welcome-icon {
  display: inline-flex;
  padding: 16px;
  border-radius: 16px;
  background: var(--web-tint);
  color: var(--web-accent);
}

.welcome h3 {
  font-size: 26px;
  margin: 18px 0 10px;
}

.welcome p {
  color: var(--web-muted);
  max-width: 480px;
  margin: 0 auto 28px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.suggestions button {
  padding: 10px 14px;
  border: 1px solid var(--web-line);
  border-radius: 8px;
  background: var(--web-paper);
  font-size: 13px;
}

.suggestions button:hover {
  color: var(--web-accent);
  border-color: var(--web-accent);
}

.msg {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

.msg.user {
  flex-direction: row-reverse;
}

.msg-avatar {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: #425aef;
  color: white;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  object-fit: cover;
}

.msg.user .msg-avatar {
  background: var(--web-muted);
}

.msg-content {
  max-width: calc(100% - 60px);
  min-width: 0;
}

.msg-role {
  font-size: 12px;
  color: var(--web-muted);
  margin-bottom: 6px;
}

.msg.user .msg-role {
  text-align: right;
}

.msg-text {
  padding: 12px 16px;
  border-radius: 12px;
  background: var(--web-soft);
  font-size: 15px;
  overflow-wrap: anywhere;
}

.msg.user .msg-text {
  background: var(--web-tint);
}

.msg-text.streaming::after {
  content: '▍';
}

.msg-text :deep(pre) {
  padding: 14px;
  overflow-x: auto;
  background: var(--web-paper);
  border: 1px solid var(--web-line);
  border-radius: 8px;
  font-size: 13px;
}

.msg-text :deep(code) {
  font-family: Consolas, monospace;
}

.msg-text :deep(p) {
  margin: 6px 0;
}

.msg-text :deep(a) {
  color: var(--web-accent);
  text-decoration: underline;
}

.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--web-line);
}

.chat-input textarea {
  flex: 1;
  resize: vertical;
  max-height: 180px;
}

.send-btn {
  padding: 12px 20px;
  min-height: 44px;
  border: 0;
  border-radius: 10px;
  background: #425aef;
  color: white;
}

.chat-error {
  padding: 0 24px;
  margin: 10px 0;
  color: var(--web-danger);
  font-size: 14px;
}

@media (max-width: 640px) {
  .page {
    width: calc(100% - 28px);
    margin: 20px auto;
  }

  .chat-header,
  .chat-input,
  .chat-body {
    padding: 14px;
  }

  .chat-header p {
    flex-basis: 100%;
    order: 3;
  }

  .welcome {
    padding: 24px 0;
  }

  .chat-input {
    gap: 8px;
  }

  .send-btn {
    padding: 12px;
  }
}
</style>
