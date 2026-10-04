import {createRouter, createWebHistory} from 'vue-router'

const routes = [
    ...['/login', '/register', '/profile', '/ai'].map(path => ({path, redirect: '/'})),
    {path: '/', component: () => import('../views/StaticBlogRedirect.vue'), meta: {guest: true, title: '主页'}},
    {
        path: '/article/:id',
        component: () => import('../views/StaticBlogRedirect.vue'),
        meta: {guest: true, title: '文章详情'}
    },
    {
        path: '/:pathMatch(.*)*',
        component: () => import('../views/NotFoundView.vue'),
        meta: {guest: true, title: '页面不存在'}
    },
]
export default createRouter({
    history: createWebHistory(), routes,
    scrollBehavior(to) {
        return to?.hash ? {el: to.hash, top: 88} : {top: 0}
    },
})
