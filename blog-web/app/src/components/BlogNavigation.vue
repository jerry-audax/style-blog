<template>
  <div class="site-navigation" :class="{ 'is-mobile': mobile }">
    <details
        ref="group"
        class="blog-navigation"
        :class="{ 'is-mobile': mobile }"
        @keydown.esc="onEscape"
    >
      <summary><a href="/blog/">主页</a>
        <SiteIcon name="down" :size="16"/>
      </summary>
      <nav aria-label="主页子导航">
        <a href="/blog/archives/">归档</a>
        <a href="/blog/categories/">分类</a>
        <a href="/blog/tags/">标签</a>
      </nav>
    </details>
    <a class="site-link" href="/blog/#recent-posts">最新文章</a>
    <a class="site-link" href="/blog/music/">音乐</a>
    <a class="site-link" href="/blog/surprise/">惊喜</a>
    <a class="site-link" href="/blog/about/">关于</a>
  </div>
</template>
<script setup>
import {ref, onMounted, onUnmounted} from 'vue'
import SiteIcon from './SiteIcon.vue'

defineProps({mobile: Boolean})
const group = ref(null)
const close = () => {
  group.value.open = false
  group.value.querySelector('summary').focus()
}
const onEscape = (event) => {
  if (!group.value.open) return
  event.preventDefault()
  event.stopPropagation()
  close()
}
const onOutsidePointer = (event) => {
  if (!group.value.contains(event.target)) group.value.open = false
}
onMounted(() => document.addEventListener('pointerdown', onOutsidePointer))
onUnmounted(() => document.removeEventListener('pointerdown', onOutsidePointer))
</script>
<style scoped>
.site-navigation {
  display: flex;
  align-items: center;
  gap: 8px;
}

.site-link {
  padding: 8px 14px;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}

.site-link:hover {
  color: var(--web-accent);
  background: var(--web-tint);
}

.site-navigation.is-mobile {
  align-items: stretch;
  flex-direction: column;
}

.blog-navigation {
  position: relative;
}

summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  list-style: none;
}

summary::-webkit-details-marker {
  display: none;
}

summary:hover,
.blog-navigation[open] > summary {
  color: var(--web-accent);
  background: var(--web-tint);
}

nav {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 150px;
  padding: 8px;
  border: 1px solid var(--web-line);
  border-radius: 12px;
  background: var(--web-paper);
  box-shadow: 0 8px 24px var(--web-line);
}

nav a {
  display: block;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
}

nav a:hover {
  color: var(--web-accent);
  background: var(--web-tint);
}

.is-mobile summary {
  padding: 12px;
  background: var(--web-soft);
  border-radius: 10px;
}

.is-mobile nav {
  position: static;
  min-width: 0;
  margin: 8px 0;
  box-shadow: none;
}
</style>
