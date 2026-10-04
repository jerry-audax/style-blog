import {beforeEach, describe, expect, it} from 'vitest'
import http from '../src/api/http'
import * as api from '../src/api'

describe('Read-only public client', () => {
    beforeEach(() => localStorage.clear())
    it('does not transmit a former visitor or admin token', async () => {
        localStorage.setItem('blog_token', 'old-visitor-fixture')
        localStorage.setItem('admin_token', 'admin-fixture')
        let config
        await http.get('/api/content/category/list', {
            adapter: async request => {
                config = request
                return {data: {code: 0, data: []}, status: 200, headers: {}, config: request}
            }
        })
        expect(config.headers.Authorization).toBeUndefined()
    })
    it('does not export visitor credential or write APIs', () => {
        for (const name of ['login', 'register', 'sendCode', 'resetPassword', 'loginByPassword', 'me', 'uploadAvatar', 'toggleLike', 'addComment'])
            expect(api[name]).toBeUndefined()
    })
})
