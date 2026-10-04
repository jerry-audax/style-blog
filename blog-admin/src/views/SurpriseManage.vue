<template>
  <section class="surprise-page">
    <header class="surprise-heading"><div><h1>惊喜视频</h1><p>上传后公开端会随机播放已启用的视频，视频直接有声播放，不需要封面。</p></div></header>
    <section class="surprise-card upload-card">
      <h2>添加视频</h2>
      <div class="surprise-form"><label>标题<input v-model.trim="newTitle" maxlength="120" placeholder="惊喜视频"></label><label>排序<input v-model.number="newSort" type="number" min="0"></label><label class="check"><input v-model="newEnabled" type="checkbox">立即启用</label><label class="upload-button primary">选择视频<input type="file" accept="video/*" hidden @change="upload"></label></div>
      <p class="muted">支持 MP4、WebM、MOV 等浏览器可播放格式，单个文件最大 200 MiB。</p>
    </section>
    <p v-if="message" class="surprise-message" :class="{error}">{{ message }}</p>
    <section class="surprise-card"><div class="card-title"><h2>视频列表</h2><button type="button" @click="load" :disabled="busy">刷新</button></div><p v-if="busy && !videos.length" class="empty">正在加载…</p><p v-else-if="!videos.length" class="empty">还没有视频，先上传一条吧。</p><div v-else class="video-list"><article v-for="video in videos" :key="video.id" class="video-row"><video :src="video.url" controls preload="metadata"></video><div class="video-fields"><label>标题<input v-model.trim="video.title" maxlength="120"></label><label>排序<input v-model.number="video.sortOrder" type="number" min="0"></label><label class="check"><input v-model="video.enabled" type="checkbox">公开随机播放</label><small>{{ formatSize(video.size) }} · {{ video.mediaType }}</small></div><div class="video-actions"><button type="button" @click="save(video)" :disabled="busy">保存</button><button type="button" class="danger" @click="remove(video)" :disabled="busy">删除</button></div></article></div></section>
  </section>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {createSurpriseVideo, deleteSurpriseVideo, getSurpriseVideos, updateSurpriseVideo} from '../api/index.js'
const videos = ref([]), busy = ref(false), message = ref(''), error = ref(false)
const newTitle = ref('惊喜视频'), newSort = ref(0), newEnabled = ref(true)
const show = (text, failed = false) => { message.value = text; error.value = failed; window.setTimeout(() => { message.value = '' }, 4000) }
const load = async () => { busy.value = true; try { videos.value = (await getSurpriseVideos()) || [] } catch (e) { show(e.message || '视频列表加载失败', true) } finally { busy.value = false } }
const upload = async event => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; busy.value = true; try { videos.value.push(await createSurpriseVideo(file, newTitle.value || '惊喜视频', newEnabled.value, Number(newSort.value) || 0)); show('视频已上传') } catch (e) { show(e.message || '视频上传失败', true) } finally { busy.value = false } }
const save = async video => { busy.value = true; try { Object.assign(video, await updateSurpriseVideo(video.id, {title: video.title, enabled: video.enabled, sortOrder: Number(video.sortOrder) || 0})); show('已保存') } catch (e) { show(e.message || '保存失败', true) } finally { busy.value = false } }
const remove = async video => { if (!window.confirm(`确定删除“${video.title}”吗？`)) return; busy.value = true; try { await deleteSurpriseVideo(video.id); videos.value = videos.value.filter(item => item.id !== video.id); show('已删除') } catch (e) { show(e.message || '删除失败', true) } finally { busy.value = false } }
const formatSize = bytes => { if (!bytes) return '0 B'; const units = ['B', 'KiB', 'MiB', 'GiB']; let value = bytes, index = 0; while (value >= 1024 && index < units.length - 1) { value /= 1024; index++ } return `${value.toFixed(index ? 1 : 0)} ${units[index]}` }
onMounted(load)
</script>

<style scoped>
.surprise-page{max-width:1100px;margin:auto}.surprise-heading{margin-bottom:22px}.surprise-heading h1{margin:0 0 8px}.surprise-heading p,.muted,.empty{color:var(--admin-muted,#73809a)}.surprise-card{background:var(--admin-card,#fff);border:1px solid var(--admin-border,#e5e9f2);border-radius:16px;padding:24px;margin-bottom:18px}.surprise-form{display:flex;gap:14px;align-items:end;flex-wrap:wrap}.surprise-form label,.video-fields label{display:flex;flex-direction:column;gap:7px;font-size:13px;color:var(--admin-muted,#73809a)}input{border:1px solid var(--admin-border,#dfe4ef);border-radius:8px;padding:9px 11px;color:inherit;background:transparent}.check{flex-direction:row!important;align-items:center;align-self:center}.check input{accent-color:#4f46e5}.upload-button{cursor:pointer}.upload-button.primary,button.primary{background:#4f46e5;color:#fff;border:0;border-radius:8px;padding:10px 16px}button{border:1px solid var(--admin-border,#dfe4ef);background:transparent;border-radius:8px;padding:9px 14px;cursor:pointer;color:inherit}.card-title{display:flex;justify-content:space-between;align-items:center}.video-list{display:grid;gap:14px}.video-row{display:grid;grid-template-columns:260px 1fr auto;gap:18px;align-items:center;border-top:1px solid var(--admin-border,#e5e9f2);padding-top:14px}.video-row video{width:260px;max-height:150px;background:#111;border-radius:10px}.video-fields{display:flex;gap:12px;align-items:end;flex-wrap:wrap}.video-fields small{color:var(--admin-muted,#73809a)}.video-actions{display:flex;gap:8px}.danger{color:#d14343}@media(max-width:800px){.video-row{grid-template-columns:1fr}.video-row video{width:100%}}
</style>
