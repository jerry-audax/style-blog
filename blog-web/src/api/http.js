import axios from 'axios'

// API base URL strategy:
// - Production: set via .env.production (VITE_API_BASE_URL)
// - Development: falls back to localhost:8080 if env var is not set
// This avoids hardcoding the production IP address in source code.
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('blog_token')
  if (token) config.headers.Authorization = token
  return config
})

http.interceptors.response.use(
  (res) => {
    const data = res.data
    if (data.code !== 0) {
      throw new Error(data.message || '请求失败')
    }
    return data.data
  },
  (err) => {
    if (err.response) {
      const status = err.response.status
      if (status === 401) {
        localStorage.removeItem('blog_token')
        window.location.hash = '#/login'
      } else if (status === 403) {
        alert('权限不足')
      } else if (status >= 500) {
        alert('服务器错误，请稍后重试')
      } else {
        alert(err.response.data?.message || '请求失败')
      }
    } else if (err.code === 'ECONNABORTED') {
      alert('请求超时，请检查网络')
    } else if (err.message === 'Network Error') {
      alert('网络连接失败')
    } else {
      alert('请求失败')
    }
    return Promise.reject(err)
  }
)

export default http
