import {afterEach, beforeEach, expect, it, vi} from 'vitest'
import {rememberUpload, readTrackedUploads, forgetActiveUploads} from '../media/uploadTracking'
const hint = {operationId: '00000000-0000-0000-0000-000000000501', targetType: 'MUSIC_TRACK', targetId: 'song', slot: 'AUDIO', createdAt: '2026-10-14T00:00:00Z'}
beforeEach(() => sessionStorage.clear())
afterEach(() => vi.restoreAllMocks())
it('stores only minimal owner-scoped hints and keeps other namespaces', () => {
    rememberUpload('1', {...hint, token: 'must-not-save', sourceSha256: 'must-not-save', file: new Blob(['x'])})
    rememberUpload('2', hint)
    expect(readTrackedUploads('1')).toEqual([hint]); expect(JSON.stringify(sessionStorage)).not.toContain('must-not-save')
    forgetActiveUploads('1'); expect(readTrackedUploads('1')).toEqual([]); expect(readTrackedUploads('2')).toEqual([hint])
})
it('bounds history and rejects malformed or credential namespaces', () => {
    for (let i = 1; i <= 25; i++) rememberUpload('1', {...hint, operationId: `00000000-0000-0000-0000-${String(i).padStart(12, '0')}`})
    expect(readTrackedUploads('1')).toHaveLength(20)
    rememberUpload('Bearer token', hint); expect(readTrackedUploads('Bearer token')).toEqual([])
    for (const key of Object.keys(sessionStorage)) sessionStorage.setItem(key, '{bad-json')
    expect(readTrackedUploads('1')).toEqual([])
})
it('storageFailureDoesNotRetryUpload', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {throw new Error('denied')})
    expect(() => rememberUpload('1', hint)).not.toThrow()
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {throw new Error('denied')})
    expect(readTrackedUploads('1')).toEqual([]); expect(() => forgetActiveUploads('1')).not.toThrow()
})
