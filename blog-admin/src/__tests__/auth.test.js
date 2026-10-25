import {beforeEach, describe, expect, it, vi} from 'vitest'
import {setActivePinia, createPinia} from 'pinia'

vi.mock('../api', () => ({loginByPassword: vi.fn(), me: vi.fn(), logout: vi.fn()}))
import {loginByPassword, me, logout} from '../api'
import {useAdminAuthStore} from '../stores/auth'

describe('Single-owner admin session', () => {
    beforeEach(() => {
        vi.resetAllMocks();
        localStorage.clear();
        setActivePinia(createPinia())
    })
    it('starts without an authenticated session', () => {
        const auth = useAdminAuthStore()
        expect(auth.isLoggedIn).toBe(false);
        expect(auth.initialized).toBe(false)
    })
    it('validates owner session after password login', async () => {
        loginByPassword.mockResolvedValue({token: 'fixture'});
        me.mockResolvedValue({owner: true, nickname: 'owner'})
        const auth = useAdminAuthStore()
        await auth.loginByPassword('13900000000', 'fixture')
        expect(auth.isLoggedIn).toBe(true);
        expect(auth.user.owner).toBe(true)
    })
    it('does not swallow failed session restoration after login', async () => {
        loginByPassword.mockResolvedValue({token: 'fixture'});
        me.mockRejectedValue(new Error('expired'))
        const auth = useAdminAuthStore()
        await expect(auth.loginByPassword('13900000000', 'fixture')).rejects.toThrow('expired')
        expect(localStorage.getItem('admin_token')).toBeNull();
        expect(auth.user).toBeNull()
    })
    it('rejects a non-owner current-user response', async () => {
        me.mockResolvedValue({owner: false});
        localStorage.setItem('admin_token', 'former-admin')
        const auth = useAdminAuthStore()
        await expect(auth.fetchMe()).rejects.toThrow('仅博主')
        expect(auth.isLoggedIn).toBe(false)
    })
    it('logout contacts the backend and clears local state even on failure', async () => {
        localStorage.setItem('admin_token', 'fixture');
        logout.mockRejectedValue(new Error('offline'))
        const auth = useAdminAuthStore()
        await expect(auth.logout()).rejects.toThrow('offline')
        expect(logout).toHaveBeenCalledOnce();
        expect(auth.isLoggedIn).toBe(false)
    })
    it('SMS login is not available', () => {
        expect(useAdminAuthStore().login).toBeUndefined()
    })
    it('only exposes upload identity after fetchMe and clears it on HTTP session expiry',async()=>{
        const auth=useAdminAuthStore();expect(auth.verifiedUserId).toBe('');me.mockResolvedValue({owner:true,userId:1});await auth.fetchMe();expect(auth.verifiedUserId).toBe('1')
        window.dispatchEvent(new Event('admin-session-expired'));expect(auth.verifiedUserId).toBe('');expect(auth.user).toBeNull();auth.$dispose()
    })
    it('late profile success cannot restore identity after clear',async()=>{
        localStorage.setItem('admin_token','old');const auth=useAdminAuthStore();let resolve;me.mockReturnValue(new Promise(done=>{resolve=done}));const pending=auth.fetchMe();auth.clear();resolve({owner:true,userId:1})
        await pending.catch(()=>{});expect(auth.verifiedUserId).toBe('');expect(auth.user).toBeNull();expect(auth.initialized).toBe(true);expect(auth.token).toBe('');auth.$dispose()
    })
    it('late old profile failure cannot clear a newly verified session',async()=>{
        localStorage.setItem('admin_token','old');const auth=useAdminAuthStore();let reject;me.mockReturnValueOnce(new Promise((resolve,fail)=>{reject=fail}));const pending=auth.fetchMe().catch(()=>{})
        auth.clear();auth.token='new';localStorage.setItem('admin_token','new');me.mockResolvedValueOnce({owner:true,userId:2});await auth.fetchMe();reject(new Error('old session expired'));await pending
        expect(auth.token).toBe('new');expect(auth.verifiedUserId).toBe('2');expect(auth.user.userId).toBe(2);expect(auth.initialized).toBe(true);auth.$dispose()
    })
    it('new profile verification revokes old verified identity until it finishes',async()=>{
        localStorage.setItem('admin_token','old');const auth=useAdminAuthStore();me.mockResolvedValueOnce({owner:true,userId:1});await auth.fetchMe();let resolve;me.mockReturnValueOnce(new Promise(done=>{resolve=done}));const pending=auth.fetchMe()
        expect(auth.verifiedUserId).toBe('');expect(auth.user).toBeNull();expect(auth.initialized).toBe(false);resolve({owner:true,userId:1});await pending;expect(auth.verifiedUserId).toBe('1');auth.$dispose()
    })
    it('password login revokes previous identity before the new request returns',async()=>{
        localStorage.setItem('admin_token','old');const auth=useAdminAuthStore();me.mockResolvedValueOnce({owner:true,userId:1});await auth.fetchMe();let resolve;loginByPassword.mockReturnValueOnce(new Promise(done=>{resolve=done}));const pending=auth.loginByPassword('13900000000','fixture')
        expect(auth.verifiedUserId).toBe('');expect(auth.user).toBeNull();me.mockResolvedValueOnce({owner:true,userId:2});resolve({token:'new'});await pending;expect(auth.verifiedUserId).toBe('2');auth.$dispose()
    })
    it('clear keeps the login shell renderable while invalidating owner state',()=>{
        const auth=useAdminAuthStore();auth.clear();expect(auth.initialized).toBe(true);expect(auth.user).toBeNull();expect(auth.verifiedUserId).toBe('');auth.$dispose()
    })
})
