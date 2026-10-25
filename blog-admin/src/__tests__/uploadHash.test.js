import {afterEach, expect, it, vi} from 'vitest'
import {webcrypto} from 'node:crypto'
import {sha256ArrayBuffer} from '../media/uploadHashCore'
import {createUploadHasher} from '../media/uploadHash'
afterEach(() => vi.unstubAllGlobals())
it('hashesKnownBytesWithoutMainThreadFallback', async () => {
    vi.stubGlobal('crypto', webcrypto)
    expect(await sha256ArrayBuffer(new Uint8Array([97, 98, 99]).buffer)).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    vi.stubGlobal('crypto', {})
    await expect(sha256ArrayBuffer(new ArrayBuffer(1))).rejects.toThrow()
})
it('singleWorkerIgnoresLateResultAndTerminates', async () => {
    vi.stubGlobal('isSecureContext', true)
    const worker = {postMessage: vi.fn(), terminate: vi.fn()}
    const hasher = createUploadHasher(() => worker)
    const file = new File(['abc'], 'a.mp3', {type: 'audio/mpeg'})
    const pending = hasher.hash(file)
    await expect(hasher.hash(file)).rejects.toThrow()
    const sent = worker.postMessage.mock.calls[0][0]
    expect(sent.file).toBe(file)
    worker.onmessage({data: {requestId: 'wrong', sourceSha256: 'a'.repeat(64)}})
    const assertion = expect(pending).rejects.toThrow()
    hasher.dispose(); await assertion
    worker.onmessage?.({data: {requestId: sent.requestId, sourceSha256: 'a'.repeat(64)}})
    expect(worker.terminate).toHaveBeenCalledOnce()
})
it('rejects unsupported or oversized preprocessing without creating a worker', async () => {
    const factory = vi.fn()
    vi.stubGlobal('isSecureContext', false)
    await expect(createUploadHasher(factory).hash(new File(['x'], 'x.mp3'))).rejects.toThrow(); expect(factory).not.toHaveBeenCalled()
    vi.stubGlobal('isSecureContext', true)
    await expect(createUploadHasher(factory).hash({size: 209715201})).rejects.toThrow(); expect(factory).not.toHaveBeenCalled()
})
