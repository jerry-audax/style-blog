import {beforeEach, describe, expect, it, vi} from 'vitest'

vi.mock('element-plus', () => ({ElMessage: {error: vi.fn()}}))
import http from '../api/http'

describe('Admin authentication errors', () => {
    it.each([
        ['/api/content/article', 409], ['/api/content/article', 503],
        ['/api/auth/profile', 409], ['/api/auth/profile', 503],
    ])('preserves the session and does not retry %s resource error %s', async (route, status) => {
        localStorage.setItem('admin_token', 'reference-fixture')
        const expired = vi.fn()
        window.addEventListener('admin-session-expired', expired)
        let requests = 0
        const message = status === 409 ? '资源引用已变化或尚未登记，请先核对资源后重试' : '资源引用保护暂不可用，请稍后重试'
        try {
            const request = http.request({url: route, method: route.includes('profile') ? 'put' : 'post', data: {}, adapter: async config => {
                requests++
                throw {response: {status, data: {code: status, message}}, config}
            }})
            await expect(request).rejects.toMatchObject({status, message})
            expect(requests).toBe(1)
            expect(expired).not.toHaveBeenCalled()
            expect(localStorage.getItem('admin_token')).toBe('reference-fixture')
            expect(window.location.hash).toBe('#/dashboard')
        } finally { window.removeEventListener('admin-session-expired', expired) }
    })
    it.each(['http','business'])('late %s 401 from an old token cannot expire the new session',async kind=>{
        localStorage.setItem('admin_token','old-session');let finish,started;const ready=new Promise(resolve=>{started=resolve})
        const expired=vi.fn();window.addEventListener('admin-session-expired',expired)
        const request=http.get('/api/auth/me',{adapter:config=>new Promise((resolve,reject)=>{finish=()=>kind==='http'?reject({response:{status:401,data:{message:'old expired'}},config}):resolve({data:{code:401,message:'old expired'},status:200,headers:{},config});started()})})
        await ready;localStorage.setItem('admin_token','new-session');window.location.hash='#/music';finish();await expect(request).rejects.toThrow('old expired')
        expect(localStorage.getItem('admin_token')).toBe('new-session');expect(expired).not.toHaveBeenCalled();expect(window.location.hash).toBe('#/music');window.removeEventListener('admin-session-expired',expired)
    })
    it.each([201,202])('unwraps code0 at HTTP%s without claiming completion', async status => {
        const op=await http.get('/api/admin/media/uploads/id',{adapter:async config=>({data:{code:0,data:{status:'UNKNOWN'}},status,headers:{},config})})
        expect(op.status).toBe('UNKNOWN')
    })
    it('keeps tracked timeout unknown and signals session expiry', async () => {
        let error
        try {await http.put('/api/admin/media/uploads/id/content',{}, {adapter:async config=>{throw {code:'ECONNABORTED',config,message:'timeout'}}})} catch (e) {error=e}
        expect(error.message).toBe('上传结果未确认，请按操作编号核对，勿重复上传')
        const expired=vi.fn();window.addEventListener('admin-session-expired',expired)
        await expect(http.get('/api/admin/media/uploads/id',{adapter:async config=>{throw {response:{status:401,data:{}},config}}})).rejects.toThrow()
        expect(expired).toHaveBeenCalledOnce();window.removeEventListener('admin-session-expired',expired)
    })
    it.each([409, 428, 503])('keeps status %s and the owner session for recoverable catalogue errors', async code => {
        localStorage.setItem('admin_token', 'fixture')
        let failure
        try { await http.put('/api/admin/music/tracks/id', {}, {adapter: async config => {
            throw {response: {status: code, data: {code, message: '资料操作失败'}}, config}
        }}) } catch (error) { failure = error }
        expect(failure.status).toBe(code)
        expect(localStorage.getItem('admin_token')).toBe('fixture')
        expect(window.location.hash).toBe('#/dashboard')
    })
    beforeEach(() => {
        localStorage.clear();
        window.location.hash = '#/dashboard'
    })
    it('handles old HTTP 200/business 401 by clearing and redirecting', async () => {
        localStorage.setItem('admin_token', 'fixture')
        await expect(http.get('/api/admin/logs', {
            adapter: async config => ({
                data: {code: 401, message: '请先登录'},
                status: 200,
                headers: {},
                config
            })
        })).rejects.toThrow('请先登录')
        expect(localStorage.getItem('admin_token')).toBeNull();
        expect(window.location.hash).toBe('#/login')
    })
    it('keeps password rejection on the login form', async () => {
        window.location.hash = '#/login'
        await expect(http.post('/api/auth/login/password', {}, {
            adapter: async config => ({
                data: {
                    code: 401,
                    message: '账号或密码错误'
                }, status: 200, headers: {}, config
            })
        })).rejects.toThrow('账号或密码错误')
        expect(window.location.hash).toBe('#/login')
    })
})
