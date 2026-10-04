import {defineStore} from 'pinia'
import {ref, computed} from 'vue'
import {loginByPassword as apiLogin, me as apiMe, logout as apiLogout} from '../api'

export const useAdminAuthStore = defineStore('adminAuth', () => {
    const K = 'admin_token'
    const token = ref(localStorage.getItem(K) || ''), user = ref(null), initialized = ref(false)
    const isLoggedIn = computed(() => !!token.value)

    function clear() {
        token.value = '';
        user.value = null;
        localStorage.removeItem(K)
    }

    async function fetchMe() {
        try {
            const result = await apiMe()
            if (result?.owner !== true) throw new Error('仅博主账号可访问管理功能')
            user.value = result
            return result
        } catch (err) {
            clear();
            throw err
        } finally {
            initialized.value = true
        }
    }

    async function loginByPassword(phone, password) {
        const result = await apiLogin(phone, password)
        token.value = result.token;
        localStorage.setItem(K, result.token)
        await fetchMe()
        return result
    }

    async function logout() {
        try {
            if (token.value) await apiLogout()
        } finally {
            clear()
        }
    }

    return {token, user, initialized, isLoggedIn, loginByPassword, logout, fetchMe, clear}
})
