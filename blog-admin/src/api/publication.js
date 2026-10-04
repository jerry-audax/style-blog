import http from './http'

export const getPublicationStatus = () => http.get('/api/admin/publication/status', {timeout: 8000})
export const retryPublication = () => http.post('/api/admin/publication/retry', null, {timeout: 8000})
