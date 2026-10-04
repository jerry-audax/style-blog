<template>
  <section class="music-page">
    <header class="music-heading">
      <div><h1>音乐管理</h1>
        <p>授权自己的网易云账号，同步博客歌单。</p></div>
      <a :href="playlistLink" target="_blank" rel="noopener noreferrer">在网易云查看歌单 ↗</a>
    </header>
    <p class="music-sources">官方授权 <span>→ 网易云歌单资料</span><i aria-hidden="true">/</i> 图床音频
      <span>→ 本站公开播放</span></p>
    <div class="music-grid">
      <section class="music-card" aria-labelledby="music-account-title">
        <h2 id="music-account-title">网易云授权 <span class="badge" :class="{ active: state.authorized }">{{
            loading ? '检查中' : !state.configured ? '未配置' : state.authorized ? '已授权' : '未授权'
          }}</span></h2>
        <p>授权只供博主同步歌单。访客无需登录，也不会使用你的会员权益。</p>
        <div class="actions">
          <button type="button" class="primary" :disabled="busy || loading || !state.configured" @click="authorize">
            {{ busy === 'authorize' ? '获取授权入口…' : state.authorized ? '检查授权' : '获取授权二维码' }}
          </button>
          <button type="button" :disabled="busy || loading" @click="reload">刷新状态</button>
          <button v-if="state.authorized || authorization" type="button" :disabled="Boolean(busy)" @click="revoke">
            解除授权
          </button>
        </div>
        <div v-if="authorization" class="authorization">
          <img v-if="qr" :src="qr" alt="使用网易云音乐 App 扫描授权二维码" width="180" height="180"/>
          <div><p>使用网易云音乐 App 扫码并确认。</p><a :href="authorization.authorizationUrl" target="_blank"
                                                       rel="noopener noreferrer">打开官方授权页面 ↗</a>
            <p class="muted">二维码短时有效；本页会自动检查授权结果。</p></div>
        </div>
        <p v-if="!state.configured && !loading" class="muted">请先完成后端 CLI 配置，密钥无需填写在此页面。</p>
      </section>
      <section class="music-card" aria-labelledby="music-sync-title">
        <h2 id="music-sync-title">博客歌单</h2>
        <p class="playlist-name">{{ playlist?.name || '尚未同步' }}</p>
        <dl>
          <div>
            <dt>歌单 ID</dt>
            <dd>{{ state.playlistId || '939817038' }}</dd>
          </div>
          <div>
            <dt>歌曲数量</dt>
            <dd>{{ playlist?.songs?.length ?? '—' }}</dd>
          </div>
          <div>
            <dt>上次同步</dt>
            <dd>{{ formattedTime }}</dd>
          </div>
        </dl>
        <button type="button" class="primary" :disabled="busy || loading || !state.authorized" @click="sync">
          {{ busy === 'sync' ? '正在同步…' : '同步歌单' }}
        </button>
        <p class="muted">这里仅同步网易云歌单资料。公开播放器独立读取图床 blog/music
          直属目录的正常音频，不依赖网易云授权；同步不会上传、覆盖或删除音频。</p>
      </section>
    </div>
    <p v-if="message" class="music-message" :class="{ error: failed }" role="status">{{ message }}</p>
    <section class="music-card track-card" aria-labelledby="music-tracks-title">
      <h2 id="music-tracks-title">已同步歌曲</h2>
      <p class="muted">下列授权标记仅代表博主账号，不代表公开播放器的可播放状态。</p>
      <p v-if="!playlist?.songs?.length" class="empty">{{
          loading ? '正在加载…' : '授权后点击“同步歌单”，在这里查看歌曲。'
        }}</p>
      <div v-else class="track-scroll">
        <table>
          <thead>
          <tr>
            <th scope="col">歌曲</th>
            <th scope="col">歌手</th>
            <th scope="col">博主账号标记</th>
          </tr>
          </thead>
          <tbody>
          <tr v-for="song in playlist.songs" :key="song.id">
            <td><a :href="`https://music.163.com/#/song?id=${song.id}`" target="_blank"
                   rel="noopener noreferrer">{{ song.name || '未命名歌曲' }}</a></td>
            <td>{{ song.artist || '未知歌手' }}</td>
            <td>{{ song.preview ? '试听' : song.ownerPlayable ? '允许播放' : '受限或未知' }}</td>
          </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section class="music-card catalog-card" aria-labelledby="music-catalog-title">
      <div class="catalog-heading">
        <div>
          <h2 id="music-catalog-title">本站自定义歌单</h2>
          <p class="muted">网易云资料只用于初始匹配。保存后以本站目录为准，音频、封面和歌词可以独立替换。</p>
        </div>
        <button type="button" class="primary" :disabled="catalogBusy" @click="createTrack">新增歌曲</button>
      </div>
      <p v-if="catalogError" class="music-message error" role="alert">{{ catalogError }}</p>
      <p v-if="catalogBusy && !catalog.length" class="empty">正在加载本站音乐…</p>
      <p v-else-if="!catalog.length" class="empty">暂时没有发现已托管音频。可以先在图床的 blog/music 目录上传，或点击“新增歌曲”。</p>
      <div v-else class="catalog-list">
        <article v-for="track in catalog" :key="track.id" class="catalog-row">
          <div class="track-cover">
            <img v-if="track.cover?.url" :src="track.cover.url" :alt="track.title" loading="lazy">
            <span v-else aria-hidden="true">♪</span>
          </div>
          <div class="track-fields">
            <label>歌曲名称<input v-model.trim="track.title" maxlength="200"></label>
            <label>歌手<input v-model.trim="track.artist" maxlength="200"></label>
            <label class="enabled"><input v-model="track.enabled" type="checkbox"> 在公开歌单显示</label>
            <div class="track-meta">
              <span>{{ track.audio?.path ? '音频已配置' : '缺少音频' }}</span>
              <span>{{ track.cover?.url ? '封面已配置' : '无自定义封面' }}</span>
              <span>{{ track.lyrics?.url ? '歌词已配置' : '无歌词' }}</span>
            </div>
          </div>
          <div class="track-actions">
            <button type="button" :disabled="catalogBusy" @click="saveTrack(track)">保存资料</button>
            <label class="upload-button">替换音频<input type="file" accept="audio/*" hidden @change="uploadMedia(track, 'audio', $event)"></label>
            <label class="upload-button">上传封面<input type="file" accept="image/jpeg,image/png,image/gif,image/webp" hidden @change="uploadMedia(track, 'cover', $event)"></label>
            <label class="upload-button">上传歌词<input type="file" accept=".lrc,text/plain" hidden @change="uploadMedia(track, 'lyrics', $event)"></label>
            <button v-if="track.audio?.path" type="button" class="danger" :disabled="catalogBusy" @click="removeMedia(track, 'audio')">删除音频</button>
            <button v-if="track.cover?.path" type="button" class="danger" :disabled="catalogBusy" @click="removeMedia(track, 'cover')">删除封面</button>
            <button v-if="track.lyrics?.path" type="button" class="danger" :disabled="catalogBusy" @click="removeMedia(track, 'lyrics')">删除歌词</button>
            <button type="button" class="danger delete-track" :disabled="catalogBusy" @click="removeTrack(track)">删除歌曲</button>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>

