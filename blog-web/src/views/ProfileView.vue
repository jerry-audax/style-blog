<template>
  <section class="page" v-if="auth.user">
    <header class="profile-hero">
      <router-link class="back-link" to="/">&larr; 返回首页</router-link>
      <p class="eyebrow">ACCOUNT SETTINGS</p>
      <h1>个人信息</h1>
      <p class="sub">管理你的账户资料与偏好设置。</p>
      <div class="hero-line"></div>
    </header>

    <div class="profile-card">
      <div class="avatar-section">
        <label class="avatar-wrap" :class="{ uploading: uploading }">
          <img v-if="previewUrl" :src="previewUrl" class="avatar-img" alt="avatar" />
          <span v-else class="avatar-placeholder">{{ avatarLetter }}</span>
          <span class="avatar-overlay">
            <span v-if="uploading">...</span>
            <span v-else>&#x1F4F7;</span>
          </span>
          <input type="file" accept="image/*" hidden @change="onFileChange" />
        </label>
        <p class="avatar-hint" v-if="!uploading">点击更换头像</p>
        <p class="avatar-hint" v-else>上传中...</p>
      </div>

      <div class="fields">
        <div class="field">
          <label>手机号</label>
          <input :value="auth.user.phone" disabled />
          <span class="field-note">手机号不可修改</span>
        </div>
        <div class="field">
          <label>用户名</label>
          <input :value="auth.user.username" disabled />
        </div>
        <div class="field">
          <label>昵称</label>
          <input v-model="form.nickname" placeholder="设置昵称" />
        </div>
        <div class="field">
          <label>邮箱</label>
          <input v-model="form.email" type="email" placeholder="your@email.com" />
        </div>
      </div>

      <div class="actions">
        <button class="primary-btn" @click="save" :disabled="saving">
          {{ saving ? '保存中...' : '保存修改' }}
        </button>
      </div>

      <p v-if="msg" class="msg" :class="{ error: isError }">{{ msg }}</p>

      <hr class="divider" />

      <h3 class="section-title">修改密码</h3>
      <div class="fields">
        <div class="field">
          <label>原密码</label>
          <input v-model="pwdForm.oldPassword" type="password" placeholder="输入当前密码" />
        </div>
        <div class="field">
          <label>新密码</label>
          <input v-model="pwdForm.newPassword" type="password" placeholder="输入新密码" />
        </div>
      </div>
      <div class="actions">
        <button class="ghost-btn" @click="changePwd" :disabled="changingPwd">
          {{ changingPwd ? '修改中...' : '修改密码' }}
        </button>
      </div>
      <p v-if="pwdMsg" class="msg" :class="{ error: pwdIsError }">{{ pwdMsg }}</p>
    </div>
  </section>

  <section class="page state-box" v-else>
    <p class="state-text">请先登录</p>
    <router-link to="/login?redirect=/profile" class="login-link">前往登录</router-link>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword, updateProfile, uploadFile } from '../api'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const saving = ref(false)
const changingPwd = ref(false)
const uploading = ref(false)
const previewUrl = ref(null)
const msg = ref('')
const isError = ref(false)
const pwdMsg = ref('')
const pwdIsError = ref(false)

const form = reactive({ nickname: '', email: '', avatar: null })
const pwdForm = reactive({ oldPassword: '', newPassword: '' })

const avatarLetter = computed(() => {
  const name = auth.user?.nickname || auth.user?.username || 'U'
  return name.charAt(0).toUpperCase()
})

const onFileChange = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  previewUrl.value = URL.createObjectURL(file)
  uploading.value = true
  try {
    const res = await uploadFile(file)
    form.avatar = res.url
  } catch (e) {
    msg.value = e.message || '上传失败'
    isError.value = true
    previewUrl.value = null
  } finally {
    uploading.value = false
  }
}

onMounted(() => {
  if (auth.user) {
    form.nickname = auth.user.nickname || ''
    form.email = auth.user.email || ''
    form.avatar = auth.user.avatar || null
    if (auth.user.avatar) previewUrl.value = auth.user.avatar
  }
})

const changePwd = async () => {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    pwdMsg.value = '请填写原密码和新密码'
    pwdIsError.value = true
    return
  }
  changingPwd.value = true
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdMsg.value = '密码已修改'
    pwdIsError.value = false
  } catch (e) {
    pwdMsg.value = e.message
    pwdIsError.value = true
  } finally {
    changingPwd.value = false
  }
}

