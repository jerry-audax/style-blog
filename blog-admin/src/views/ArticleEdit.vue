<template>
  <section class="page authoring-page">
    <header class="page-header">
      <div class="header-left"><button class="back-btn" @click="handleCancel" :disabled="saving"><span class="back-arrow">&larr;</span><span>返回文章列表</span></button><div><h2 class="page-title">{{ isEdit ? '编辑文章' : '写文章' }}</h2><p class="page-sub">正文用来阅读，封面、摘要与分类用于公开博客展示。</p></div></div>
      <div class="header-actions"><el-button size="large" @click="handleCancel" :disabled="saving">取消</el-button><el-button type="primary" size="large" @click="submit" :loading="saving" :disabled="uploading || loading || !!loadError">{{ form.status === 0 ? '保存草稿' : '保存发布状态' }}</el-button></div>
    </header>
    <div v-if="loadError" class="editor-load-error" role="alert"><p>{{ loadError }}</p><el-button @click="loadEditor">重新加载</el-button></div>
    <div v-else class="authoring-layout" v-loading="loading">
      <div class="authoring-main">
        <div class="article-heading"><label for="article-title">文章标题</label><el-input id="article-title" v-model="form.title" placeholder="给这篇文章起个标题" size="large" class="title-input"/><p>固定链接{{ articleId ? '：/blog/article/' + articleId + '/' : '将在首次保存后生成' }}，不随标题变化。</p></div>
    <div class="editor-wrapper editor-primary">
      <div class="toolbar" v-if="editor">
        <button class="tb-btn" @click="editor.chain().focus().toggleBold().run()"
                :class="{ active: editor.isActive('bold') }" title="加粗">B
        </button>
        <button class="tb-btn tb-italic" @click="editor.chain().focus().toggleItalic().run()"
                :class="{ active: editor.isActive('italic') }" title="斜体">I
        </button>
        <button class="tb-btn" @click="editor.chain().focus().toggleStrike().run()"
                :class="{ active: editor.isActive('strike') }" title="删除线">S
        </button>
        <span class="tb-sep"></span>
        <button class="tb-btn" @click="editor.chain().focus().toggleHeading({ level: 1 }).run()"
                :class="{ active: editor.isActive('heading', { level: 1 }) }">H1
        </button>
        <button class="tb-btn" @click="editor.chain().focus().toggleHeading({ level: 2 }).run()"
                :class="{ active: editor.isActive('heading', { level: 2 }) }">H2
        </button>
        <button class="tb-btn" @click="editor.chain().focus().toggleHeading({ level: 3 }).run()"
                :class="{ active: editor.isActive('heading', { level: 3 }) }">H3
        </button>
        <span class="tb-sep"></span>
        <button class="tb-btn" @click="editor.chain().focus().toggleBlockquote().run()"
                :class="{ active: editor.isActive('blockquote') }" title="引用">"
        </button>
        <button class="tb-btn" @click="editor.chain().focus().toggleCodeBlock().run()"
                :class="{ active: editor.isActive('codeBlock') }" title="代码块">&lt;/&gt;
        </button>
        <button class="tb-btn" @click="editor.chain().focus().toggleBulletList().run()"
                :class="{ active: editor.isActive('bulletList') }" title="无序列表">•
        </button>
        <button class="tb-btn" @click="editor.chain().focus().toggleOrderedList().run()"
                :class="{ active: editor.isActive('orderedList') }" title="有序列表">1.
        </button>
        <span class="tb-sep"></span>
        <button class="tb-btn" @click="setLink" title="插入链接">🔗</button>
        <button class="tb-btn" :disabled="uploading" @click="articleImageInput.click()" title="上传正文图片"
                aria-label="上传正文图片">🖼
        </button>
        <button class="tb-library" :disabled="uploading || saving || loading" @click="openImagePicker('article')">图片库</button>
        <input ref="articleImageInput" type="file" accept="image/jpeg,image/png,image/gif,image/webp" hidden
               @change="selectArticleImage"/>
        <span v-if="uploading" role="status">图片上传中…</span>
      </div>
      <div class="editor-body" @click="onEditorClick">
        <editor-content :editor="editor"/>
      </div>
    </div>


        <p class="editor-footer">支持富文本、代码、图片拖拽与粘贴。正文保存为 HTML，公开端构建时净化。</p>
      </div>
      <aside class="authoring-sidebar" aria-label="文章发布与卡片设置">
        <fieldset class="form-section"><legend>发布设置</legend>
    <div class="meta-bar">
      <div class="meta-item">
        <label>状态</label>
        <el-select v-model="form.status" size="large">
          <el-option label="草稿" :value="0"/>
          <el-option label="已发布" :value="1"/>
        </el-select>
      </div>
      <div class="meta-divider"/>
      <div class="meta-item">
        <label>分类</label>
        <el-select v-model="form.categoryId" placeholder="选择分类" clearable size="large">
          <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id"/>
        </el-select>
      </div>
      <div class="meta-divider"/>
      <div class="meta-item meta-tags">
        <label>标签</label>
        <el-select v-model="form.tagIds" placeholder="选择标签" multiple clearable size="large">
          <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id"/>
        </el-select>
      </div>
      <div class="meta-divider"/>
      <div class="meta-switches">
        <div class="switch-item">
          <el-switch v-model="form.isTop" :active-value="1" :inactive-value="0" size="large"/>
          <span>置顶</span>
        </div>
        <div class="switch-item">
          <el-switch v-model="form.isCommentEnabled" :active-value="1" :inactive-value="0" size="large"/>
          <span>展示历史评论</span>
        </div>
      </div>
    </div>


          <p class="page-sub">“已发布”是后台状态；更新公开页面仍需同步、构建和部署。置顶影响首页文章排序。</p>
        </fieldset>
        <fieldset class="form-section"><legend>首页卡片</legend>
          <label for="article-summary">文章摘要</label><el-input id="article-summary" v-model="form.summary" placeholder="简述文章内容，用于首页文章卡片" type="textarea" :rows="4"/>
          <label for="article-cover">封面地址</label><el-input id="article-cover" v-model="form.coverUrl" placeholder="封面图片 URL（可选）"/>
          <div class="cover-actions"><el-button :disabled="uploading || saving" @click="openImagePicker('cover')">从图片库选择</el-button><el-button :disabled="uploading || saving" @click="coverImageInput.click()">上传封面</el-button></div>
          <input ref="coverImageInput" type="file" accept="image/jpeg,image/png,image/gif,image/webp" hidden @change="selectCoverImage"/>
          <p class="page-sub">Telegram 图床 · 最大 10 MiB。取消或替换图片不自动删除文件。</p>
        </fieldset>
        <section class="article-card-preview" aria-label="文章卡片预览">
          <p class="preview-label">首页卡片预览 · 非部署结果</p>
          <img v-if="form.coverUrl" :src="form.coverUrl" alt="文章封面预览"/>
          <div class="card-preview-copy"><span>{{ categories.find(item => item.id === form.categoryId)?.name || '未分类' }}{{ form.isTop === 1 ? ' · 置顶' : '' }}</span><strong>{{ form.title || '文章标题' }}</strong><p>{{ form.summary || '填写摘要，让访客更快了解文章内容。' }}</p></div>
        </section>
      </aside>
    </div>
    <ImagePicker v-model="imagePickerOpen" :purpose="pickerPurpose" @select="useLibraryImage"/>
    <el-dialog v-model="imageDialog" title="编辑图片" width="440px">
      <div class="dialog-field">
        <label>图片地址</label>
        <el-input v-model="editingImageSrc" placeholder="图片 URL" size="large"/>
      </div>
      <div class="dialog-field">
        <label>图片说明</label>
        <el-input v-model="editingImageCaption" placeholder="图片下方展示的说明文字（可选）" size="large"/>
      </div>
      <div class="dialog-field">
        <label>宽度</label>
        <el-select v-model="editingImageWidth" size="large" style="width:100%">
          <el-option label="原始大小" value=""/>
          <el-option label="25%" value="25%"/>
          <el-option label="50%" value="50%"/>
          <el-option label="75%" value="75%"/>
          <el-option label="100%" value="100%"/>
        </el-select>
      </div>
      <div class="dialog-preview" v-if="editingImageSrc">
        <img :src="editingImageSrc" :style="{ width: editingImageWidth || 'auto' }" alt="preview"/>
      </div>
      <template #footer>
        <el-button @click="imageDialog = false">取消</el-button>
        <el-button type="primary" @click="updateImageSrc">确定</el-button>
      </template>
    </el-dialog>

    <!-- 图片预览 -->
    <Teleport to="body">
      <div class="preview-overlay" v-if="showPreview" @click="showPreview = false">
        <img :src="previewSrc" @click.stop alt="preview"/>
        <button class="preview-close" @click="showPreview = false">&times;</button>
      </div>
    </Teleport>

  </section>
