<template>
  <section class="page" v-if="auth.user">
    <header class="profile-hero">
      <router-link class="back-link" to="/dashboard">&larr; 返回仪表盘</router-link>
      <p class="eyebrow">ACCOUNT SETTINGS</p>
      <h1>博主设置</h1>
      <p class="sub">统一管理博主头像、昵称、邮箱与密码。</p>
      <div class="hero-line"></div>
    </header>

    <div class="profile-card">
      <div class="avatar-section">
        <div class="avatar-wrap">
          <img v-if="previewUrl" :src="previewUrl" class="avatar-img" alt="当前头像"/>
          <span v-else class="avatar-placeholder">{{ avatarLetter }}</span>
        </div>
        <button class="ghost-btn" type="button" :disabled="uploadingAvatar || saving" @click="avatarInput.click()">
          {{ uploadingAvatar ? '上传中…' : '选择头像' }}
        </button>
        <button v-if="uploadedAvatar" class="ghost-btn" type="button" :disabled="uploadingAvatar || saving"
                @click="discardAvatar">撤销更换
        </button>
        <input id="profile-avatar" ref="avatarInput" type="file" accept="image/jpeg,image/png,image/gif,image/webp"
               hidden
               :disabled="uploadingAvatar || saving" @change="changeAvatar"/>
        <p class="avatar-hint">JPEG / PNG / GIF / WebP，最大 2 MiB；上传后点击保存修改。</p>
      </div>

      <div class="fields">
        <div class="field">
          <label for="profile-phone">手机号</label>
          <input id="profile-phone" :value="auth.user.phone" disabled/>
          <span class="field-note">手机号不可修改</span>
        </div>
        <div class="field">
          <label for="profile-username">用户名</label>
          <input id="profile-username" :value="auth.user.username" disabled/>
        </div>
        <div class="field">
          <label for="profile-nickname">昵称</label>
          <input id="profile-nickname" v-model="form.nickname" placeholder="设置昵称"/>
        </div>
        <div class="field">
          <label for="profile-email">邮箱</label>
          <input
              id="profile-email"
              v-model="form.email"
              type="email"
              placeholder="your@email.com"
          />
        </div>
      </div>

      <div class="actions">
        <button class="primary-btn" type="button" @click="save" :disabled="saving || uploadingAvatar">
          {{ saving ? '保存中...' : '保存修改' }}
        </button>
      </div>

      <p v-if="msg" class="msg" :class="{ error: isError }" role="status">{{ msg }}</p>

      <hr class="divider"/>

      <h3 class="section-title">修改密码</h3>
      <div class="fields">
        <div class="field">
          <label for="profile-old-password">原密码</label>
          <input
              id="profile-old-password"
              v-model="pwdForm.oldPassword"
              type="password"
              autocomplete="current-password"
              placeholder="输入当前密码"
          />
        </div>
        <div class="field">
          <label for="profile-new-password">新密码</label>
          <input
              id="profile-new-password"
              v-model="pwdForm.newPassword"
              type="password"
              autocomplete="new-password"
              placeholder="8-64 位新密码" minlength="8" maxlength="64"
          />
        </div>
      </div>
      <div class="actions">
        <button class="ghost-btn" @click="changePwd" :disabled="changingPwd">
          {{ changingPwd ? '修改中...' : '修改密码' }}
        </button>
      </div>
      <p v-if="pwdMsg" class="msg" :class="{ error: pwdIsError }" role="status">{{ pwdMsg }}</p>
    </div>
  </section>

  <section class="page state-box" v-else>
    <p class="state-text">请先登录</p>
    <router-link to="/login" class="login-link">前往登录</router-link>
  </section>
</template>

<script setup>
import {computed, reactive, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {changePassword, updateProfile, uploadAvatar} from '../api'
import {useAdminAuthStore} from '../stores/auth'

const auth = useAdminAuthStore()
const router = useRouter()
const saving = ref(false)
const changingPwd = ref(false)
const uploadedAvatar = ref('')
const uploadingAvatar = ref(false)
const avatarInput = ref(null)
const previewUrl = computed(() => uploadedAvatar.value || auth.user?.avatar || null)
const msg = ref('')
const isError = ref(false)
const pwdMsg = ref('')
const pwdIsError = ref(false)

const form = reactive({nickname: '', email: ''})
const pwdForm = reactive({oldPassword: '', newPassword: ''})

const changeAvatar = async (event) => {
  const input = event.target
  const file = input.files?.[0]
  if (!file || uploadingAvatar.value) return
  if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type) || file.size === 0 || file.size > 2 * 1024 * 1024) {
    msg.value = '请选择非空且 2 MiB 以内的 JPEG / PNG / GIF / WebP 图片'
    isError.value = true
    input.value = ''
    return
  }
  uploadingAvatar.value = true
  try {
    const image = await uploadAvatar(file)
    uploadedAvatar.value = image.url
    msg.value = '头像已上传，请保存修改；原头像不会自动删除'
    isError.value = false
  } catch (error) {
    msg.value = error.message
    isError.value = true
  } finally {
    uploadingAvatar.value = false
    input.value = ''
  }
}