<script setup>
import {computed, onMounted, onBeforeUnmount, ref} from 'vue'
import QRCode from 'qrcode'
import {getMusicStatus, getMusicPlaylist, authorizeMusic, syncMusic, revokeMusic, getMusicCatalog, createMusicTrack, updateMusicTrack, deleteMusicTrack, uploadMusicMedia, deleteMusicMedia} from '../api'

const state = ref({configured: false, authorized: false, playlistId: '939817038'})
const playlist = ref(null), authorization = ref(null), qr = ref('')
const loading = ref(true), busy = ref(''), message = ref(''), failed = ref(false)
const catalog = ref([]), catalogBusy = ref(false), catalogError = ref('')
const playlistLink = computed(() => `https://music.163.com/#/playlist?id=${state.value.playlistId}`)
const formattedTime = computed(() => playlist.value?.syncedAt ? new Date(playlist.value.syncedAt).toLocaleString('zh-CN') : '—')
let alive = true, timer = null, revision = 0

function stopPolling() {
  clearTimeout(timer);
  timer = null;
  revision += 1
}

function notice(text, error = false) {
  if (alive) {
    message.value = text;
    failed.value = error
  }
}

function validAuthorization(value) {
  try {
    const url = new URL(value.authorizationUrl)
    return url.protocol === 'https:' && ['163cn.tv', 'music.163.com', 'st.music.163.com'].includes(url.hostname)
        && !url.username && !url.password && Date.parse(value.expiresAt) > Date.now()
  } catch {
    return false
  }
}

