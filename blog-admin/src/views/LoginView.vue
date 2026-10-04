<template>
  <section class="page" :style="pageStyle">
    <div class="login-card">
      <div class="card-header">
        <span class="header-icon">&#9733;</span>
        <span class="header-text">{{ adminTheme.brand.subtitle }}</span>
      </div>

      <div class="card-body">

        <div class="field-stack">
          <label>博主账号</label>
          <el-input :model-value="phone" readonly autocomplete="username" size="large"/>
        </div>


        <div class="field-stack">
          <label>密码</label>
          <el-input v-model="pwd" type="password" autocomplete="current-password" show-password
                    placeholder="输入博主密码" size="large" @keyup.enter="doLogin"/>
        </div>

        <el-button type="primary" size="large" @click="doLogin" :loading="loading" class="login-btn">
          登录控制台
        </el-button>

        <p v-if="msg" class="msg" :class="{ error: isError }">{{ msg }}</p>
      </div>
    </div>
  </section>
</template>

<script setup>
import {ref} from 'vue'
import {useRouter} from 'vue-router'
import {useAdminAuthStore} from '../stores/auth'
import {adminTheme} from '../config/theme'
import {adminAssets} from '../config/assets'

const router = useRouter()
const auth = useAdminAuthStore()
const assets = adminAssets
const pageStyle = {
  '--admin-login-bg': `url(${assets.backgrounds.login})`
}

const phone = import.meta.env.VITE_BLOG_OWNER_PHONE || '19140838906'
const pwd = ref('')
const msg = ref('')
const isError = ref(false)
const loading = ref(false)
const doLogin = async () => {
  msg.value = '';
  isError.value = false
  loading.value = true
  try {
    if (!pwd.value) {
      msg.value = '请输入密码';
      isError.value = true;
      return
    }
    await auth.loginByPassword(phone, pwd.value)
    pwd.value = ''
    router.push('/')
  } catch (e) {
    msg.value = e.message;
    isError.value = true
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
}

.login-card {
  width: min(420px, 100%);
  background: var(--admin-login-bg) center / cover no-repeat;
  background-color: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow-lg);
  overflow: hidden;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 28px;
  border-bottom: 1px solid var(--admin-line);
  background: linear-gradient(135deg, rgba(108, 159, 212, 0.14), rgba(240, 168, 184, 0.10));
}

.header-icon {
  font-size: 18px;
  color: var(--admin-accent-3);
  text-shadow: 0 0 10px rgba(240, 168, 184, 0.4);
}

.header-text {
  font-size: 15px;
  font-weight: 800;
  color: var(--admin-text);
  letter-spacing: .04em;
}

.card-body {
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.tab-row {
  display: flex;
  gap: 8px;
}

.tab-btn {
  flex: 1;
  padding: 10px;
  font-size: 14px;
  border: 1px solid var(--admin-line);
  background: var(--admin-bg);
  color: var(--admin-muted);
  cursor: pointer;
  border-radius: 8px;
  transition: all .2s;
}

.tab-btn.active {
  background: var(--admin-accent);
  color: #fff;
  border-color: var(--admin-accent);
  font-weight: 800;
  box-shadow: 0 10px 22px rgba(108, 159, 212, 0.18);
}

.tab-btn:not(.active):hover {
  border-color: var(--admin-accent);
  color: var(--admin-accent);
}

.field-stack {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-stack label {
  font-size: 13px;
  color: var(--admin-muted);
  font-weight: 600;
}

.code-row {
  display: flex;
  gap: 10px;
}

.code-row > :first-child {
  flex: 1;
}

.send-btn {
  white-space: nowrap;
}

.login-btn {
  width: 100%;
}

.hint-box {
  padding: 12px 16px;
  border-left: 4px solid var(--admin-accent);
  background: var(--admin-soft-accent);
  border-radius: 8px;
}

.hint-box span {
  display: block;
  font-size: 11px;
  color: var(--admin-muted);
  letter-spacing: .08em;
}

.hint-box strong {
  display: block;
  margin-top: 4px;
  font-size: 24px;
  color: var(--admin-accent);
  text-shadow: 0 0 10px rgba(108, 159, 212, 0.15);
}

.msg {
  margin: 0;
  font-size: 14px;
  color: var(--admin-success);
}

.msg.error {
  color: var(--admin-danger);
}
</style>