</template>
<script setup>
import {h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {useEditor, EditorContent, NodeViewWrapper, VueNodeViewRenderer} from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import Link from '@tiptap/extension-link'
import {getArticle, listCategories, listTags, saveArticle, uploadImage} from '../api'
import {validateImageUpload} from '../utils/images'
import ImagePicker from '../components/ImagePicker.vue'

const route = useRoute()
const router = useRouter()
const saving = ref(false)
const loading = ref(true), loadError = ref('')
const imagePickerOpen = ref(false), pickerPurpose = ref('article')
const openImagePicker = purpose => {pickerPurpose.value = purpose; imagePickerOpen.value = true}
const useLibraryImage = image => {
  if (disposed || loading.value || loadError.value || saving.value || uploading.value) return
  if (pickerPurpose.value === 'cover') form.coverUrl = image.url
  else editor.value?.chain().focus().setImage({src: image.url}).run()
}
const categories = ref([])
const tags = ref([])
const uploading = ref(false)
const articleImageInput = ref(null)
const coverImageInput = ref(null)
let disposed = false

const uploadSelectedImages = async (files, purpose = 'article') => {
  if (uploading.value || saving.value || loading.value || loadError.value) return
  uploading.value = true
  try {
    for (const file of Array.from(files).slice(0, 10)) {
      validateImageUpload(file, purpose)
      const image = await uploadImage(file, purpose)
      if (disposed) return
      if (purpose === 'cover') form.coverUrl = image.url
      else editor.value?.chain().focus().setImage({src: image.url}).run()
    }
  } catch (error) {
    if (!disposed) ElMessage.error(error.message)
  } finally {
    uploading.value = false
  }
}
const selectArticleImage = (event) => {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  uploadSelectedImages(files)
}
const selectCoverImage = (event) => {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  uploadSelectedImages(files, 'cover')
}

const articleId = route.params.id ? Number(route.params.id) : null
const isEdit = !!articleId

const form = reactive({
  id: null, title: '', summary: '', coverUrl: '', contentMd: '',
  categoryId: null, status: 0, isTop: 0, isCommentEnabled: 1, tagIds: []
})

// ---- Resizable image node view with preview ----
const previewSrc = ref('')
const showPreview = ref(false)
const ResizableImageVue = {
  props: ['node', 'updateAttributes'],
  setup(props) {
    const imgRef = ref(null)
    const resizing = ref(false)
    let startX = 0, startW = 0

    const onResizeStart = (e) => {
      e.preventDefault();
      e.stopPropagation()
      resizing.value = true
      startX = e.clientX
      startW = imgRef.value?.clientWidth || 200
      document.addEventListener('mousemove', onResizeMove)
      document.addEventListener('mouseup', onResizeEnd)
    }
    const onResizeMove = (e) => {
      if (!imgRef.value) return
      const parentW = imgRef.value.parentElement?.parentElement?.clientWidth || 800
      const newW = startW + (e.clientX - startX)
      const pct = Math.max(10, Math.min(100, Math.round((newW / parentW) * 100)))
      props.updateAttributes({width: pct + '%'})
    }
    const onResizeEnd = () => {
      resizing.value = false
      document.removeEventListener('mousemove', onResizeMove)
      document.removeEventListener('mouseup', onResizeEnd)
    }
    const onPreview = () => {
      previewSrc.value = props.node.attrs.src
      showPreview.value = true
    }
    onBeforeUnmount(onResizeEnd)
    return () => h(NodeViewWrapper, {class: 'image-node', 'data-drag-handle': ''}, {default: () => [
      h('div', {
        class: 'image-resize-wrap',
        style: {width: props.node.attrs.width || 'auto', maxWidth: '100%'}
      }, [
        h('img', {
          ref: imgRef,
          src: props.node.attrs.src,
          class: 'resizable-image',
          onClick: onPreview,
          title: '点击放大预览'
        }),
        h('div', {class: 'resize-handle', onMousedown: onResizeStart, title: '拖动调整大小'})
      ]),
      h('figcaption', {class: 'image-caption'}, props.node.attrs.caption || '')
    ]})
  }
}

const ResizableImageExt = Image.extend({
  inline: true,
  group: 'inline',
  addAttributes() {
    return {...this.parent?.(), width: {default: null}, caption: {default: ''}}
  },
  addNodeView() {
    return VueNodeViewRenderer(ResizableImageVue)
  }
})

const editor = useEditor({
  extensions: [
    StarterKit.configure({heading: {levels: [1, 2, 3]}, link: false}),
    ResizableImageExt,
    Placeholder.configure({placeholder: '开始写作，可粘贴或拖入图片…'}),
    Link.configure({openOnClick: false, HTMLAttributes: {target: '_blank', rel: 'noopener'}})
  ],
  editorProps: {
    handleDrop: (view, event) => {
      const files = event.dataTransfer?.files
      if (files?.length) {
        event.preventDefault()
        const position = view.posAtCoords({left: event.clientX, top: event.clientY})
        if (position) editor.value?.commands.setTextSelection(position.pos)
        uploadSelectedImages(files)
        return true
      }
      return false
    },
    handlePaste: (view, event) => {
      const items = event.clipboardData?.items
      if (!items) return false
      const imageFiles = []
      for (const item of items) {
        if (item.type.startsWith('image/')) imageFiles.push(item.getAsFile())
      }
      if (imageFiles.length) {
        event.preventDefault()
        uploadSelectedImages(imageFiles)
        return true
      }
      // 粘贴纯文本 URL → 自动转为链接
      const text = event.clipboardData?.getData('text/plain')?.trim()
      if (text && /^https?:\/\/\S+$/.test(text)) {
        event.preventDefault()
        editor.value.chain().focus().insertContent(`<a href="${text}" target="_blank" rel="noopener">${text}</a> `).run()
        return true
      }
      return false
    }
  }
})

const setLink = () => {
  const url = window.prompt('输入链接地址:', 'https://')
  if (!url || !editor.value) return
  const {empty} = editor.value.state.selection
  if (empty) {
    // 无选中文字：插入链接文字并在后面加空格，后续输入不受影响
    editor.value.chain().focus().insertContent(`<a href="${url}" target="_blank" rel="noopener">${url}</a>&nbsp;`).run()
  } else {
    editor.value.chain().focus().setLink({href: url}).run()
  }
}

const imageDialog = ref(false)
const editingImageSrc = ref('')
const editingImageWidth = ref('')
const editingImageCaption = ref('')
let editingImagePos = null

const onEditorClick = (e) => {
  const nodeWrap = e.target.closest('.image-node')
  if (!nodeWrap || !editor.value) return
  const img = nodeWrap.querySelector('img')
  if (!img) return

  // Find the node position
  const {view} = editor.value
  const pos = view.posAtDOM(nodeWrap, 0)
  const node = view.state.doc.nodeAt(pos)
  if (node?.type.name === 'image') {
    editingImagePos = pos
    editingImageSrc.value = node.attrs.src
    editingImageWidth.value = node.attrs.width || ''
    editingImageCaption.value = node.attrs.caption || ''
    imageDialog.value = true
  }
}

const updateImageSrc = () => {
  if (editingImagePos != null && editor.value) {
    editor.value.chain().focus().setNodeSelection(editingImagePos).updateAttributes('image', {
      src: editingImageSrc.value,
      width: editingImageWidth.value || null,
      caption: editingImageCaption.value || null
    }).run()
  }
  imageDialog.value = false
  editingImagePos = null
}

watch(() => editor.value?.getHTML(), (html) => {
  if (html && editor.value) form.contentMd = html
})

const handleCancel = () => {
  router.push('/articles')
}

const submit = async () => {
  if (saving.value || uploading.value || loading.value || loadError.value) return
  if (!form.title.trim() || !editor.value || editor.value.isEmpty) {
    ElMessage.warning('请填写文章标题和正文'); return
  }
  saving.value = true
  try {
    if (editor.value) form.contentMd = editor.value.getHTML()
    await saveArticle({...form})

    ElMessage.success(form.status === 0 ? '草稿已保存；若撤回已发布文章，博客将自动更新' : '文章已保存，公开博客将在数秒内开始自动更新')
    router.push('/articles')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

const loadEditor = async () => {
  if (disposed) return
  loading.value = true; loadError.value = ''
  try {
    const [cats, tgs, article] = await Promise.all([listCategories(), listTags(), articleId ? getArticle(articleId) : Promise.resolve(null)])
    if (disposed) return
    categories.value = cats; tags.value = tgs
    if (article) Object.assign(form, {
      id: article.id, title: article.title, summary: article.summary || '',
      coverUrl: article.coverUrl || '', contentMd: article.contentHtml || article.contentMd || '',
      categoryId: article.categoryId, status: article.status,
      isTop: article.isTop ?? 0, isCommentEnabled: article.isCommentEnabled ?? 1,
      tagIds: article.tagIds || [],
    })
    await nextTick()
    if (!disposed && editor.value) editor.value.commands.setContent(form.contentMd || '')
  } catch (error) {
    if (!disposed) loadError.value = error?.message || '文章与分类标签加载失败，请重新加载后再编辑'
  } finally {
    if (!disposed) loading.value = false
  }
}
onMounted(loadEditor)

onBeforeUnmount(() => {
  disposed = true
  editor.value?.destroy()
})
</script>

<style scoped>
.page {
  max-width: 1200px;
  padding-bottom: 64px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  gap: 16px;
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: var(--admin-text);
}

.page-sub {
  margin: 0;
  font-size: 14px;
  color: var(--admin-muted);
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 13px;
  border: 1px solid var(--admin-line-strong);
  background: transparent;
  color: var(--admin-muted);
  border-radius: 8px;
  transition: all .2s;
}

.back-btn:hover {
  background: var(--admin-soft-accent);
  color: var(--admin-accent);
}

.back-arrow {
  font-size: 15px;
}

.meta-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  background: var(--admin-panel);
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.meta-item label {
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-muted);
  white-space: nowrap;
}

.meta-tags {
  min-width: 180px;
}

.meta-divider {
  width: 1px;
  height: 28px;
  background: var(--admin-line);
  flex-shrink: 0;
}

.meta-switches {
  display: flex;
  align-items: center;
  gap: 18px;
}

.switch-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--admin-muted);
  white-space: nowrap;
}

