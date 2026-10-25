import {describe, it, expect, vi} from 'vitest'

// Mock http module before importing from api to avoid axios/location/env side effects
vi.mock('../api/http', () => ({
    default: {
        get: vi.fn(),
        post: vi.fn(),
        put: vi.fn(),
        delete: vi.fn(),
    }
}))

import {
    paginationParams,
    uploadImage,
    listImages,
    deleteImage,
    uploadAvatar,
    updateProfile,
    changePassword,
    getMediaCapabilities,
    updateMusicTrack,
    updateSurpriseVideo,
    createMediaUploadOperation, putMediaUploadContent, getMediaUploadOperation, listMediaUploadOperations,
    pauseMediaUploadOperation, recoverMediaUploadOperation, bindMediaUploadOperation
} from '../api'
import http from '../api/http'
import {legacyImageCapabilities} from './fixtures/mediaCapabilities'

describe('tracked upload contracts', () => {
    it('operationVersionRemainsDecimalString and all seven endpoints use only the blog backend', () => {
        const id='id/with space', route='/api/admin/media/uploads/id%2Fwith%20space', version='9007199254740993'
        const payload={operationId:'uuid',expectedVersion:0}
        createMediaUploadOperation(payload); expect(http.post).toHaveBeenLastCalledWith('/api/admin/media/uploads',payload,expect.objectContaining({silent:true}))
        const progress=vi.fn(),file=new Blob(['audio'],{type:'audio/mpeg'})
        putMediaUploadContent(id,file,{onUploadProgress:progress}); const [url,form,config]=http.put.mock.calls.at(-1)
        expect(url).toBe(route+'/content');expect([...form.keys()]).toEqual(['file']);expect(config.timeout).toBe(1800000);expect(config.onUploadProgress).toBe(progress);expect(config.headers).toBeUndefined()
        getMediaUploadOperation(id);expect(http.get).toHaveBeenLastCalledWith(route,expect.objectContaining({silent:true}))
        listMediaUploadOperations();expect(http.get).toHaveBeenLastCalledWith('/api/admin/media/uploads',expect.objectContaining({params:{offset:0,limit:20}}))
        pauseMediaUploadOperation(id,version);expect(http.post).toHaveBeenLastCalledWith(route+'/pause',{operationVersion:version},expect.any(Object))
        recoverMediaUploadOperation(id,version);expect(http.post).toHaveBeenLastCalledWith(route+'/recover',{operationVersion:version},expect.objectContaining({timeout:600000}))
        bindMediaUploadOperation(id,{newOperationId:'new',expectedVersion:0,operationVersion:version});expect(http.post).toHaveBeenLastCalledWith(route+'/bind',{newOperationId:'new',expectedVersion:0,operationVersion:version},expect.any(Object))
    })
})

describe('versioned catalogue API', () => {
    it('reads capabilities only through the owner backend', () => {
        getMediaCapabilities()
        expect(http.get).toHaveBeenLastCalledWith('/api/admin/media/capabilities')
    })
    it('preserves version zero and encodes historical IDs without trimming', () => {
        const payload = {title: '歌曲', expectedVersion: 0}
        updateMusicTrack('  视频 ID  ', payload)
        expect(http.put).toHaveBeenLastCalledWith('/api/admin/music/tracks/%20%20%E8%A7%86%E9%A2%91%20ID%20%20', payload)
        updateSurpriseVideo('video/id', {title: '视频', expectedVersion: 7})
        expect(http.put).toHaveBeenLastCalledWith('/api/admin/surprise/videos/video%2Fid', {title: '视频', expectedVersion: 7})
    })
})

describe('paginationParams', () => {
    it('returns defaults when no arguments given', () => {
        expect(paginationParams()).toEqual({pageNum: 1, pageSize: 10})
    })

    it('accepts custom pageNum', () => {
        expect(paginationParams(5)).toEqual({pageNum: 5, pageSize: 10})
    })

    it('accepts custom pageNum and pageSize', () => {
        expect(paginationParams(3, 20)).toEqual({pageNum: 3, pageSize: 20})
    })

    it('accepts pageSize only via explicit arguments', () => {
        expect(paginationParams(1, 50)).toEqual({pageNum: 1, pageSize: 50})
    })
})

describe('image API contracts', () => {
    it('keeps avatar upload and personal-information editing in the admin API', async () => {
        await uploadAvatar(new Blob(['avatar'], {type: 'image/png'}))
        expect(http.post.mock.calls.at(-1)[0]).toBe('/api/images/avatar')
        const profile = {nickname: 'fixture-owner', email: 'fixture@example.test', avatar: '/file/avatar.png'}
        updateProfile(profile)
        expect(http.put).toHaveBeenLastCalledWith('/api/auth/profile', profile)
        const password = {oldPassword: 'fixture-old', newPassword: 'fixture-new'}
        changePassword(password)
        expect(http.put).toHaveBeenLastCalledWith('/api/auth/password', password)
    })
    it('uploads multipart via backend without setting provider credentials', async () => {
        const file = new Blob(['image'], {type: 'image/png'})
        await uploadImage(file, 'cover')
        const [url, form, options] = http.post.mock.calls.at(-1)
        expect(url).toBe('/api/images/cover')
        expect(form.get('file')).toBeInstanceOf(Blob)
        expect(options.timeout).toBeGreaterThan(30000)
        expect(options.headers).toBeUndefined()
    })
    it('list is bounded and deletion targets one decoded object key', async () => {
        http.get.mockResolvedValueOnce({images:[],total:0})
        await listImages(20, 20, 'photo',legacyImageCapabilities.images)
        expect(http.get).toHaveBeenLastCalledWith('/api/images', {params: {offset: 20, count: 20, search: 'photo'}})
        deleteImage('blog/covers/a.png')
        expect(http.delete).toHaveBeenLastCalledWith('/api/images', expect.objectContaining({data: {path: 'blog/covers/a.png'}}))
    })
    it('rejects non-purpose upload routes', () => {
        expect(() => uploadImage(new Blob(), '../manage')).toThrow()
    })
})