const discardAvatar = () => {
  uploadedAvatar.value = ''
  msg.value = '已撤销头像更换；已上传图片不会自动删除'
  isError.value = false
}

const avatarLetter = computed(() => {
  const name = auth.user?.nickname || auth.user?.username || 'U'
  return name.charAt(0).toUpperCase()
})

watch(() => auth.user, (user) => {
  if (user) {
    form.nickname = user.nickname || ''
    form.email = user.email || ''
  }
}, {immediate: true})

const changePwd = async () => {
  if (changingPwd.value) return
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    pwdMsg.value = '请填写原密码和新密码'
    pwdIsError.value = true
    return
  }
  changingPwd.value = true
  try {
    await changePassword({oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword})
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdMsg.value = '密码已修改，请重新登录'
    auth.clear()
    router.push('/login')
    pwdIsError.value = false
  } catch (e) {
    pwdMsg.value = e.message
    pwdIsError.value = true
  } finally {
    changingPwd.value = false
  }
}

const save = async () => {
  if (saving.value || uploadingAvatar.value) return
  saving.value = true
  try {
    const updated = await updateProfile({
      nickname: form.nickname || null,
      email: form.email || null,
      ...(uploadedAvatar.value ? {avatar: uploadedAvatar.value} : {}),
    })
    auth.user = updated
    uploadedAvatar.value = ''
    msg.value = '博主资料已更新'
    isError.value = false
  } catch (e) {
    msg.value = e.message
    isError.value = true
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.page {
  max-width: 760px;
  margin: 0 auto;
  padding: 40px 24px 64px;
}

.profile-hero {
  margin-bottom: 30px;
}

.back-link {
  display: inline-block;
  color: var(--admin-muted);
  margin-bottom: 16px;
  font-size: 14px;
}

.back-link:hover {
  color: var(--admin-accent);
}

.profile-hero h1 {
  margin: 8px 0;
  font-size: 36px;
}

.sub {
  margin: 0;
  color: var(--admin-muted);
}

.hero-line {
  display: none;
}

.profile-card {
  padding: 32px;
  background: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: 16px;
  box-shadow: var(--admin-shadow);
}

.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-bottom: 24px;
  margin-bottom: 28px;
  border-bottom: 1px solid var(--admin-line);
}

.avatar-wrap {
  position: relative;
  width: 84px;
  height: 84px;
  border-radius: 50%;
  overflow: hidden;
  border: 3px solid var(--admin-line);
  display: grid;
  place-items: center;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  background: #425aef;
  color: white;
  font-size: 30px;
  font-weight: 700;
}

.avatar-hint,
.field-note {
  color: var(--admin-muted);
  font-size: 12px;
}

.fields {
  display: grid;
  gap: 18px;
}

.field {
  display: grid;
  gap: 6px;
}

.field label {
  font-size: 14px;
}

.field input {
  width: 100%;
}

.field input:disabled {
  opacity: 0.65;
}

.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 24px;
}

.msg {
  margin: 16px 0 0;
  font-size: 14px;
  color: var(--admin-accent);
}

.msg.error {
  color: var(--admin-danger);
}

.divider {
  border: 0;
  border-top: 1px solid var(--admin-line);
  margin: 32px 0;
}

.section-title {
  margin: 0 0 20px;
  font-size: 20px;
}

.state-box {
  text-align: center;
  padding: 80px 24px;
}

.login-link {
  color: var(--admin-accent);
}

@media (max-width: 500px) {
  .page {
    padding: 24px 14px 40px;
  }

  .profile-card {
    padding: 24px 18px;
  }
}

.field input {
  padding: 11px 13px;
  background: var(--admin-input-bg);
  color: var(--admin-text);
  border: 1px solid var(--admin-line-strong);
  border-radius: 8px;
  font: inherit;
}

.primary-btn, .ghost-btn {
  padding: 10px 16px;
  border: 1px solid var(--admin-line-strong);
  border-radius: 8px;
  color: var(--admin-text);
  background: var(--admin-bg);
}

.primary-btn {
  background: var(--admin-accent);
  color: white;
}

button:disabled {
  opacity: .6;
  cursor: not-allowed;
}
</style>
