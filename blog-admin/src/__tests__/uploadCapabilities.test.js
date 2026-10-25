import {expect, it} from 'vitest'
import {validateUploadCapabilities, canUseLegacyUploads} from '../media/uploadCapabilities'
const limits = {audioBytes: 104857600, coverBytes: 10485760, lyricsBytes: 524288, videoBytes: 209715200, chunkBytes: 16000000}
const raw = {metadataWrite: true, metadataVersionRequired: true, mediaUpload: true, mediaReplace: true, mediaDelete: false, catalogDelete: false, uploadProtocol: 'tracked-v1', uploadLimits: limits}
it('unknownProtocolAndMalformedLimitsDisableUploads', () => {
    expect(validateUploadCapabilities(raw)).toEqual({protocol: 'tracked-v1', limits})
    for (const value of [null, {}, {...raw, uploadProtocol: 'v2'}, {...raw, uploadLimits: {...limits, chunkBytes: 16777216}}, {...raw, uploadLimits: {...limits, videoBytes: '209715200'}}, {...raw, metadataVersionRequired: false}, {...raw, mediaUpload: 'true'}]) {
        expect(validateUploadCapabilities(value)).toBeNull(); expect(canUseLegacyUploads(value)).toBe(false)
    }
    const legacy = {...raw, metadataVersionRequired: false, uploadProtocol: null, uploadLimits: null}
    expect(canUseLegacyUploads(legacy)).toBe(true)
    const {uploadProtocol, uploadLimits, ...incomplete} = legacy
    expect(canUseLegacyUploads(incomplete)).toBe(false)
})
