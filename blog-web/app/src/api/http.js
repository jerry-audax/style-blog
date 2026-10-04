import axios from 'axios'

// Public clients never restore or transmit a management session.
const http = axios.create({baseURL: '', timeout: 15000})
http.interceptors.response.use(
    res => {
        if (res.data.code !== 0) throw new Error(res.data.message || '请求失败');
        return res.data.data
    },
    err => {
        err.message = err.response?.data?.message || (err.code === 'ECONNABORTED' ? '请求超时，请检查网络' : '服务暂时不可用');
        return Promise.reject(err)
    },
)
export default http