.form-section {
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--admin-panel);
  margin-bottom: 16px;
}

.form-section legend {
  font-size: 13px;
  font-weight: 700;
  color: var(--admin-accent);
  padding: 0 8px;
  letter-spacing: .06em;
}

.title-input :deep(.el-input__inner) {
  font-size: 18px;
  font-weight: 600;
}

.editor-wrapper {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--admin-line);
  border-radius: var(--admin-radius);
  overflow: hidden;
  background: var(--admin-panel);
  margin-bottom: 16px;
}

.editor-primary {
  min-height: 550px;
  height: 65vh;
  max-height: 80vh;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--admin-line);
  background: var(--admin-table-head);
  flex-shrink: 0;
  flex-wrap: wrap;
}

.tb-btn {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--admin-muted);
  font-size: 14px;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all .15s;
}

.tb-btn:hover {
  background: var(--admin-soft-accent);
  color: var(--admin-accent);
}

.tb-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
}

.tb-btn.active {
  background: var(--admin-soft-accent-strong);
  color: var(--admin-accent);
}

.tb-italic {
  font-style: italic;
}

.tb-sep {
  width: 1px;
  height: 20px;
  background: var(--admin-line);
  margin: 0 4px;
}

.editor-body {
  flex: 1;
  min-height: 0;
  overflow: hidden auto;
}

