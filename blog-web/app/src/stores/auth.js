import {defineStore} from 'pinia'
import {ref, computed} from 'vue'
import {login as apiLogin, loginByPassword as apiLoginByPassword, register as apiRegister, me} from '../api'

export const useAuthStore = defineStore('auth', () => {
    const token = ref(localStorage.getItem('blog_token') || '')
    const user = ref(null)
    const loading = ref(false)
    const initialized = ref(false)

    const isLoggedIn = computed(() => !!token.value)

    async function fetchMe() {
        if (!token.value) {
            initialized.value = true
            return
        }
        try {
            user.value = await me()
        } catch {
            token.value = ''
            user.value = null
            localStorage.removeItem('blog_token')
        } finally {
            initialized.value = true
        }
    }

    async function loginByPassword(phone, password) {
        loading.value = true
        try {
            const res = await apiLoginByPassword(phone, password)
            token.value = res.token
            localStorage.setItem('blog_token', res.token)
            await fetchMe()
            return res
        } finally {
            loading.value = false
        }
    }

    async function login(phone, code) {
        loading.value = true
        try {
            const res = await apiLogin(phone, code)
            token.value = res.token
            localStorage.setItem('blog_token', res.token)
            await fetchMe()
            return res
        } finally {
            loading.value = false
        }
    }

    async function register(phone, code, password, confirmPassword) {
        loading.value = true
        try {
            const res = await apiRegister(phone, code, password, confirmPassword)
            token.value = res.token
            localStorage.setItem('blog_token', res.token)
            await fetchMe()
            return res
        } finally {
            loading.value = false
        }
    }

    function logout() {
        token.value = ''
        user.value = null
        localStorage.removeItem('blog_token')
    }

    return {token, user, loading, initialized, isLoggedIn, fetchMe, login, loginByPassword, register, logout}
})
