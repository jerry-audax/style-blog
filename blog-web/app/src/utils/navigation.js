export function safeRedirect(value) {
    if (
        typeof value !== 'string' ||
        !value.startsWith('/') ||
        value.startsWith('//') ||
        value.includes('\\')
    )
        return '/'
    try {
        const url = new URL(value, window.location.origin)
        return url.origin === window.location.origin ? url.pathname + url.search + url.hash : '/'
    } catch {
        return '/'
    }
}
