export const imageAccept = 'image/jpeg,image/png,image/gif,image/webp'
export const imageLimit = purpose => purpose === 'avatar' ? 2 * 1024 * 1024 : 10 * 1024 * 1024

export function validateImageUpload(file, purpose = 'article') {
    if (!['article', 'cover', 'avatar'].includes(purpose)) throw new Error('无效图片用途')
    if (!file || !imageAccept.split(',').includes(file.type) || !Number.isFinite(file.size) || file.size <= 0 || file.size > imageLimit(purpose))
        throw new Error(`请选择 ${purpose === 'avatar' ? '2' : '10'} MiB 以内的非空 JPEG / PNG / GIF / WebP 图片`)
}

export function isSafeImageUrl(value, allowRelative = false) {
    if (typeof value !== 'string' || !value || value.length > 4096 || /[\u0000-\u0020\u007f\\]/.test(value)) return false
    if (allowRelative && value.startsWith('/') && !value.startsWith('//')) return true
    try { const url = new URL(value); return ['https:','http:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password } catch { return false }
}
export const imageKey = image => image.assetId || image.path
export const imageName = image => image.fileName || image.path?.split('/').at(-1) || '图片'
export const imageFileSize = image => Object.hasOwn(image,'fileSize') ? image.fileSize : image.size ?? null
export const imageMime = image => image.mimeType || image.mediaType || '类型未知'
export const imagePurposeLabel = image => {
    const path = typeof image === 'string' ? image : image?.path
    return !path ? '图片' : path.includes('/avatars/') ? '头像' : path.includes('/covers/') ? '封面' : '正文'
}
export const formatImageSize = size => size == null ? '大小未知' : size === 0 ? '0 B' : size < 1024 * 1024 ? `${(size / 1024).toFixed(1)} KiB` : `${(size / 1024 / 1024).toFixed(1)} MiB`
