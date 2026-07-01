<template>
  <div class="web-shell" v-if="auth.initialized" :style="shellStyle">
    <div class="web-glow web-glow-a"></div>
    <div class="web-glow web-glow-b"></div>

    <nav class="top-nav">
      <div class="nav-inner">
        <router-link to="/" class="nav-brand">
          <span class="brand-mark">&#9670;</span>
          <span class="brand-text">{{ theme.brand.name }}</span>
        </router-link>
        <div class="nav-links">
          <router-link to="/" class="nav-link">文章</router-link>
          <router-link to="/ai" class="nav-link nav-ai">AI 助手</router-link>
          <template v-if="auth.isLoggedIn">
            <router-link to="/profile" class="nav-user">
              <img v-if="auth.user?.avatar" :src="auth.user.avatar" class="nav-avatar" />
              <span>{{ auth.user?.nickname || auth.user?.username }}</span>
            </router-link>
            <button class="nav-btn" @click="auth.logout()">退出</button>
          </template>
          <router-link v-else to="/login" class="nav-btn nav-btn--accent">登录</router-link>
        </div>
        <button class="hamburger" @click="menuOpen = !menuOpen" :aria-label="menuOpen ? '关闭菜单' : '打开菜单'">
          <span :class="{ open: menuOpen }"></span>
        </button>
      </div>
    </nav>

    <!-- 移动端菜单 -->
    <Teleport to="body">
      <div class="mobile-menu-overlay" :class="{ show: menuOpen }" @click.self="menuOpen = false">
        <div class="mobile-menu">
          <button class="mobile-menu-close" @click="menuOpen = false">&times;</button>
          <router-link to="/" class="mobile-nav-link" @click="menuOpen = false">文章</router-link>
          <router-link to="/ai" class="mobile-nav-link" @click="menuOpen = false">AI 助手</router-link>
          <template v-if="auth.isLoggedIn">
            <router-link to="/profile" class="mobile-nav-link" @click="menuOpen = false">
              {{ auth.user?.nickname || auth.user?.username }}
            </router-link>
            <button class="mobile-nav-btn" @click="auth.logout(); menuOpen = false">退出登录</button>
          </template>
          <router-link v-else to="/login" class="mobile-nav-btn mobile-nav-btn--accent" @click="menuOpen = false">登录 / 注册</router-link>
        </div>
      </div>
    </Teleport>

    <main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <footer class="site-footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <span class="brand-mark">&#9670;</span>
          <span>{{ theme.brand.footer }}</span>
        </div>
        <p class="footer-copy">&copy; {{ year }} Jerry L-served.</p>
      </div>
    </footer>
  </div>

  <div class="init-loader" v-else>
    <span class="init-spinner"></span>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from './stores/auth'
import { webTheme } from './config/theme'
import { webAssets } from './config/assets'

const auth = useAuthStore()
const year = computed(() => new Date().getFullYear())
const menuOpen = ref(false)
const theme = webTheme
const assets = webAssets
const shellStyle = {
  '--web-home-bg': `url(${assets.backgrounds.home})`
}

onMounted(() => {
  auth.fetchMe()
})
</script>

<style>
:root {
  /* ---- Typography ---- */
  --web-font-display: "ZCOOL KuaiLe", "Noto Sans SC", sans-serif;
  --web-font-body: "Noto Sans SC", "Inter", -apple-system, sans-serif;

  /* ---- Colors ---- */
  --web-bg: #f0f5ff;
  --web-paper: #ffffff;
  --web-ink: #2c3e50;
  --web-muted: #7b8da0;
  --web-line: rgba(100, 140, 200, 0.15);
  --web-accent: #4a90d9;
  --web-accent-2: #5bb89e;
  --web-accent-3: #f08ca0;

  /* ---- Shadows ---- */
  --web-shadow: 0 4px 24px rgba(74, 144, 217, 0.10);
  --web-shadow-lg: 0 16px 48px rgba(74, 144, 217, 0.14);

  /* ---- Radius / Spacing ---- */
  --web-radius: 12px;

  /* ---- Typography ---- */
  font-family: var(--web-font-body);
  color: var(--web-ink);
  background: var(--web-bg);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  background: var(--web-bg);
}
.web-shell {
  background: var(--web-home-bg) center / cover no-repeat fixed;
}
body::before {
  content: '';
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 0;
  pointer-events: none;
}

a { color: inherit; text-decoration: none; }
button, input, select, textarea { font: inherit; color: inherit; }
img { max-width: 100%; display: block; }

.web-shell {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
}

