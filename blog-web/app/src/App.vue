<template>
  <div class="web-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="site-header">
      <nav class="nav-inner" aria-label="主导航">
        <a class="nav-brand" href="/blog/"><span class="brand-mark">J.</span>Javerry</a>
        <div class="nav-links">
          <BlogNavigation/>
        </div>
        <div class="nav-tools">
          <button
              class="icon-btn"
              :aria-label="dark ? '切换浅色模式' : '切换深色模式'"
              @click="toggleTheme"
          >
            <SiteIcon :name="dark ? 'sun' : 'moon'"/>
          </button
          >
          <button
              class="icon-btn mobile-toggle"
              aria-label="打开导航菜单"
              @click="menu.showModal()"
          >
            <SiteIcon name="menu" :size="24"/>
          </button>
        </div>
      </nav>
    </header>
    <main id="main-content" tabindex="-1">
      <router-view/>
    </main>
    <footer class="site-footer">
      <div class="footer-inner">
        <div>
          <strong>Javerry</strong>
          <p>记录技术，分享思考，保持好奇。</p>
        </div>
        <span>© {{ new Date().getFullYear() }} Javerry</span>
      </div>
    </footer>
    <dialog ref="menu">
      <div class="menu-heading">
        <strong>Javerry</strong
        >
        <button class="icon-btn" aria-label="关闭导航菜单" @click="menu.close()">
          <SiteIcon name="close"/>
        </button>
      </div>
      <nav aria-label="移动端导航">
        <BlogNavigation mobile/>
      </nav>
    </dialog>
  </div>
</template>
<script setup>
import {ref, watch, onMounted, onUnmounted} from 'vue'
import {useRoute} from 'vue-router'
import SiteIcon from './components/SiteIcon.vue'
import BlogNavigation from './components/BlogNavigation.vue'

const route = useRoute()
const menu = ref(null)
const system = window.matchMedia('(prefers-color-scheme: dark)')
let preference
try {
  preference = localStorage.getItem('javerry-theme')
} catch {
  /* in-memory fallback */
}
const dark = ref(preference ? preference === 'dark' : system.matches)
watch(
    dark,
    (value) => {
      document.documentElement.dataset.theme = value ? 'dark' : 'light'
    },
    {immediate: true},
)
const toggleTheme = () => {
  dark.value = !dark.value
  preference = dark.value ? 'dark' : 'light'
  try {
    localStorage.setItem('javerry-theme', preference)
  } catch {
    /* keep in-memory preference */
  }
}
const onThemeChange = (event) => {
  if (!preference) dark.value = event.matches
}
watch(
    () => route.fullPath,
    () => {
      menu.value?.close()
      document.title = (route.meta.title || '博客') + ' · Javerry'
    },
    {immediate: true},
)
onMounted(() => {
  system.addEventListener('change', onThemeChange)
})
onUnmounted(() => system.removeEventListener('change', onThemeChange))
</script>
<style scoped>
.web-shell {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
}

.skip-link {
  position: fixed;
  top: -60px;
  left: 20px;
  padding: 12px 20px;
  background: var(--web-accent);
  color: white;
  z-index: 100;
  border-radius: 8px;
}

.skip-link:focus {
  top: 8px;
}

.site-header {
  position: sticky;
  top: 0;
  height: 76px;
  z-index: 20;
  border-bottom: 1px solid var(--web-line);
  background: var(--web-paper);
}

.nav-inner {
  width: min(1220px, calc(100% - 64px));
  margin: auto;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.5px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 35px;
  height: 35px;
  color: white;
  background: #425aef;
  border-radius: 11px;
}

.nav-links,
.nav-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nav-links a {
  padding: 8px 14px;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 600;
}

.nav-links a:hover,
.nav-links a.active {
  color: var(--web-accent);
  background: var(--web-tint);
}

.account-link {
  max-width: 110px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  padding: 8px 10px;
}

.logout-btn {
  background: transparent;
  border: 0;
  color: var(--web-muted);
  font-size: 12px;
}

.mobile-toggle {
  display: none;
}

main {
  flex: 1;
}

main:focus {
  outline: none;
}

.site-footer {
  background: var(--web-paper);
  border-top: 1px solid var(--web-line);
  padding: 30px 0;
}

.footer-inner {
  width: min(1220px, calc(100% - 64px));
  margin: auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  font-size: 12px;
  color: var(--web-muted);
}

.footer-inner strong {
  font-size: 18px;
  color: var(--web-ink);
}

.footer-inner p {
  margin: 5px 0 0;
}

.menu-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
  font-size: 20px;
}

dialog nav {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

dialog nav a {
  display: block;
  padding: 12px;
  background: var(--web-soft);
  border-radius: 10px;
}

@media (max-width: 760px) {
  .site-header {
    height: 64px;
  }

  .nav-inner,
  .footer-inner {
    width: calc(100% - 28px);
  }

  .nav-links,
  .logout-btn {
    display: none;
  }

  .mobile-toggle {
    display: inline-flex;
  }

  .nav-tools {
    gap: 2px;
  }

  .nav-brand {
    font-size: 20px;
    gap: 7px;
  }

  .footer-inner {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
