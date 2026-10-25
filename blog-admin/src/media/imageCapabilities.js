/** Absence is unknown support, never permission to call the legacy upload API. */
export function validateImageCapabilities(raw) {
    const c = raw?.images
    if (!c || typeof c.uploadEnabled !== 'boolean' || typeof c.deleteEnabled !== 'boolean' || c.avatarBytes !== 2097152 || c.imageBytes !== 10485760) return null
    if (c.storage === 'postgres') {
        if (c.deleteEnabled || c.uploadProtocol !== (c.uploadEnabled ? 'tracked-image-v1' : null)) return null
    } else if (c.storage === 'legacy-json') {
        if (c.uploadProtocol !== 'legacy') return null
    } else return null
    return {storage:c.storage, uploadProtocol:c.uploadProtocol, uploadEnabled:c.uploadEnabled, deleteEnabled:c.deleteEnabled, avatarBytes:c.avatarBytes, imageBytes:c.imageBytes}
}
