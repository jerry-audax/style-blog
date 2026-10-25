import {defineStore} from 'pinia'
import {ref, computed, onScopeDispose} from 'vue'
import {loginByPassword as apiLogin, me as apiMe, logout as apiLogout} from '../api'
import {forgetActiveUploads} from '../media/uploadTracking'

export const useAdminAuthStore = defineStore('adminAuth', () => {
    const K = 'admin_token'
    const token = ref(localStorage.getItem(K) || ''), user = ref(null), initialized = ref(false)
    const isLoggedIn = computed(() => !!token.value)
    const verifiedUserId = ref('')
    let sessionGeneration = 0, profileRequest = 0

    function revokeProfile() {
        if (verifiedUserId.value) forgetActiveUploads(verifiedUserId.value)
        verifiedUserId.value = ''
        user.value = null
        initialized.value = false
    }

    function clear() {
        sessionGeneration++; profileRequest++
        revokeProfile()
        token.value = '';
        localStorage.removeItem(K)
        // Logged out is a known state: App.vue must still render the login route.
        initialized.value = true
    }

    async function fetchMe() {
        const generation = sessionGeneration, request = ++profileRequest, sessionToken = token.value
        const storedToken = localStorage.getItem(K) || ''
        const current = () => generation === sessionGeneration && request === profileRequest && token.value === sessionToken && (localStorage.getItem(K) || '') === storedToken
        revokeProfile()
        try {
            const result = await apiMe()
            if (!current()) throw new Error('会话已变化，请重新验证')
            if (result?.owner !== true) throw new Error('仅博主账号可访问管理功能')
            user.value = result
            const id = typeof result.userId === 'number' && Number.isSafeInteger(result.userId) ? String(result.userId) : result.userId
            verifiedUserId.value = typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n ? id : ''
            return result
        } catch (err) {
            if (current()) { clear(); initialized.value = true }
            throw err
        } finally {
            if (current()) initialized.value = true
        }
    }

    async function loginByPassword(phone, password) {
        const generation = ++sessionGeneration
        profileRequest++; revokeProfile()
        try {
            const result = await apiLogin(phone, password)
            if (generation !== sessionGeneration) throw new Error('会话已变化，请重新登录')
            token.value = result.token;
            localStorage.setItem(K, result.token)
            await fetchMe()
            return result
        } catch (err) {
            if (generation === sessionGeneration) { clear(); initialized.value = true }
            throw err
        }
    }

    async function logout() {
        try {
            if (token.value) await apiLogout()
        } finally {
            clear()
        }
    }

    window.addEventListener('admin-session-expired', clear)
    onScopeDispose(() => window.removeEventListener('admin-session-expired', clear))
    return {token, user, initialized, verifiedUserId, isLoggedIn, loginByPassword, logout, fetchMe, clear}
})