.editor-body :deep(.tiptap) {
  padding: 24px 28px;
  min-height: 100%;
  outline: none;
  font-size: 16px;
  line-height: 1.8;
  color: var(--admin-text);
}

.editor-body :deep(.tiptap p.is-editor-empty:first-child::before) {
  content: attr(data-placeholder);
  color: var(--admin-muted);
  pointer-events: none;
  float: left;
  height: 0;
}

.editor-body :deep(.tiptap h1) {
  font-size: 28px;
  margin: 20px 0 10px;
  line-height: 1.3;
}

.editor-body :deep(.tiptap h2) {
  font-size: 22px;
  margin: 18px 0 8px;
  line-height: 1.3;
}

.editor-body :deep(.tiptap h3) {
  font-size: 18px;
  margin: 16px 0 6px;
  line-height: 1.3;
}

.editor-body :deep(.tiptap p) {
  margin: 0 0 16px;
}

.editor-body :deep(.tiptap pre) {
  background: var(--admin-table-head);
  padding: 16px 20px;
  border-radius: 8px;
  font-size: 14px;
  overflow-x: auto;
  margin: 16px 0;
}

.editor-body :deep(.tiptap code) {
  background: var(--admin-table-head);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.9em;
}

.editor-body :deep(.tiptap blockquote) {
  border-left: 3px solid var(--admin-accent);
  padding: 8px 16px;
  margin: 16px 0;
  color: var(--admin-muted);
  background: var(--admin-soft-accent);
  border-radius: 0 6px 6px 0;
}

