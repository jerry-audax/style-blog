export function createUploadHasher(workerFactory = () => new Worker(new URL('../workers/mediaUploadHash.worker.js', import.meta.url), {type: 'module'})) {
    let worker = null, pending = null, stopped = false, counter = 0
    function rejectPending(message) {
        if (!pending) return
        const current = pending; pending = null; clearTimeout(current.timer); current.reject(new Error(message))
    }
    return {
        async hash(file) {
            if (stopped || pending || globalThis.isSecureContext !== true || !file || file.size < 1 || file.size > 209715200) throw new Error('无法预处理文件，请使用 HTTPS 或本机安全页面并一次选择一个文件')
            if (!worker) {
                try { worker = workerFactory() } catch { throw new Error('浏览器不支持后台文件校验') }
                worker.onmessage = ({data}) => {
                    if (!pending || data?.requestId !== pending.requestId) return
                    if (!/^[0-9a-f]{64}$/.test(data.sourceSha256 || '')) { rejectPending('文件校验失败，请重新选择文件'); return }
                    const current = pending; pending = null; clearTimeout(current.timer); current.resolve(data.sourceSha256)
                }
                worker.onerror = () => { rejectPending('后台文件校验失败'); worker?.terminate(); worker = null }
            }
            return new Promise((resolve, reject) => {
                const requestId = String(++counter)
                pending = {requestId, resolve, reject, timer: setTimeout(() => { rejectPending('文件校验超时，请重新选择文件'); worker?.terminate(); worker = null }, 120000)}
                try { worker.postMessage({requestId, file}) } catch { rejectPending('无法发送文件进行校验') }
            })
        },
        dispose() { if (stopped) return; stopped = true; rejectPending('文件校验已停止'); worker?.terminate(); worker = null }
    }
}