async function reload() {
  loading.value = true
  try {
    const [status, songs] = await Promise.all([getMusicStatus(), getMusicPlaylist()])
    let custom = []
    if (typeof getMusicCatalog === 'function') {
      try { custom = await getMusicCatalog(); catalogError.value = '' } catch (error) { catalogError.value = error.message }
    }
    if (!alive) return
    state.value = status;
    playlist.value = songs
    catalog.value = Array.isArray(custom) ? custom : []
    if (status.authorized) {
      stopPolling();
      authorization.value = null;
      qr.value = ''
    }
    notice('状态已更新')
  } catch (error) {
    notice(error.message, true)
  } finally {
    if (alive) loading.value = false
  }
}

async function loadCatalog() {
  if (typeof getMusicCatalog !== 'function') return
  catalogBusy.value = true
  catalogError.value = ''
  try { catalog.value = await getMusicCatalog() } catch (error) { catalogError.value = error.message } finally { catalogBusy.value = false }
}

async function createTrack() {
  catalogBusy.value = true
  catalogError.value = ''
  try {
    const track = await createMusicTrack({title: '未命名歌曲', artist: '', sourceId: '', enabled: true, sortOrder: catalog.value.length})
    catalog.value.push(track)
    notice('已新增歌曲，请上传音频或完善资料')
  } catch (error) { catalogError.value = error.message } finally { catalogBusy.value = false }
}

async function saveTrack(track) {
  catalogBusy.value = true
  catalogError.value = ''
  try {
    const saved = await updateMusicTrack(track.id, {title: track.title, artist: track.artist, sourceId: track.sourceId, enabled: track.enabled, sortOrder: track.sortOrder})
    Object.assign(track, saved)
    notice('歌曲资料已保存，公开端将自动更新')
  } catch (error) { catalogError.value = error.message } finally { catalogBusy.value = false }
}

async function uploadMedia(track, kind, event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  catalogBusy.value = true
  catalogError.value = ''
  try {
    const saved = await uploadMusicMedia(track.id, kind, file)
    Object.assign(track, saved)
    notice(`${kind === 'audio' ? '音频' : kind === 'cover' ? '封面' : '歌词'}已上传，公开端将自动更新`)
  } catch (error) { catalogError.value = error.message } finally { catalogBusy.value = false }
}

async function removeMedia(track, kind) {
  if (!window.confirm(`确认删除这首歌的${kind === 'audio' ? '音频' : kind === 'cover' ? '封面' : '歌词'}？`)) return
  catalogBusy.value = true
  catalogError.value = ''
  try {
    const saved = await deleteMusicMedia(track.id, kind)
    Object.assign(track, saved)
    notice('音乐资源已删除，公开端将自动更新')
  } catch (error) { catalogError.value = error.message } finally { catalogBusy.value = false }
}

async function removeTrack(track) {
  if (!window.confirm(`确认删除歌曲“${track.title || '未命名歌曲'}”？这会同时删除音频、封面和歌词文件，且无法恢复。`)) return
  catalogBusy.value = true
  catalogError.value = ''
  try {
    await deleteMusicTrack(track.id)
    catalog.value = catalog.value.filter(item => item.id !== track.id)
    notice('歌曲及其关联文件已删除，公开端将自动更新')
  } catch (error) { catalogError.value = error.message } finally { catalogBusy.value = false }
}