.editor-body :deep(.tiptap ul), .editor-body :deep(.tiptap ol) {
  padding-left: 24px;
  margin: 12px 0;
}

.editor-body :deep(.tiptap li) {
  margin: 4px 0;
}

.editor-body :deep(.tiptap a) {
  color: var(--admin-accent);
  text-decoration: underline;
}

.editor-body :deep(.tiptap hr) {
  border: none;
  border-top: 1px solid var(--admin-line);
  margin: 24px 0;
}

/* Image node wrapper */
.editor-body :deep(.image-node) {
  display: inline-block;
  vertical-align: middle;
  max-width: 100%;
  margin: 4px;
}

.editor-body :deep(.image-resize-wrap) {
  display: inline-block;
  position: relative;
  max-width: 100%;
  overflow: visible;
  border-radius: 6px;
}

.editor-body :deep(.resizable-image) {
  display: block;
  width: 100%;
  height: auto;
  cursor: zoom-in;
  transition: outline .15s;
  border-radius: 6px;
}

.editor-body :deep(.image-node:hover .resizable-image),
.editor-body :deep(.image-node.ProseMirror-selectednode .resizable-image) {
  outline: 2px solid var(--admin-accent);
  outline-offset: 2px;
}

.editor-body :deep(.resize-handle) {
  position: absolute;
  right: -4px;
  bottom: -4px;
  width: 20px;
  height: 20px;
  background: var(--admin-accent);
  clip-path: polygon(100% 0, 100% 100%, 0 100%);
  border-radius: 0 0 6px 0;
  cursor: nwse-resize;
  opacity: 0;
  transition: opacity .15s;
  z-index: 2;
}

