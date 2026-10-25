const fields = ['metadataWrite', 'metadataVersionRequired', 'mediaUpload', 'mediaReplace', 'mediaDelete', 'catalogDelete']
const limits = Object.freeze({audioBytes: 104857600, coverBytes: 10485760, lyricsBytes: 524288, videoBytes: 209715200, chunkBytes: 16000000})
const valid = raw => raw && fields.every(field => typeof raw[field] === 'boolean')
export function validateUploadCapabilities(raw) {
    if (!valid(raw) || raw.metadataVersionRequired !== true || raw.uploadProtocol !== 'tracked-v1' || !raw.uploadLimits) return null
    if (Object.keys(limits).some(key => !Number.isSafeInteger(raw.uploadLimits[key]) || raw.uploadLimits[key] !== limits[key])) return null
    return {protocol: 'tracked-v1', limits: {...limits}}
}
export const canUseLegacyUploads = raw => Boolean(valid(raw) && raw.metadataVersionRequired === false && raw.uploadProtocol === null && raw.uploadLimits === null)
