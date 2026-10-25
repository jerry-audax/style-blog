import axios from 'axios'
import {ElMessage} from 'element-plus'

const http = axios.create({baseURL: '', timeout: 15000})
http.interceptors.request.use(config => {
    const token = localStorage.getItem('admin_token')
    if (token) config.headers.Authorization = token
    return config
})

function rejectResponse(code, message, config, data) {
    const requestToken = config?.headers?.get?.('Authorization') ?? config?.headers?.Authorization ?? config?.headers?.authorization ?? ''
    if (code === 401 && requestToken === (localStorage.getItem('admin_token') || '')) {
        localStorage.removeItem('admin_token')
        window.dispatchEvent(new Event('admin-session-expired'))
        if (config?.url !== '/api/auth/login/password') window.location.hash = '#/login'
    }
    const error = new Error(message || (code === 403 ? '权限不足' : '请求失败'))
    error.status = code
    error.data = data
    return error
}

http.interceptors.response.use(
    res => {
        if (res.data.code !== 0) throw rejectResponse(res.data.code, res.data.message, res.config, res.data.data)
        return res.data.data
    },
    err => {
        const config = err.config
        if (err.response) err = rejectResponse(err.response.status, err.response.data?.message, config, err.response.data?.data)
        else if (err.code === 'ECONNABORTED') err.message = config?.url?.startsWith('/api/admin/media/uploads')
            ? '上传结果未确认，请按操作编号核对，勿重复上传' : config?.url?.startsWith('/api/images')
                ? '图片操作超时，结果未确认，请先检查图床列表，勿立即重复操作' : '请求超时，请检查网络'
        else if (err.message === 'Network Error') err.message = '网络连接失败'
        if (!config?.silent) ElMessage.error(err.message || '请求失败')
        return Promise.reject(err)
    },
)
export default http
