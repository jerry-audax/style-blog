/** Worker-only full source hashing; no main-thread fallback and no new crypto dependency. */
export async function sha256ArrayBuffer(buffer) {
    if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 1 || buffer.byteLength > 209715200 || !globalThis.crypto?.subtle) throw new Error('无法安全校验文件')
    const digest = await crypto.subtle.digest('SHA-256', buffer)
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}
