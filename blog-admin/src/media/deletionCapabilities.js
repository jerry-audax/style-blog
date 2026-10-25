const fields = ['metadataWrite', 'metadataVersionRequired', 'mediaUpload', 'mediaReplace', 'mediaDelete', 'catalogDelete']
export function validateDeletionCapabilities(raw) {
    if (!raw || raw.deleteProtocol !== 'tracked-delete-v1' || raw.metadataVersionRequired !== true || fields.some(key => typeof raw[key] !== 'boolean')) return null
    return {protocol: 'tracked-delete-v1', mediaDelete: raw.mediaDelete, catalogDelete: raw.catalogDelete}
}
