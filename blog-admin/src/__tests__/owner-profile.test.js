import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {createApp, h, nextTick} from 'vue'
import {createPinia, setActivePinia} from 'pinia'
import {legacyImageCapabilities} from './fixtures/mediaCapabilities'

vi.mock('vue-router', () => ({useRouter: () => ({push: vi.fn()})}))
vi.mock('../api', () => ({
    changePassword: vi.fn(), updateProfile: vi.fn(), uploadImage: vi.fn(),getMediaCapabilities:vi.fn(),
    loginByPassword: vi.fn(), me: vi.fn(), logout: vi.fn(),
}))
import {updateProfile, uploadImage,getMediaCapabilities} from '../api'
import {useAdminAuthStore} from '../stores/auth'
import OwnerAccountView from '../views/OwnerAccountView.vue'

let app, host, auth
const owner = {
    userId:1,
    owner: true,
    phone: '13900000000',
    username: 'fixture-owner',
    nickname: '原昵称',
    email: 'fixture@example.test',
    avatar: '/old-avatar.png'
}

async function mount(user = owner) {
    const pinia = createPinia();
    setActivePinia(pinia)
    auth = useAdminAuthStore();
    auth.user = user
    auth.initialized=true;auth.verifiedUserId=user?'1':''
    host = document.createElement('div');
    document.body.append(host)
    app = createApp(OwnerAccountView);
    app.use(pinia)
    app.component('RouterLink', {setup: (_, {slots}) => () => h('a', {}, slots.default?.())})
    app.mount(host);
    await nextTick()
}

async function selectAvatar(file) {
    const input = host.querySelector('#profile-avatar')
    Object.defineProperty(input, 'files', {value: [file], configurable: true})
    input.dispatchEvent(new Event('change', {bubbles: true}))
    await flush()
}

async function flush() {
    await new Promise(resolve => setTimeout(resolve, 0));
    await nextTick()
}

const button = name => [...host.querySelectorAll('button')].find(node => node.textContent.trim() === name)
beforeEach(() => {
    vi.resetAllMocks();
    getMediaCapabilities.mockResolvedValue(legacyImageCapabilities)
    localStorage.clear()
})
afterEach(() => {
    app?.unmount();
    host?.remove()
})

