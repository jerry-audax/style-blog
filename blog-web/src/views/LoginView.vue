<template>
  <section class="page" :style="pageStyle">
    <div class="auth-card">
      <div class="auth-intro">
        <span class="intro-icon">&#9670;</span>
        <p class="eyebrow">JOIN THE CONVERSATION</p>
        <h1>加入博客社区</h1>
        <p class="lead">
          注册账号后即可在文章下方发表评论、参与讨论。我们使用手机验证码登录，无需记忆密码。
        </p>
        <div class="feature-list">
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>阅读所有公开文章</span>
          </div>
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>发表评论与回复</span>
          </div>
          <div class="feature-item">
            <span class="feature-dot"></span>
            <span>收藏喜欢的文章</span>
          </div>
        </div>
      </div>

      <div class="auth-form">
        <div class="tab-row">
          <button class="tab-btn" :class="{ active: mode === 'sms' }" @click="mode = 'sms'">短信登录</button>
          <button class="tab-btn" :class="{ active: mode === 'pwd' }" @click="mode = 'pwd'">密码登录</button>
          <button class="tab-btn" :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
        </div>

        <div class="field-stack">
          <label>手机号</label>
          <input v-model="phone" type="tel" placeholder="输入手机号" maxlength="11" />
        </div>

        <!-- 短信登录 / 注册 — 验证码 -->
        <div class="field-stack" v-if="mode !== 'pwd'">
          <label>验证码</label>
          <div class="code-row">
            <input v-model="code" placeholder="6 位验证码" maxlength="6" />
            <button class="send-btn" @click="send" :disabled="sending">
              {{ sending ? '发送中...' : '获取验证码' }}
            </button>
          </div>
        </div>

        <!-- 密码登录 — 密码 -->
        <div class="field-stack" v-if="mode === 'pwd'">
          <label>密码</label>
          <input v-model="pwd" type="password" placeholder="输入密码" />
        </div>

        <!-- 注册 — 密码 + 确认密码 -->
        <template v-if="mode === 'register'">
          <div class="field-stack">
            <label>设置密码</label>
            <input v-model="pwd" type="password" placeholder="6-32 位密码" />
          </div>
          <div class="field-stack">
            <label>确认密码</label>
            <input v-model="confirmPwd" type="password" placeholder="再次输入密码" />
          </div>
        </template>

        <button class="submit-btn" @click="handleSubmit" :disabled="auth.loading">
          {{ auth.loading ? '处理中...' : (mode === 'register' ? '注册' : '登录') }}
        </button>

        <p v-if="mode === 'pwd'" class="forgot-link">
          <a href="#" @click.prevent="showReset = true">忘记密码？</a>
        </p>

        <div class="hint-box" v-if="mockCode">
          <span>开发环境验证码</span>
          <strong>{{ mockCode }}</strong>
        </div>

        <p v-if="msg" class="msg" :class="{ error: isError }">{{ msg }}</p>

        <p class="back-link">
          <router-link to="/">&larr; 返回文章列表</router-link>
        </p>
      </div>
    </div>

    <!-- 忘记密码弹窗 -->
    <div class="modal-overlay" v-if="showReset" @click.self="showReset = false">
      <div class="modal-card">
        <h3>重置密码</h3>
        <div class="field-stack">
          <label>手机号</label>
          <input v-model="resetPhone" type="tel" placeholder="输入手机号" maxlength="11" />
        </div>
        <div class="field-stack">
          <label>验证码</label>
          <div class="code-row">
            <input v-model="resetCode" placeholder="6 位验证码" maxlength="6" />
            <button class="send-btn" @click="sendResetCode" :disabled="sendingReset">
              {{ sendingReset ? '发送中...' : '获取验证码' }}
            </button>
          </div>
        </div>
        <div class="field-stack">
          <label>新密码</label>
          <input v-model="resetPwd" type="password" placeholder="6-32 位新密码" />
        </div>
        <button class="submit-btn" @click="doResetPassword" :disabled="resetting">
          {{ resetting ? '重置中...' : '重置密码' }}
        </button>
        <p v-if="resetMsg" class="msg" :class="{ error: resetError }">{{ resetMsg }}</p>
        <p class="forgot-link"><a href="#" @click.prevent="showReset = false">返回登录</a></p>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { sendCode, resetPassword } from '../api'
import { webAssets } from '../config/assets'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const assets = webAssets
const pageStyle = {
  '--web-login-bg': `url(${assets.backgrounds.login})`
}

const mode = ref('sms')
const phone = ref('')
const code = ref('')
const pwd = ref('')
const confirmPwd = ref('')
const mockCode = ref('')
const msg = ref('')
const isError = ref(false)
const sending = ref(false)

