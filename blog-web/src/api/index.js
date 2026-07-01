import http from './http'

// ====== Pagination Helper ======
export const paginationParams = (pageNum = 1, pageSize = 10) => ({ pageNum, pageSize })

// ====== Auth ======
export const sendCode = (phone, bizType) => http.post('/api/auth/sms-code', { phone, bizType })
export const register = (phone, code, password, confirmPassword) => http.post('/api/auth/register', { phone, code, password, confirmPassword })
export const login = (phone, code) => http.post('/api/auth/login', { phone, code })
export const loginByPassword = (phone, password) => http.post('/api/auth/login/password', { phone, password })
export const resetPassword = (phone, code, newPassword) => http.post('/api/auth/password/reset', { phone, code, newPassword })
export const me = () => http.get('/api/auth/me')
export const getUserProfile = me
export const updateProfile = (payload) => http.put('/api/auth/profile', payload)
export const changePassword = (payload) => http.put('/api/auth/password', payload)

// ====== Articles ======
export const getArticleList = (pageNum = 1, pageSize = 10, categoryId, tagId) => http.get('/api/content/article/list', {
  params: { status: 1, pageNum, pageSize, categoryId, tagId }
})
export const articleList = getArticleList
export const getHotArticleList = (limit = 6) => http.get('/api/content/article/hot', { params: { limit } })
export const hotArticleList = getHotArticleList
export const getArticleDetail = (id) => http.get(`/api/content/article/${id}`)
export const articleDetail = getArticleDetail
export const toggleLike = (id) => http.post(`/api/content/article/${id}/like`)

// ====== Categories & Tags ======
export const getCategoryList = () => http.get('/api/content/category/list')
export const categoryList = getCategoryList
export const getTagList = () => http.get('/api/content/tag/list')
export const tagList = getTagList

// ====== Comments ======
export const getCommentList = (articleId) => http.get(`/api/comment/article/${articleId}`)
export const commentList = getCommentList
export const addComment = (payload) => http.post('/api/comment', payload)
export const saveComment = addComment

// ====== File Upload ======
export const uploadFile = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/api/file/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
}