function poll(currentRevision) {
  timer = setTimeout(async () => {
    if (!alive || currentRevision !== revision || !authorization.value) return
    if (loading.value) {
      poll(currentRevision);
      return
    }
    if (Date.parse(authorization.value.expiresAt) <= Date.now()) {
      stopPolling();
      authorization.value = null;
      qr.value = '';
      notice('二维码已过期，请重新获取', true);
      return
    }
    try {
      const status = await getMusicStatus()
      if (!alive || currentRevision !== revision) return
      state.value = status
      if (status.authorized) {
        stopPolling();
        authorization.value = null;
        qr.value = '';
        notice('网易云授权成功，可以同步歌单');
        return
      }
    } catch (error) {
      if (!alive || currentRevision !== revision) return
      // Do not repeatedly call an unavailable provider or keep an invalid blog session polling.
      stopPolling();
      authorization.value = null;
      qr.value = '';
      notice(`${error.message}；请刷新状态后重试`, true);
      return
    }
    poll(currentRevision)
  }, 10000)
}

async function authorize() {
  busy.value = 'authorize';
  stopPolling()
  authorization.value = null;
  qr.value = ''
  try {
    const result = await authorizeMusic()
    if (!alive) return
    if (result.authorized) {
      await reload();
      notice('网易云授权有效');
      return
    }
    if (!validAuthorization(result)) throw new Error('授权入口无效或已过期，请重新获取')
    authorization.value = result
    const currentRevision = revision
    const image = await QRCode.toDataURL(result.authorizationUrl, {width: 180, margin: 2})
    if (!alive || currentRevision !== revision) return
    qr.value = image;
    notice('请在网易云音乐 App 中确认授权');
    poll(currentRevision)
  } catch (error) {
    notice(error.message, true)
  } finally {
    if (alive) busy.value = ''
  }
}

async function sync() {
  busy.value = 'sync'
  try {
    const result = await syncMusic()
    if (!alive) return
    playlist.value = result;
    notice(`网易云资料已同步，共 ${result.songs.length} 首；本站播放列表仍以已托管音频为准`)
  } catch (error) {
    notice(error.message, true)
  } finally {
    if (alive) busy.value = ''
  }
}

async function revoke() {
  if (!window.confirm('解除网易云授权并清除同步资料？本站已托管音频和博客管理登录均不受影响。')) return
  busy.value = 'revoke';
  stopPolling()
  try {
    const result = await revokeMusic()
    if (!alive) return
    authorization.value = null;
    qr.value = '';
    playlist.value = null
    state.value = {...state.value, authorized: false}
    notice(result.remoteLogoutConfirmed ? '网易云授权已解除，同步资料已清除' : '本站授权已停用；网易云退出结果未确认，可在网易云端检查并撤销授权')
  } catch (error) {
    notice(error.message, true)
  } finally {
    if (alive) busy.value = ''
  }
}

onMounted(reload)
onBeforeUnmount(() => {
  alive = false;
  stopPolling();
  authorization.value = null;
  qr.value = ''
})
</script>

<style scoped>
.music-page {
  max-width: 1180px;
  margin: 0 auto;
  color: var(--admin-text);
  font-family: var(--admin-font-body);
}

.music-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-bottom: 22px;
}

h1 {
  font-family: var(--admin-font-display);
  font-size: 30px;
  margin: 0 0 8px;
}

h2 {
  font-size: 18px;
  margin: 0 0 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

p {
  line-height: 1.8;
  margin: 10px 0;
}

.music-heading p, .muted {
  color: var(--admin-muted);
  font-size: 13px;
}

a {
  color: var(--admin-accent);
  text-decoration: none;
}

a:hover {
  text-decoration: underline;
}

.music-sources {
  border-left: 3px solid var(--admin-accent);
  padding: 8px 16px;
  margin: 0 0 24px;
  font-size: 14px;
}

.music-sources span {
  color: var(--admin-muted);
}

.music-sources i {
  margin: 0 20px;
  color: var(--admin-line);
}

.music-grid {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 20px;
}

.music-card {
  background: var(--admin-panel);
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  padding: 24px;
}

.badge {
  border-radius: 5px;
  background: var(--admin-bg);
  color: var(--admin-muted);
  padding: 4px 8px;
  font-size: 12px;
}

.badge.active {
  color: var(--admin-success);
}

.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 20px;
}

