// These public reads never restore or transmit the management session.
export const validContentId = value => typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n

export async function publicContent(url, signal) {
    const controller = new AbortController()
    const cancel = () => controller.abort()
    if (signal?.aborted) controller.abort()
    else signal?.addEventListener('abort', cancel, {once: true})
    const timer = setTimeout(cancel, 10000)
    try {
        const response = await fetch(url, {signal: controller.signal, credentials: 'omit', cache: 'no-store', redirect: 'error', headers: {Accept: 'application/json'}})
        if (!response.ok) throw new Error('公开内容暂不可用')
        const body = await response.json()
        if (controller.signal.aborted || body?.code !== 0) throw new Error('公开内容暂不可用')
        return body.data
    } finally {clearTimeout(timer); signal?.removeEventListener('abort', cancel)}
}