const send = async () => {
  if (!phone.value || phone.value.length < 11) {
    msg.value = '请输入正确的手机号'; isError.value = true; return
  }
  sending.value = true
  try {
    const bizType = mode.value === 'register' ? 'REGISTER' : 'LOGIN'
    const res = await sendCode(phone.value, bizType)
    mockCode.value = res.mockCode
    msg.value = '验证码已发送'; isError.value = false
  } catch (e) {
    msg.value = e.message; isError.value = true
  } finally { sending.value = false }
}

const handleSubmit = async () => {
  msg.value = ''; isError.value = false
  try {
    if (mode.value === 'pwd') {
      if (!pwd.value) { msg.value = '请输入密码'; isError.value = true; return }
      await auth.loginByPassword(phone.value, pwd.value)
    } else if (mode.value === 'sms') {
      if (!code.value) { msg.value = '请输入验证码'; isError.value = true; return }
      await auth.login(phone.value, code.value)
    } else {
      if (!code.value) { msg.value = '请输入验证码'; isError.value = true; return }
      if (!pwd.value || !confirmPwd.value) { msg.value = '请设置密码'; isError.value = true; return }
      if (pwd.value !== confirmPwd.value) { msg.value = '两次密码不一致'; isError.value = true; return }
      if (pwd.value.length < 6) { msg.value = '密码至少 6 位'; isError.value = true; return }
      await auth.register(phone.value, code.value, pwd.value, confirmPwd.value)
    }
    router.push(route.query.redirect || '/')
  } catch (e) {
    msg.value = e.message; isError.value = true
  }
}

// ---- 忘记密码 ----
const showReset = ref(false)
const resetPhone = ref('')
const resetCode = ref('')
const resetPwd = ref('')
const resetMsg = ref('')
const resetError = ref(false)
const sendingReset = ref(false)
const resetting = ref(false)

const sendResetCode = async () => {
  if (!resetPhone.value || resetPhone.value.length < 11) {
    resetMsg.value = '请输入正确的手机号'; resetError.value = true; return
  }
  sendingReset.value = true
  try {
    await sendCode(resetPhone.value, 'RESET_PASSWORD')
    resetMsg.value = '验证码已发送'; resetError.value = false
  } catch (e) {
    resetMsg.value = e.message; resetError.value = true
  } finally { sendingReset.value = false }
}

const doResetPassword = async () => {
  if (!resetCode.value) { resetMsg.value = '请输入验证码'; resetError.value = true; return }
  if (!resetPwd.value || resetPwd.value.length < 6) { resetMsg.value = '密码至少 6 位'; resetError.value = true; return }
  resetting.value = true
  try {
    await resetPassword(resetPhone.value, resetCode.value, resetPwd.value)
    resetMsg.value = '密码重置成功，请使用新密码登录'; resetError.value = false
    setTimeout(() => { showReset.value = false; mode.value = 'pwd'; phone.value = resetPhone.value }, 1500)
  } catch (e) {
    resetMsg.value = e.message; resetError.value = true
  } finally { resetting.value = false }
}
</script>

<style scoped>
.page {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 72px 24px 40px;
  background: var(--web-login-bg) center / cover no-repeat;
  z-index: 0;
}
/* 遮罩让卡片可读 */
.page::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(135deg, rgba(8, 12, 24, 0.74), rgba(8, 12, 24, 0.38)),
    radial-gradient(circle at top left, rgba(240, 140, 160, 0.12), transparent 30%),
    radial-gradient(circle at bottom right, rgba(74, 144, 217, 0.10), transparent 26%);
  z-index: 0;
}
.page > * { position: relative; z-index: 1; }

.auth-card {
  width: min(880px, 100%);
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  background: rgba(10, 14, 24, 0.42);
  backdrop-filter: blur(18px) saturate(1.2);
  -webkit-backdrop-filter: blur(18px) saturate(1.2);
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: var(--web-radius);
  box-shadow: 0 18px 60px rgba(0, 0, 0, 0.36);
  overflow: hidden;
}

.auth-intro {
  padding: 48px 40px;
  background: rgba(255, 255, 255, 0.05);
  border-right: 1px solid rgba(255, 255, 255, 0.10);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.intro-icon {
  font-size: 36px;
  color: rgba(255, 255, 255, 0.82);
  margin-bottom: 18px;
  text-shadow: 0 0 16px rgba(255, 255, 255, 0.18);
}
.eyebrow {
  margin: 0;
  font-size: 12px;
  letter-spacing: .16em;
  color: rgba(255, 255, 255, 0.62);
}
.auth-intro h1 {
  margin: 10px 0 16px;
  font-family: var(--web-font-display);
  font-size: 42px;
  line-height: 1.1;
  letter-spacing: .03em;
  color: rgba(255, 255, 255, 0.98);
  text-shadow: 0 3px 18px rgba(0, 0, 0, 0.42);
}
.lead {
  margin: 0 0 28px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 16px;
  line-height: 1.75;
}

.feature-list { display: flex; flex-direction: column; gap: 12px; }
.feature-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.82);
}
.feature-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--web-accent-3);
  box-shadow: 0 0 10px rgba(240, 140, 160, 0.45);
  flex-shrink: 0;
}