button {
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid var(--admin-line);
  border-radius: 6px;
  padding: 10px 14px;
  background: var(--admin-panel);
  color: var(--admin-text);
}

button.primary {
  background: var(--admin-accent);
  border-color: var(--admin-accent);
  color: var(--admin-panel);
}

button:disabled {
  opacity: .5;
  cursor: not-allowed;
}

:is(button,a):focus-visible {
  outline: 2px solid var(--admin-accent);
  outline-offset: 4px;
}

.authorization {
  display: flex;
  gap: 20px;
  align-items: center;
  margin-top: 24px;
}

.authorization img {
  background: white;
  border-radius: 6px;
  flex-shrink: 0;
}

.playlist-name {
  font-size: 19px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

dl {
  margin: 16px 0 24px;
}

dl div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin: 10px 0;
  font-size: 13px;
}

dt {
  color: var(--admin-muted);
}

dd {
  margin: 0;
  text-align: right;
}

.track-card {
  margin-top: 24px;
}

.catalog-card {
  margin-top: 24px;
}

.catalog-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 18px;
}

.catalog-list {
  display: grid;
  gap: 12px;
}

.catalog-row {
  display: grid;
  grid-template-columns: 76px minmax(220px, 1fr) minmax(260px, 0.8fr);
  gap: 16px;
  align-items: center;
  padding: 14px;
  border: 1px solid var(--admin-line);
  border-radius: 8px;
  background: color-mix(in srgb, var(--admin-bg) 60%, transparent);
}

.track-cover {
  width: 76px;
  height: 76px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  overflow: hidden;
  background: var(--admin-surface);
  color: var(--admin-muted);
  font-size: 28px;
}

.track-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.track-fields {
  display: grid;
  gap: 8px;
}

.track-fields label {
  display: grid;
  grid-template-columns: 66px 1fr;
  align-items: center;
  gap: 8px;
  color: var(--admin-muted);
  font-size: 12px;
}

.track-fields input:not([type="checkbox"]) {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  border: 1px solid var(--admin-line);
  border-radius: 5px;
  padding: 7px 8px;
  background: var(--admin-panel);
  color: var(--admin-text);
}

.track-fields .enabled {
  display: flex;
  gap: 6px;
}

.track-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--admin-muted);
  font-size: 11px;
}

.track-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.track-actions button,
.upload-button {
  border: 1px solid var(--admin-line);
  border-radius: 5px;
  padding: 7px 9px;
  background: var(--admin-panel);
  color: var(--admin-text);
  font-size: 12px;
  cursor: pointer;
}

.track-actions .upload-button {
  display: inline-flex;
  align-items: center;
}

.track-actions .danger {
  color: var(--admin-danger);
}

.track-scroll {
  overflow-x: auto;
  max-height: 500px;
}

table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: 13px;
}

td, th {
  padding: 13px 10px;
  border-bottom: 1px solid var(--admin-line);
}

th {
  color: var(--admin-muted);
  white-space: nowrap;
}

td {
  overflow-wrap: anywhere;
}

.empty {
  padding: 30px 0;
  text-align: center;
  color: var(--admin-muted);
}

.music-message {
  padding: 12px 16px;
  background: var(--admin-panel);
  border-left: 3px solid var(--admin-success);
  font-size: 14px;
}

.music-message.error {
  border-color: var(--admin-danger);
}

@media (max-width: 780px) {
  .music-grid {
    grid-template-columns: 1fr;
  }

  .music-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .music-card {
    padding: 20px;
  }

  .authorization {
    flex-direction: column;
    align-items: flex-start;
  }

  .music-sources i {
    margin: 0 8px;
  }

  .catalog-heading {
    flex-direction: column;
  }

  .catalog-row {
    grid-template-columns: 56px 1fr;
  }

  .track-cover {
    width: 56px;
    height: 56px;
  }

  .track-actions {
    grid-column: 1 / -1;
  }
}
</style>
