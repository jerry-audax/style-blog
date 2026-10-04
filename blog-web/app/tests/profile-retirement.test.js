import {describe, expect, it} from 'vitest'
import * as api from '../src/api'
import source from '../src/views/ProfileView.vue?raw'

describe('Profile editing belongs to admin only', () => {
    it('keeps the old component as a blog redirect without an editor or account dependencies', () => {
        expect(source).toContain('StaticBlogRedirect')
        expect(source).not.toMatch(/<input|<form|updateProfile|changePassword|uploadAvatar|useAuthStore/)
    })
    it('does not export personal-information or avatar mutations in the public API', () => {
        for (const name of ['updateProfile', 'changePassword', 'uploadAvatar', 'me']) {
            expect(api[name]).toBeUndefined()
        }
    })
})