.editor-body :deep(.image-node:hover .resize-handle),
.editor-body :deep(.image-node.ProseMirror-selectednode .resize-handle) {
  opacity: 0.8;
}

/* ---- image preview overlay ---- */
.preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: zoom-out;
}

.preview-overlay img {
  max-width: 90vw;
  max-height: 90vh;
  object-fit: contain;
  border-radius: 4px;
  box-shadow: 0 4px 32px rgba(0, 0, 0, 0.4);
}

.preview-close {
  position: absolute;
  top: 20px;
  right: 20px;
  background: rgba(255, 255, 255, 0.15);
  border: none;
  color: #fff;
  font-size: 32px;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background .2s;
}

.preview-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.editor-body :deep(.image-caption) {
  text-align: center;
  font-size: 13px;
  color: var(--admin-muted);
  margin-top: 6px;
  padding: 0 4px;
  line-height: 1.5;
  font-style: italic;
  min-height: 0;
}

.dialog-field {
  margin-bottom: 16px;
}

.dialog-field label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-muted);
}

.dialog-preview {
  margin-top: 12px;
}

.dialog-preview img {
  max-width: 100%;
  border-radius: 6px;
}

@media (max-width: 900px) {
  .meta-bar {
    gap: 10px;
  }

  .meta-divider {
    display: none;
  }

  .toolbar {
    gap: 0;
    padding: 6px 8px;
  }

  .editor-primary {
    height: 50vh;
    min-height: 400px;
  }
}