const save = async () => {
  saving.value = true
  try {
    const updated = await updateProfile({
      nickname: form.nickname || null,
      email: form.email || null,
      avatar: form.avatar || null
    })
    auth.user = updated
    msg.value = '个人信息已更新'
    isError.value = false
    setTimeout(() => router.push('/'), 600)
  } catch (e) {
    msg.value = e.message
    isError.value = true
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.page { max-width: 720px; margin: 0 auto; padding: 56px 24px 72px; }

.profile-hero { margin-bottom: 36px; }
.back-link {
  display: inline-flex;
  margin-bottom: 18px;
  color: rgba(255, 255, 255, 0.7);
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
.profile-hero h1 {
  margin: 0;
  font-family: var(--web-font-display);
  font-size: clamp(38px, 5vw, 56px);
  line-height: 1.1;
  letter-spacing: .03em;
  color: rgba(255, 255, 255, 0.98);
  text-shadow: 0 4px 18px rgba(0, 0, 0, 0.38);
}
.sub { margin: 12px 0 0; color: rgba(255, 255, 255, 0.72); font-size: 16px; }
.hero-line { margin-top: 24px; width: 48px; height: 4px; border-radius: 2px; background: linear-gradient(90deg, var(--web-accent-3), var(--web-accent)); }

.profile-card {
  padding: 36px 32px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--web-radius);
  background: rgba(0, 0, 0, 0.3); backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
  box-shadow: var(--web-shadow);
}

.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 32px;
  padding-bottom: 28px;
  border-bottom: 1px solid var(--web-line);
}
.avatar-wrap {
  position: relative;
  width: 80px; height: 80px;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 3px solid var(--web-line);
  transition: border-color .2s;
}
.avatar-wrap:hover { border-color: var(--web-accent-3); }
.avatar-wrap.uploading { pointer-events: none; opacity: .7; }
.avatar-img {
  width: 100%; height: 100%;
  object-fit: cover;
}
.avatar-placeholder {
  width: 100%; height: 100%;
  background: linear-gradient(135deg, var(--web-accent), var(--web-accent-3));
  color: #fff; font-size: 32px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, 0.15);
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 20px;
  opacity: 0;
  transition: opacity .22s;
}
.avatar-wrap:hover .avatar-overlay { opacity: 1; }
.avatar-hint { margin: 10px 0 0; color: rgba(255, 255, 255, 0.5); font-size: 13px; }

.fields { display: flex; flex-direction: column; gap: 18px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field label {
  font-size: 12px;
  letter-spacing: .1em;
  color: rgba(255, 255, 255, 0.5);
  font-weight: 600;
}
.field input {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.85);
  font-size: 15px;
  border-radius: var(--web-radius);
  transition: border-color .18s ease;
}
.field input:focus {
  outline: 2px solid rgba(74, 144, 217, 0.18);
  border-color: rgba(255, 255, 255, 0.7);
  box-shadow: 0 0 0 3px rgba(74, 144, 217, 0.06);
}
.field input:disabled {
  opacity: .55;
  background: rgba(255, 255, 255, 0.04);
  cursor: not-allowed;
}
.field-note { font-size: 12px; color: rgba(255, 255, 255, 0.5); }

.actions { margin-top: 28px; display: flex; justify-content: flex-end; }
.primary-btn {
  padding: 14px 28px;
  background: var(--web-accent-3);
  color: #fff;
  border: none;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  border-radius: 999px;
  transition: all .22s ease;
  box-shadow: 0 6px 20px rgba(240, 140, 160, 0.25);
}
.primary-btn:hover:not(:disabled) { background: #e47890; transform: scale(1.03); }
.primary-btn:disabled { opacity: .5; cursor: not-allowed; }

.divider { margin: 32px 0 24px; border: none; border-top: 1px solid var(--web-line); }
.section-title { margin: 0 0 16px; font-size: 18px; color: rgba(255, 255, 255, 0.96); }
.ghost-btn {
  padding: 12px 20px;
  background: rgba(255,255,255,0.7);
  color: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  border-radius: var(--web-radius);
  transition: all .18s ease;
}
.ghost-btn:hover:not(:disabled) { background: rgba(0,0,0,0.04); }
.ghost-btn:disabled { opacity: .5; cursor: not-allowed; }
.msg { margin: 16px 0 0; font-size: 14px; color: var(--web-accent-2); }
.msg.error { color: #c0392b; }

.state-box { text-align: center; padding: 120px 24px; }
.state-text { color: rgba(255, 255, 255, 0.5); font-size: 18px; }
.login-link { color: var(--web-accent-2); font-weight: 700; }
@media (max-width: 500px) {
  .page { padding: 32px 14px 48px; }
  .profile-card { padding: 24px 16px; }
  .profile-header { flex-direction: column; text-align: center; }
}
</style>
