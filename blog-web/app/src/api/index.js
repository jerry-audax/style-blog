import http from './http'

// ====== Pagination Helper ======
export const paginationParams = (pageNum = 1, pageSize = 10) => ({pageNum, pageSize})

// ====== Articles ======
export const getArticleList = (pageNum = 1, pageSize = 10, categoryId, tagId) => http.get('/api/content/article/list', {
    params: {status: 1, pageNum, pageSize, categoryId, tagId}
})
export const articleList = getArticleList
export const getHotArticleList = (limit = 6) => http.get('/api/content/article/hot', {params: {limit}})
export const hotArticleList = getHotArticleList
export const getArticleDetail = (id) => http.get(`/api/content/article/${id}`)
export const articleDetail = getArticleDetail

// ====== Categories & Tags ======
export const getCategoryList = () => http.get('/api/content/category/list')
export const categoryList = getCategoryList
export const getTagList = () => http.get('/api/content/tag/list')
export const tagList = getTagList

// ====== Comments ======
export const getCommentList = (articleId) => http.get(`/api/comment/article/${articleId}`)
export const commentList = getCommentList


