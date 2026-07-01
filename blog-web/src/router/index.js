import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'

const routes = [
  {
    path: '/login',
    component: LoginView,
    meta: { guest: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import(/* webpackChunkName: "article-list" */ '../views/ArticleListView.vue'),
    meta: { guest: true, title: '首页' }
  },
  {
    path: '/article/:id',
    component: () => import(/* webpackChunkName: "article-detail" */ '../views/ArticleDetailView.vue'),
    meta: { guest: true, title: '文章详情' }
  },
  {
    path: '/profile',
    component: () => import(/* webpackChunkName: "profile" */ '../views/ProfileView.vue'),
    meta: { requiresAuth: true, title: '个人中心' }
  },
  {
    path: '/ai',
    component: () => import(/* webpackChunkName: "ai-chat" */ '../views/AiChatView.vue'),
    meta: { guest: true, title: 'AI 对话' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

// Navigation guard for auth-protected routes
router.beforeEach((to) => {
  const token = localStorage.getItem('blog_token')

  // Protected routes — require authentication
  if (to.meta.requiresAuth) {
    if (!token) {
      return '/login'
    }
  }

  // Guest-only route (login page) — redirect to home if already logged in
  if (to.meta.guest && to.path === '/login' && token) {
    return '/'
  }

  return true
})

export default router
