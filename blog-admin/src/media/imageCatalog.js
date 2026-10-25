import {isSafeImageUrl} from '../utils/images'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const decimal = value => typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n
const text = (value,max) => typeof value === 'string' && value.trim() && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value)
const date = value => typeof value === 'string' && value.length <= 40 && Number.isFinite(Date.parse(value))
const imageTypes = new Set(['image/jpeg','image/png','image/gif','image/webp'])

function persistedImage(raw) {
    if (!raw || !uuid.test(raw.assetId) || !decimal(raw.assetVersion) || !text(raw.fileName,255) || !isSafeImageUrl(raw.url)
        || raw.status !== 'ACTIVE' || raw.mimeType !== null && !text(raw.mimeType,128)
        || raw.fileSize !== null && (!Number.isSafeInteger(raw.fileSize) || raw.fileSize < 0)
        || !date(raw.createdAt) || !date(raw.updatedAt) || Date.parse(raw.updatedAt) < Date.parse(raw.createdAt))
        throw new Error('图片元数据响应不兼容，请核对后端版本')
    return {assetId:raw.assetId,assetVersion:raw.assetVersion,fileName:raw.fileName,url:raw.url,mimeType:raw.mimeType,fileSize:raw.fileSize,status:raw.status,createdAt:raw.createdAt,updatedAt:raw.updatedAt}
}

export function normalizeImageCatalogPage(raw, capabilities) {
    if (!raw || !Array.isArray(raw.images)) throw new Error('图片列表格式无效')
    if (capabilities?.storage === 'postgres') {
        if (!decimal(raw.total) || BigInt(raw.total) > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('图片总数超出安全分页范围，请缩小搜索条件')
        if (!Number.isSafeInteger(raw.offset) || raw.offset < 0 || raw.offset > 100000 || !Number.isSafeInteger(raw.count) || raw.count < 1 || raw.count > 50 || raw.images.length > raw.count)
            throw new Error('图片分页响应无效')
        const total = Number(raw.total)
        if (raw.images.length && total < raw.offset + raw.images.length) throw new Error('图片分页总数不一致')
        return {images:raw.images.map(persistedImage),total,offset:raw.offset,count:raw.count}
    }
    if (capabilities?.storage !== 'legacy-json' || !Number.isSafeInteger(raw.total) || raw.total < 0 || raw.images.length > 50) throw new Error('图片列表能力或总数无效')
    return {images:raw.images.map(image => {
        if (!text(image?.path,512) || !isSafeImageUrl(image.url,true)) throw new Error('旧图片列表格式无效')
        return {...image}
    }),total:raw.total}
}

export function validateImageUploadResult(result, operation) {
    if (result?.targetType !== 'IMAGE_CREATE' || result.music != null || result.video != null || !['IMAGE','AVATAR'].includes(operation?.slot))
        throw new Error('图片成功回执类型不匹配')
    const image = persistedImage(result.image)
    if (image.assetId !== operation.targetId || !imageTypes.has(image.mimeType)) throw new Error('图片成功回执身份或类型不匹配')
    return image
}
