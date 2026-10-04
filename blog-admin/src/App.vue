<template>
  <div v-if="auth.initialized" class="admin-shell" :class="{'no-sidebar': isLogin}">
    <a v-if="!isLogin" class="skip-link" href="#admin-main">跳到内容</a>
    <aside v-if="!isLogin" class="sidebar">
      <router-link class="sidebar-brand" to="/dashboard" aria-label="Javerry 博客工作台">
        <span class="brand-mark"><img v-if="publicBlogUrl && !logoFailed" :src="publicBlogUrl + 'img/site-logo.jpg'" alt="" @error="logoFailed = true"/><span v-else>J</span></span>
        <span><strong>{{ adminTheme.brand.name }}</strong><small>{{ adminTheme.brand.subtitle }}</small></span>
      </router-link>
      <router-link class="sidebar-compose" to="/article/new"><el-icon><EditPen/></el-icon>写文章</router-link>
      <AdminNavigation/>
      <div class="sidebar-storage"><span class="storage-indicator"></span><div><strong>Telegram 图床</strong><small>Cloudflare ImgBed · 后端接入</small></div></div>
      <router-link v-if="auth.user" to="/account" class="sidebar-user" title="编辑博主资料">
        <div class="user-avatar"><img v-if="auth.user.avatar" :src="auth.user.avatar" alt="博主头像"/><span v-else>{{ ownerName.slice(0, 1) }}</span></div>
        <div class="user-info"><strong>{{ ownerName }}</strong><small>唯一博主</small></div>
        <el-icon><Setting/></el-icon>
      </router-link>
    </aside>

    <div class="admin-workspace">
      <header v-if="!isLogin" class="workspace-header">
        <button type="button" class="icon-button mobile-menu-toggle" aria-label="打开管理导航" :aria-expanded="menuOpen" @click="menuOpen = true"><el-icon><Expand/></el-icon></button>
        <div class="workspace-breadcrumb"><span>博客管理</span><el-icon><ArrowRight/></el-icon><strong>{{ route.meta.title || '工作台' }}</strong></div>
        <div class="workspace-actions">
          <a v-if="publicBlogUrl" :href="publicBlogUrl" target="_blank" rel="noopener noreferrer" class="visit-blog"><el-icon><TopRight/></el-icon><span>查看博客</span></a>
          <button type="button" class="icon-button" :aria-label="currentTheme === 'dark' ? '切换浅色模式' : '切换深色模式'" @click="toggleTheme"><el-icon><Sunny v-if="currentTheme === 'dark'"/><Moon v-else/></el-icon></button>
          <button v-if="auth.isLoggedIn" type="button" class="text-action" @click="handleLogout">退出</button>
        </div>
      </header>
      <main id="admin-main" class="main-content" tabindex="-1">
        <PublicationNotice v-if="!isLogin && auth.isLoggedIn"/>
        <router-view v-slot="{Component}"><transition name="page-fade" mode="out-in"><component :is="Component" :key="route.path"/></transition></router-view>
      </main>
    </div>
    <el-drawer v-if="!isLogin" v-model="menuOpen" title="博客管理" direction="ltr" size="280px">
      <router-link class="sidebar-compose" to="/article/new" @click="menuOpen = false"><el-icon><EditPen/></el-icon>写文章</router-link>
      <AdminNavigation @navigate="menuOpen = false"/>
      <router-link to="/account" class="drawer-owner" @click="menuOpen = false">博主设置 · {{ ownerName }}</router-link>
    </el-drawer>
  </div>
  <div v-else class="init-loader" role="status" aria-label="正在验证管理会话"><span class="init-spinner"></span></div>
</template>

<script setup>
import {computed, onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {EditPen, Setting, Expand, ArrowRight, TopRight, Sunny, Moon} from '@element-plus/icons-vue'
import {useAdminAuthStore} from './stores/auth'
import {adminTheme} from './config/theme'
import {publicBlogUrl} from './config/blog'
import AdminNavigation from './components/AdminNavigation.vue'
import PublicationNotice from './components/PublicationNotice.vue'
import './styles/admin.css'

const router = useRouter(), route = useRoute(), auth = useAdminAuthStore()
const menuOpen = ref(false), logoFailed = ref(false), currentTheme = ref('light')
const isLogin = computed(() => route.path === '/login')
const ownerName = computed(() => auth.user?.nickname || auth.user?.username || '博主')
const applyTheme = theme => {
  currentTheme.value = theme === 'dark' ? 'dark' : 'light'
  document.documentElement.setAttribute('data-admin-theme', currentTheme.value)
  document.documentElement.classList.toggle('dark', currentTheme.value === 'dark')
  localStorage.setItem('admin_theme', currentTheme.value)
}
const toggleTheme = () => applyTheme(currentTheme.value === 'dark' ? 'light' : 'dark')
const handleLogout = async () => {
  try { await auth.logout() } finally { menuOpen.value = false; router.push('/login') }
}
watch(() => route.fullPath, () => {
  menuOpen.value = false
  document.title = `${route.meta.title || '博客管理'} · Javerry`
}, {immediate: true})
onMounted(() => {
  applyTheme(localStorage.getItem('admin_theme') || 'light')
  if (auth.isLoggedIn) auth.fetchMe().catch(() => router.push('/login'))
  else auth.initialized = true
})
</script>