/* ---- Cloud decorations ---- */
.web-glow {
  position: fixed;
  pointer-events: none;
  z-index: 0;
}
.web-glow-a {
  width: 260px; height: 90px;
  top: 8%; left: 5%;
  background: rgba(74, 144, 217, 0.10);
  border-radius: 90px;
  box-shadow:
    60px -30px 0 20px rgba(74, 144, 217, 0.08),
    140px -15px 0 10px rgba(74, 144, 217, 0.06);
}
.web-glow-a::after {
  content: '';
  position: absolute;
  width: 100px; height: 100px;
  right: -30px; top: -50px;
  background: radial-gradient(circle, rgba(240, 140, 160, 0.12) 0%, transparent 70%);
  border-radius: 50%;
}
.web-glow-b {
  width: 200px; height: 70px;
  right: 8%; top: 55%;
  background: rgba(91, 184, 158, 0.09);
  border-radius: 80px;
  box-shadow:
    -50px -25px 0 15px rgba(91, 184, 158, 0.07),
    -110px 10px 0 8px rgba(74, 144, 217, 0.05);
}

/* ---- Top Navigation ---- */
.top-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background:
    linear-gradient(180deg, rgba(12, 16, 28, 0.88), rgba(12, 16, 28, 0.64)),
    linear-gradient(90deg, rgba(255, 255, 255, 0.06), rgba(255, 255, 255, 0.02));
  backdrop-filter: blur(24px) saturate(1.6);
  -webkit-backdrop-filter: blur(24px) saturate(1.6);
  border-bottom: 1px solid rgba(255, 255, 255, 0.10);
  box-shadow:
    0 10px 30px rgba(0, 0, 0, 0.18),
    inset 0 -1px 0 rgba(255, 255, 255, 0.06);
}
.top-nav::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(circle at 12% 0%, rgba(240, 140, 160, 0.16), transparent 26%),
    radial-gradient(circle at 88% 0%, rgba(91, 184, 158, 0.12), transparent 24%);
  opacity: .9;
}
.nav-inner {
  position: relative;
  z-index: 1;
}
.nav-inner {
  max-width: 1040px;
  margin: 0 auto;
  padding: 0 24px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.nav-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  font-family: var(--web-font-display);
  font-size: 23px;
  color: rgba(243, 247, 255, 0.96);
  letter-spacing: .06em;
  text-shadow:
    0 2px 14px rgba(0, 0, 0, 0.40),
    0 0 1px rgba(255, 255, 255, 0.10);
  transition: transform .22s ease, opacity .22s ease;
}
.nav-brand:hover { transform: translateY(-1px) scale(1.02); opacity: .98; }
.brand-mark {
  font-size: 26px;
  line-height: 1;
  color: rgba(255, 173, 190, 0.95);
  text-shadow:
    0 0 14px rgba(240, 140, 160, 0.46),
    0 0 26px rgba(240, 140, 160, 0.14);
}
.nav-links { display: flex; align-items: center; gap: 14px; }
.nav-link {
  font-size: 14px;
  letter-spacing: .1em;
  color: rgba(232, 239, 248, 0.84);
  text-shadow: 0 1px 8px rgba(0, 0, 0, 0.24);
  transition: transform .2s ease, background .2s ease, color .2s ease, border-color .2s ease;
  padding: 7px 13px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.02);
}
.nav-link:hover, .nav-link.router-link-exact-active {
  color: rgba(250, 252, 255, 0.98);
  background: rgba(255, 255, 255, 0.10);
  border-color: rgba(255, 255, 255, 0.16);
  transform: translateY(-1px);
}
.nav-ai {
  background: rgba(240, 140, 160, 0.18);
  color: rgba(255, 181, 196, 0.95) !important;
  font-weight: 700;
  border-color: rgba(240, 140, 160, 0.18);
}
.nav-ai:hover, .nav-ai.router-link-exact-active {
  background: rgba(240, 140, 160, 0.26);
  color: rgba(255, 193, 206, 0.98) !important;
  border-color: rgba(240, 140, 160, 0.28);
}
.nav-user {
  display: flex; align-items: center; gap: 8px;
  font-size: 14px; color: rgba(236, 242, 250, 0.90);
  text-shadow: 0 1px 8px rgba(0, 0, 0, 0.20);
  max-width: 160px; cursor: pointer; transition: color .2s ease;
}
.nav-avatar {
  width: 28px; height: 28px; border-radius: 50%;
  object-fit: cover; border: 2px solid rgba(255, 255, 255, 0.26); flex-shrink: 0;
}
.nav-user:hover { color: rgba(250, 252, 255, 0.98); }
.nav-btn {
  padding: 8px 18px; font-size: 14px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: rgba(239, 244, 252, 0.94);
  text-shadow: 0 1px 8px rgba(0, 0, 0, 0.18);
  cursor: pointer; transition: transform .2s ease, background .2s ease, border-color .2s ease; border-radius: 999px;
}
.nav-btn:hover { background: rgba(255, 255, 255, 0.16); color: rgba(253, 254, 255, 0.99); transform: translateY(-1px); border-color: rgba(255, 255, 255, 0.26); }
.nav-btn--accent {
  background: linear-gradient(135deg, var(--web-accent-3), #e66f8b);
  color: #fff; border-color: rgba(255, 255, 255, 0.08);
}
.nav-btn--accent:hover { background: linear-gradient(135deg, #e66f8b, #d95d7a); transform: translateY(-1px); }

/* ---- Main Content ---- */
.main-content {
  flex: 1;
  position: relative;
  z-index: 1;
}

/* ---- Page Transitions ---- */
.page-fade-enter-active, .page-fade-leave-active {
  transition: opacity .28s ease, transform .28s ease;
}
.page-fade-enter-from {
  opacity: 0;
  transform: translateY(16px) scale(.98);
}
.page-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(.99);
}

/* ---- Footer ---- */
.site-footer {
  position: relative;
  z-index: 1;
  border-top: 1px solid var(--web-line);
  padding: 24px;
  margin-top: 64px;
  opacity: .85;
}
.footer-inner {
  max-width: 1040px;
  margin: 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 13px;
  color: var(--web-muted);
}
.footer-brand {
  display: flex;
  align-items: center;
  gap: 6px;
  color: rgba(255, 255, 255, 0.9);
}
.footer-brand .brand-mark {
  font-size: 16px;
  color: var(--web-accent-3);
  text-shadow: 0 0 10px rgba(240, 140, 160, 0.4);
}
.footer-copy { margin: 0; }

/* ---- Hamburguer Button ---- */
.hamburger {
  display: none;
  width: 40px; height: 40px;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.14);
  border-radius: 12px;
  cursor: pointer;
  position: relative;
  align-items: center;
  justify-content: center;
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.05);
}
.hamburger span,
.hamburger span::before,
.hamburger span::after {
  display: block; width: 20px; height: 2px;
  background: rgba(255,255,255,0.7);
  border-radius: 2px;
  transition: all .25s ease;
  position: absolute;
}
.hamburger span { top: 50%; transform: translateY(-50%); }
.hamburger span::before { content: ''; top: -6px; }
.hamburger span::after { content: ''; top: 6px; }
.hamburger span.open { background: transparent; }
.hamburger span.open::before { top: 0; transform: rotate(45deg); }
.hamburger span.open::after { top: 0; transform: rotate(-45deg); }

