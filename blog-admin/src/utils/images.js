export const imageAccept = 'image/jpeg,image/png,image/gif,image/webp'
export const imageLimit = purpose => purpose === 'avatar' ? 2 * 1024 * 1024 : 10 * 1024 * 1024

export function validateImageUpload(file, purpose = 'article') {
    if (!['article', 'cover', 'avatar'].includes(purpose)) throw new Error('无效图片用途')
    if (!file || !imageAccept.split(',').includes(file.type) || !Number.isFinite(file.size) || file.size <= 0 || file.size > imageLimit(purpose))
        throw new Error(`请选择 ${purpose === 'avatar' ? '2' : '10'} MiB 以内的非空 JPEG / PNG / GIF / WebP 图片`)
}

export const imagePurposeLabel = path => path.includes('/avatars/') ? '头像' : path.includes('/covers/') ? '封面' : '正文'
export const formatImageSize = size => !size ? '大小未知' : size < 1024 * 1024 ? `${(size / 1024).toFixed(1)} KiB` : `${(size / 1024 / 1024).toFixed(1)} MiB`