@media (max-width: 640px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .meta-bar {
    flex-direction: column;
    gap: 8px;
  }

  .form-section {
    flex-direction: column;
  }

  .toolbar {
    gap: 0;
    padding: 4px 6px;
    overflow-x: auto;
  }
}
.tb-library {font-size: 12px; color: var(--admin-accent); background: var(--admin-soft-accent); border: 0; border-radius: 6px; padding: 7px 9px;}
.authoring-page {max-width: none;}
.authoring-layout {display: grid; grid-template-columns: minmax(0, 1fr) 310px; gap: 24px; align-items: start;}
.authoring-main {min-width: 0;}
.article-heading {background: var(--admin-panel); border: 1px solid var(--admin-line); border-radius: var(--admin-radius); padding: 20px; margin-bottom: 18px;}
.article-heading label, .authoring-sidebar label {font-size: 12px; color: var(--admin-muted);}
.article-heading .title-input {margin-top: 10px;}
.article-heading p, .editor-footer {font-size: 11px; color: var(--admin-muted); line-height: 1.7;}
.article-heading p {margin: 12px 0 0;}
.authoring-sidebar .meta-bar {display: flex; flex-direction: column; align-items: stretch; padding: 0; border: 0; background: transparent; margin: 0; gap: 14px;}
.authoring-sidebar .meta-item {display: flex; flex-direction: column; align-items: stretch; gap: 7px;}
.authoring-sidebar .meta-item .el-select {width: 100%;}
.authoring-sidebar .meta-divider {display: none;}
.authoring-sidebar .meta-switches {display: flex; flex-direction: column; align-items: stretch; gap: 12px; margin: 2px 0 0;}
.authoring-sidebar .switch-item {display: flex; justify-content: space-between; flex-direction: row-reverse;}
.authoring-sidebar .form-section {padding: 18px; min-width: 0;}
.authoring-sidebar .form-section legend {color: var(--admin-text); letter-spacing: 0;}
.authoring-sidebar .page-sub {white-space: normal; font-size: 11px; line-height: 1.75; margin: 4px 0 0;}
.cover-actions {display: flex; gap: 6px; flex-wrap: wrap;}
.cover-actions .el-button + .el-button {margin-left: 0;}
.article-card-preview {border: 1px solid var(--admin-line); border-radius: var(--admin-radius); background: var(--admin-panel); overflow: hidden;}
.preview-label {font-size: 11px; color: var(--admin-muted); padding: 14px 16px; margin: 0;}
.article-card-preview > img {width: 100%; height: 160px; object-fit: cover;}
.card-preview-copy {padding: 16px;}
.card-preview-copy > span {font-size: 11px; color: var(--admin-accent);}
.card-preview-copy strong {display: block; font-size: 16px; line-height: 1.5; margin: 10px 0;}
.card-preview-copy p {font-size: 12px; line-height: 1.7; color: var(--admin-muted); margin: 0; overflow-wrap: anywhere;}
.editor-load-error {text-align: center; padding: 40px; border: 1px solid var(--admin-line); border-radius: 14px; color: var(--admin-danger); background: var(--admin-panel);}
@media (max-width: 1100px) {.authoring-layout {grid-template-columns: 1fr;} .authoring-sidebar {display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px;} .article-card-preview {grid-column: 1/-1;} .article-card-preview > img {height: 200px;} }
@media (max-width: 640px) {.authoring-sidebar {display: block;} .article-card-preview {margin-top: 16px;}}
</style>