.auth-form {
  padding: 48px 40px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.tab-row {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}
.tab-btn {
  flex: 1;
  padding: 12px;
  font-size: 15px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.07);
  color: rgba(255, 255, 255, 0.74);
  cursor: pointer;
  border-radius: 999px;
  transition: all .22s ease;
}
.tab-btn.active {
  background: rgba(255, 255, 255, 0.94);
  color: #1a1a2e;
  border-color: transparent;
  font-weight: 800;
  box-shadow: 0 10px 24px rgba(255, 255, 255, 0.12);
}
.tab-btn:not(.active):hover { color: rgba(255, 255, 255, 0.96); border-color: rgba(255, 255, 255, 0.42); }

.field-stack { display: flex; flex-direction: column; gap: 6px; }
label { font-size: 13px; color: rgba(255, 255, 255, 0.6); letter-spacing: .06em; }
label { font-size: 13px; color: rgba(255, 255, 255, 0.7); letter-spacing: .08em; }

input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.09);
  color: rgba(255, 255, 255, 0.96);
  font-size: 16px;
  border-radius: var(--web-radius);
  transition: border-color .18s ease, background .18s ease;
}
input::placeholder { color: rgba(255, 255, 255, 0.38); }
input:focus {
  outline: none;
  border-color: rgba(255, 255, 255, 0.44);
  background: rgba(255, 255, 255, 0.13);
}

.code-row { display: flex; gap: 10px; }
.code-row input { flex: 1; }

.send-btn {
  padding: 14px 18px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.86);
  font-size: 14px;
  cursor: pointer;
  white-space: nowrap;
  border-radius: 999px;
  transition: all .22s ease;
}
.send-btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.18); color: #fff; }
.send-btn:disabled { opacity: .4; cursor: not-allowed; }

.submit-btn {
  padding: 16px;
  background: linear-gradient(135deg, rgba(255,255,255,0.96), rgba(255,255,255,0.88));
  color: #1a1a2e;
  font-size: 17px;
  font-weight: 700;
  border: none;
  cursor: pointer;
  border-radius: 999px;
  transition: transform .22s ease, box-shadow .22s ease, background .22s ease;
}
.submit-btn:hover:not(:disabled) { background: #fff; transform: scale(1.02); box-shadow: 0 16px 30px rgba(255, 255, 255, 0.16); }
.submit-btn:disabled { opacity: .5; cursor: not-allowed; }

.hint-box {
  padding: 14px 16px;
  border-left: 4px solid var(--web-accent-3);
  background: rgba(255, 255, 255, 0.07);
  border-radius: var(--web-radius);
}
.hint-box span { display: block; font-size: 11px; letter-spacing: .12em; color: rgba(255, 255, 255, 0.58); }
.hint-box strong { display: block; margin-top: 4px; font-size: 24px; color: #fff; text-shadow: 0 0 10px rgba(255, 255, 255, 0.12); }

.msg { margin: 0; font-size: 14px; color: rgba(255, 214, 178, 0.96); }
.msg.error { color: rgba(255, 120, 100, 0.9); }

.forgot-link { margin: 0; font-size: 14px; text-align: right; }
.forgot-link a { color: rgba(255, 255, 255, 0.58); }
.forgot-link a:hover { color: rgba(255, 255, 255, 0.92); }

.back-link { margin: 0; font-size: 14px; }
.back-link a { color: rgba(255, 255, 255, 0.58); }
.back-link a:hover { color: rgba(255, 255, 255, 0.92); }

/* 忘记密码弹窗 */
.modal-overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,.5);
  display: flex; align-items: center; justify-content: center;
  z-index: 100;
}
.modal-card {
  width: min(400px, 90vw);
  background: rgba(16, 20, 34, 0.94);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: var(--web-radius);
  padding: 32px;
  display: flex; flex-direction: column; gap: 16px;
  box-shadow: 0 12px 40px rgba(0,0,0,.4);
}
.modal-card h3 { margin: 0; font-size: 20px; color: rgba(255, 255, 255, 0.96); }

@media (max-width: 700px) {
  .auth-card { grid-template-columns: 1fr; }
  .auth-intro { border-right: none; border-bottom: 1px solid rgba(255, 255, 255, 0.08); padding: 36px 28px; }
  .auth-form { padding: 36px 28px; }
  .auth-intro h1 { font-size: 32px; }
}
</style>
