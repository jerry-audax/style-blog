import {sha256ArrayBuffer} from '../media/uploadHashCore'
self.onmessage = async ({data}) => {
    const {requestId, file} = data || {}
    try {
        if (!file || file.size < 1 || file.size > 209715200) throw new Error('invalid source')
        const sourceSha256 = await sha256ArrayBuffer(await file.arrayBuffer())
        self.postMessage({requestId, sourceSha256})
    } catch { self.postMessage({requestId, error: '文件校验失败，请重新选择文件'}) }
}