/* ---- Mobile Menu ---- */
.mobile-menu-overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.6);
  backdrop-filter: blur(8px);
  z-index: 200;
  opacity: 0; visibility: hidden;
  transition: all .3s ease;
}
.mobile-menu-overlay.show { opacity: 1; visibility: visible; }
.mobile-menu {
  position: fixed; top: 0; right: 0; bottom: 0;
  width: min(280px, 80vw);
  background: linear-gradient(180deg, rgba(13, 16, 28, 0.98), rgba(20, 20, 40, 0.96));
  backdrop-filter: blur(22px);
  padding: 22px;
  display: flex; flex-direction: column; gap: 8px;
  transform: translateX(100%);
  transition: transform .3s ease;
  box-shadow: -24px 0 48px rgba(0, 0, 0, 0.28);
}
.mobile-menu-overlay.show .mobile-menu { transform: translateX(0); }
.mobile-menu-close {
  align-self: flex-end; background: none; border: none;
  color: rgba(255,255,255,0.7); font-size: 28px;
  cursor: pointer; padding: 0; line-height: 1;
}
.mobile-nav-link {
  display: block; padding: 14px 16px; border-radius: 10px;
  color: rgba(236, 242, 250, 0.88); font-size: 17px; letter-spacing: .06em;
  text-shadow: 0 1px 8px rgba(0, 0, 0, 0.20);
  transition: background .2s, color .2s, transform .2s;
}
.mobile-nav-link:hover { background: rgba(255,255,255,0.08); color: rgba(250,252,255,0.98); transform: translateX(2px); }
.mobile-nav-btn {
  padding: 14px 16px; border-radius: 10px; border: 1px solid rgba(255,255,255,0.12);
  background: rgba(255,255,255,0.06); color: rgba(239,244,252,0.90);
  font-size: 16px; cursor: pointer; text-align: center; transition: background .2s, color .2s, transform .2s;
}
.mobile-nav-btn:hover { background: rgba(255,255,255,0.12); transform: translateX(2px); }
.mobile-nav-btn--accent { background: linear-gradient(135deg, var(--web-accent-3), #e66f8b); border-color: transparent; color: #fff; }

.nav-brand,
.footer-brand,
.hero h1,
.section-head h2,
.card h2,
.hot-card h3,
.profile-hero h1,
.article-hero h1,
.welcome h3,
.state-title,
.comment-head h2,
.page-title,
.section-title {
  font-family: var(--web-font-display);
}

@media (max-width: 640px) {
  .nav-links { display: none; }
  .hamburger { display: flex; }
  .nav-inner { height: 52px; padding: 0 14px; }
  .brand-text { display: none; }
}

@media (max-width: 720px) {
  .nav-inner { height: 52px; }
  .nav-brand .brand-text { display: none; }
  .footer-inner { flex-direction: column; text-align: center; }
}

/* ---- Init loader ---- */
.init-loader {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--web-bg);
  z-index: 999;
}
.init-spinner {
  width: 36px;
  height: 36px;
  border: 3px solid var(--web-line);
  border-top-color: var(--web-accent-3);
  border-radius: 50%;
  animation: init-spin 0.7s linear infinite;
}
@keyframes init-spin {
  to { transform: rotate(360deg); }
}
</style>
