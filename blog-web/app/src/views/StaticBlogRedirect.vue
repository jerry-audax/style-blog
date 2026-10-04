<template>
  <div class="page-container">
    <p>正在前往技术博客…</p>
    <a :href="target">进入博客</a>
  </div>
</template>
<script setup>
import {computed, onMounted} from 'vue'
import {useRoute} from 'vue-router'

const route = useRoute()
const target = computed(() =>
    route.params.id ? '/blog/article/' + encodeURIComponent(route.params.id) + '/' : '/blog/',
)
onMounted(() => window.location.replace(target.value))
</script>