describe('Admin owner profile and avatar editor', () => {
    it.each([409, 503])('keeps uploaded avatar and edited fields after resource protection HTTP %s without retrying', async status => {
        localStorage.setItem('admin_token', 'reference-fixture')
        await mount()
        const nickname = host.querySelector('#profile-nickname')
        nickname.value = '尚未保存的昵称'
        nickname.dispatchEvent(new Event('input', {bubbles: true}))
        const email = host.querySelector('#profile-email')
        email.value = 'draft@example.test'
        email.dispatchEvent(new Event('input', {bubbles: true}))
        uploadImage.mockResolvedValue({url: '/pending-avatar.png'})
        await selectAvatar(new File(['image'], 'avatar.png', {type: 'image/png'}))
        const message = status === 409 ? '资源引用已变化或尚未登记，请先核对资源后重试' : '资源引用保护暂不可用，请稍后重试'
        updateProfile.mockRejectedValueOnce(Object.assign(new Error(message), {status}))
            .mockResolvedValueOnce({...owner, nickname: '尚未保存的昵称', email: 'draft@example.test', avatar: '/pending-avatar.png'})
        button('保存修改').click(); await flush()
        expect(updateProfile).toHaveBeenCalledOnce()
        expect(auth.user.avatar).toBe(owner.avatar)
        expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('/pending-avatar.png')
        expect(host.querySelector('#profile-nickname').value).toBe('尚未保存的昵称')
        expect(host.querySelector('#profile-email').value).toBe('draft@example.test')
        expect(host.querySelector('.msg.error').textContent).toBe(message)
        expect(host.textContent).not.toContain('博主资料已更新')
        expect(auth.token).toBe('reference-fixture')
        expect(localStorage.getItem('admin_token')).toBe('reference-fixture')
        expect(uploadImage).toHaveBeenCalledOnce()
        expect(button('保存修改').disabled).toBe(false)
        button('保存修改').click(); await flush()
        expect(updateProfile).toHaveBeenCalledTimes(2)
        expect(uploadImage).toHaveBeenCalledOnce()
        expect(auth.user.avatar).toBe('/pending-avatar.png')
    })
    it('initializes profile fields even when the owner is restored after mounting', async () => {
        await mount(null);
        auth.user = {...owner};
        await nextTick()
        expect(host.querySelector('#profile-nickname').value).toBe(owner.nickname)
        expect(host.querySelector('#profile-email').value).toBe(owner.email)
    })
    it('previews an uploaded avatar without saving until the owner confirms', async () => {
        await mount();
        uploadImage.mockResolvedValue({url: '/new-avatar.png'})
        await selectAvatar(new File(['image'], 'avatar.png', {type: 'image/png'}))
        expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('/new-avatar.png')
        expect(auth.user.avatar).toBe('/old-avatar.png')
        expect(updateProfile).not.toHaveBeenCalled()
        updateProfile.mockResolvedValue({...owner, avatar: '/new-avatar.png'})
        button('保存修改').click();
        await flush()
        expect(updateProfile).toHaveBeenCalledWith({
            nickname: owner.nickname,
            email: owner.email,
            avatar: '/new-avatar.png'
        })
        expect(auth.user.avatar).toBe('/new-avatar.png')
    })
    it('can discard a pending avatar without saving or deleting a remote image', async () => {
        await mount();
        uploadImage.mockResolvedValue({url: '/new-avatar.png'})
        await selectAvatar(new File(['image'], 'avatar.png', {type: 'image/png'}))
        expect(button('撤销更换')).toBeDefined()
        button('撤销更换').click();
        await nextTick()
        expect(host.querySelector('.avatar-img').getAttribute('src')).toBe(owner.avatar)
        expect(updateProfile).not.toHaveBeenCalled()
    })
    it('rejects an unsupported, empty or oversized file before uploading', async () => {
        await mount()
        for (const file of [new File(['svg'], 'avatar.svg', {type: 'image/svg+xml'}), new File([], 'empty.png', {type: 'image/png'}), new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'large.png', {type: 'image/png'})]) {
            await selectAvatar(file)
        }
        expect(uploadImage).not.toHaveBeenCalled()
        expect(host.querySelector('.avatar-img').getAttribute('src')).toBe(owner.avatar)
    })
    it('keeps the original avatar and reports a failed upload', async () => {
        await mount();
        uploadImage.mockRejectedValue(new Error('图床暂时不可用'))
        await selectAvatar(new File(['image'], 'avatar.png', {type: 'image/png'}))
        expect(host.textContent).toContain('图床暂时不可用')
        expect(host.querySelector('.avatar-img').getAttribute('src')).toBe(owner.avatar)
        expect(button('保存修改').disabled).toBe(false)
    })
    it('retains a pending avatar after save failure, allowing save without reupload', async () => {
        await mount();
        uploadImage.mockResolvedValue({url: '/new-avatar.png'})
        await selectAvatar(new File(['image'], 'avatar.png', {type: 'image/png'}))
        updateProfile.mockRejectedValueOnce(new Error('保存失败')).mockResolvedValueOnce({
            ...owner,
            avatar: '/new-avatar.png'
        })
        button('保存修改').click();
        await flush()
        expect(auth.user.avatar).toBe(owner.avatar)
        expect(host.querySelector('.avatar-img').getAttribute('src')).toBe('/new-avatar.png')
        button('保存修改').click();
        await flush()
        expect(uploadImage).toHaveBeenCalledOnce();
        expect(auth.user.avatar).toBe('/new-avatar.png')
    })
})
