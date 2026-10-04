import http from './http'

// ====== Pagination Helper ======
export const paginationParams = (pageNum = 1, pageSize = 10) => ({pageNum, pageSize})

// ====== Auth ======
export const loginByPassword = (phone, password) => http.post('/api/auth/login/password', {phone, password})
export const me = () => http.get('/api/auth/me')
export const getUserProfile = me

// ====== Dashboard ======
export const getDashboard = () => http.get('/api/admin/dashboard')

// ====== Articles ======
export const getArticleList = (pageNum = 1, pageSize = 10, status) => http.get('/api/content/article/list', {
    params: {
        pageNum,
        pageSize,
        status
    }
})
export const listArticles = getArticleList
export const getArticle = (id) => http.get(`/api/content/article/${id}`)
export const saveArticle = (payload) => http.post('/api/content/article', payload)
export const deleteArticle = (id) => http.delete(`/api/content/article/${id}`)

// ====== Categories ======
export const getCategoryList = () => http.get('/api/content/category/list')
export const listCategories = getCategoryList
export const saveCategory = (payload) => http.post('/api/content/category', payload)
export const deleteCategory = (id) => http.delete(`/api/content/category/${id}`)

// ====== Tags ======
export const getTagList = () => http.get('/api/content/tag/list')
export const listTags = getTagList
export const saveTag = (payload) => http.post('/api/content/tag', payload)
export const deleteTag = (id) => http.delete(`/api/content/tag/${id}`)

// ====== Comments ======
export const getAdminCommentList = (pageNum = 1, pageSize = 10, status) => http.get('/api/comment/admin/list', {
    params: {
        pageNum,
        pageSize,
        status
    }
})
export const listAdminComments = getAdminCommentList
export const auditComment = (id, status) => http.post(`/api/comment/admin/${id}/audit`, null, {params: {status}})
export const deleteComment = (id) => http.delete(`/api/comment/admin/${id}`)

// ImgBed credentials stay in Spring Boot; the browser sends only its blog session.
export const uploadImage = (file, purpose = 'article') => {
    if (!['article', 'cover', 'avatar'].includes(purpose)) throw new Error('无效图片用途')
    const form = new FormData()
    form.append('file', file)
    return http.post(`/api/images/${purpose}`, form, {timeout: 45000})
}
export const listImages = (offset = 0, count = 20, search = '') =>
    http.get('/api/images', {params: {offset, count, search}})
export const deleteImage = (path) => http.delete('/api/images', {data: {path}, timeout: 45000})
export const logout = () => http.post('/api/auth/logout')
export const updateProfile = payload => http.put('/api/auth/profile', payload)
export const changePassword = payload => http.put('/api/auth/password', payload)
export const uploadAvatar = file => uploadImage(file, 'avatar')

// Official CLI authorization is backend-only; no AppID/key/token in frontend env.
export const getMusicStatus = () => http.get('/api/admin/music/status', {timeout: 65000})
export const getMusicPlaylist = () => http.get('/api/admin/music/playlist')
export const authorizeMusic = () => http.post('/api/admin/music/authorize', null, {timeout: 65000})
export const syncMusic = () => http.post('/api/admin/music/sync', null, {timeout: 195000})
export const revokeMusic = () => http.delete('/api/admin/music/authorization', {timeout: 65000})
export const getMusicCatalog = () => http.get('/api/admin/music/catalog', {timeout: 65000})
export const createMusicTrack = payload => http.post('/api/admin/music/tracks', payload)
export const updateMusicTrack = (id, payload) => http.put(`/api/admin/music/tracks/${encodeURIComponent(id)}`, payload)
export const deleteMusicTrack = id => http.delete(`/api/admin/music/tracks/${encodeURIComponent(id)}`)
export const uploadMusicMedia = (id, kind, file) => {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/api/admin/music/tracks/${encodeURIComponent(id)}/media/${kind}`, form, {timeout: 120000})
}
export const deleteMusicMedia = (id, kind) => http.delete(`/api/admin/music/tracks/${encodeURIComponent(id)}/media/${kind}`, {timeout: 65000})

// Surprise videos are stored in the same Cloudflare ImgBed/Telegram backend.
export const getSurpriseVideos = () => http.get('/api/admin/surprise/videos', {timeout: 65000})
export const createSurpriseVideo = (file, title = '惊喜视频', enabled = true, sortOrder = 0) => {
    const form = new FormData()
    form.append('file', file)
    form.append('title', title)
    form.append('enabled', String(enabled))
    form.append('sortOrder', String(sortOrder))
    return http.post('/api/admin/surprise/videos', form, {timeout: 240000})
}
export const updateSurpriseVideo = (id, payload) => http.put(`/api/admin/surprise/videos/${encodeURIComponent(id)}`, payload)
export const deleteSurpriseVideo = id => http.delete(`/api/admin/surprise/videos/${encodeURIComponent(id)}`, {timeout: 65000})
