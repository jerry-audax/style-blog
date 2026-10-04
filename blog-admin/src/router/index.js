import {createRouter, createWebHashHistory} from 'vue-router'
import LoginView from '../views/LoginView.vue'

const routes = [
    {
        path: '/music',
        component: () => import('../views/MusicManage.vue'),
        meta: {requiresAuth: true, title: '音乐管理'}
    },
    {
        path: '/surprise',
        component: () => import('../views/SurpriseManage.vue'),
        meta: {requiresAuth: true, title: '惊喜视频'}
    },
    {
        path: '/login',
        component: LoginView,
        meta: {guest: true, title: '登录'}
    },
    {
        path: '/',
        redirect: '/dashboard'
    },
    {
        path: '/dashboard',
        component: () => import(/* webpackChunkName: "dashboard" */ '../views/Dashboard.vue'),
        meta: {requiresAuth: true, title: '仪表盘'}
    },
    {
        path: '/articles',
        component: () => import(/* webpackChunkName: "article" */ '../views/ArticleManage.vue'),
        meta: {requiresAuth: true, title: '文章管理'}
    },
    {
        path: '/article/new',
        component: () => import(/* webpackChunkName: "article-edit" */ '../views/ArticleEdit.vue'),
        meta: {requiresAuth: true, title: '新建文章'}
    },
    {
        path: '/article/:id/edit',
        component: () => import(/* webpackChunkName: "article-edit" */ '../views/ArticleEdit.vue'),
        meta: {requiresAuth: true, title: '编辑文章'}
    },
    {
        path: '/comments',
        component: () => import(/* webpackChunkName: "comment" */ '../views/CommentManage.vue'),
        meta: {requiresAuth: true, title: '评论管理'}
    },
    {
        path: '/categories',
        component: () => import(/* webpackChunkName: "category" */ '../views/CategoryManage.vue'),
        meta: {requiresAuth: true, title: '分类管理'}
    },
    {
        path: '/tags',
        component: () => import(/* webpackChunkName: "tag" */ '../views/TagManage.vue'),
        meta: {requiresAuth: true, title: '标签管理'}
    },
    {
        path: '/users',
        redirect: '/account'
    },
    {
        path: '/account',
        component: () => import('../views/OwnerAccountView.vue'),
        meta: {requiresAuth: true, title: '博主设置'}
    },
    {
        path: '/logs',
        component: () => import(/* webpackChunkName: "log" */ '../views/LogManage.vue'),
        meta: {requiresAuth: true, title: '操作日志'}
    },
    {
        path: '/images',
        component: () => import('../views/ImageManage.vue'),
        meta: {requiresAuth: true, title: '图片管理'}
    }
]

const router = createRouter({
    history: createWebHashHistory(),
    routes
})

// Token validation cache — avoids calling /api/auth/me on every navigation
let lastVerified = 0
let lastCheckedToken = ''
let lastValid = false
const CACHE_DURATION = 30_000 // 30 seconds

export async function validateToken(token) {
    try {
        const res = await fetch('/api/auth/me', {
            headers: {Authorization: token}
        })
        const body = await res.json()
        if (res.ok && body.code === 0 && body.data?.owner === true) return {valid: true}
        return {valid: false, status: body.code === 401 || body.code === 403 ? body.code : res.ok ? 403 : res.status}
    } catch {
        // Network error — return undefined status so caller can decide
        return {valid: false, status: -1}
    }
}

function isCacheFresh(token) {
    return Date.now() - lastVerified < CACHE_DURATION && token === lastCheckedToken
}

router.beforeEach(async (to) => {
    const token = localStorage.getItem('admin_token')
    const now = Date.now()

    // Protected routes — require server-verified authentication
    if (to.meta.requiresAuth) {
        if (!token) {
            return '/login'
        }

        // Use cached result when fresh and token hasn't changed
        if (isCacheFresh(token)) {
            return lastValid ? true : '/login'
        }

        // Validate token against server
        const {valid, status} = await validateToken(token)
        lastVerified = now
        lastCheckedToken = token

        if (valid) {
            lastValid = true
            return true
        }

        // 401/403: token is definitively invalid — clear and redirect
        if (status === 401 || status === 403) {
            localStorage.removeItem('admin_token')
            lastValid = false
            return '/login'
        }

        // Keep the token for retry, but do not treat an unverified session as valid.
        lastValid = false
        return '/login'
    }

    // Guest-only route (login page) — validate existing token to avoid
    // redirecting the user into a requiresAuth guard with an expired token
    if (to.meta.guest && token) {
        if (isCacheFresh(token)) {
            return lastValid ? '/' : true
        }

        const {valid, status} = await validateToken(token)
        lastVerified = now
        lastCheckedToken = token

        if (valid) {
            lastValid = true
            return '/' // token is good — redirect away from login
        }

        // Token is invalid — stay on login page
        lastValid = false
        if (status === 401 || status === 403) {
            localStorage.removeItem('admin_token')
        }
        return true
    }

    return true
})

export default router
