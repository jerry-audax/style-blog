<template>
  <aside class="publication-notice" aria-label="公开博客自动发布状态">
    <div class="publication-intro">
      <el-icon aria-hidden="true"><Connection/></el-icon>
      <div aria-live="polite"><strong>{{ heading }}</strong><p>已保存的发布内容会自动更新到博客；草稿不会公开，无需手动执行构建命令。</p><p v-if="state.error" class="publication-error">{{ state.error }}</p><small v-if="state.publishedAt">最近发布：{{ formatTime(state.publishedAt) }}</small></div>
      <button class="text-action" type="button" @click="retry" :disabled="retrying || !state.configured || state.phase === 'BUILDING'">{{ retrying ? '正在提交…' : state.phase === 'FAILED' ? '重试发布' : '重新发布' }}</button>
    </div>
  </aside>
</template>
<script setup>
import {computed, onMounted, onBeforeUnmount, ref} from 'vue'
import {ElIcon} from 'element-plus'
import {Connection} from '@element-plus/icons-vue'
import {getPublicationStatus, retryPublication} from '../api/publication'
const state = ref({configured: false, phase: 'LOADING', error: null})
const retrying = ref(false)
let disposed = false, timer
const heading = computed(() => ({LOADING: '正在读取发布状态', WAITING: '等待发布', BUILDING: '正在更新公开博客', CURRENT: '最近一次发布成功', FAILED: '公开博客发布失败', UNAVAILABLE: '自动发布服务未连接'}[state.value.phase] || '发布状态待确认'))
const formatTime = value => {const date = new Date(value); return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN') : '未知'}
const refresh = async () => {
  try {const result = await getPublicationStatus(); if (!disposed && !retrying.value) state.value = result}
  catch {if (!disposed && !retrying.value) state.value = {configured: false, phase: 'UNAVAILABLE', error: '发布状态暂不可用，已保存的后台内容不会丢失。'}}
  finally {if (!disposed) timer = setTimeout(refresh, 5000)}
}
const retry = async () => {
  if (retrying.value || !state.value.configured) return
  retrying.value = true
  try {const result = await retryPublication(); if (!disposed) state.value = result}
  catch (error) {if (!disposed) state.value = {...state.value, phase: 'FAILED', error: error.message || '发布请求失败，请稍后重试。'}}
  finally {if (!disposed) retrying.value = false}
}
onMounted(refresh)
onBeforeUnmount(() => {disposed = true; clearTimeout(timer)})
</script>
<style scoped>
.publication-error {color:var(--admin-danger);}
small {color:var(--admin-muted);}
button:disabled {opacity:.5;cursor:not-allowed;}
</style>
