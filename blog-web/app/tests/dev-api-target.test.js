// @vitest-environment node
import {afterEach,expect,it,vi} from 'vitest'
import definition from '../vite.config.js'
import {fileURLToPath} from 'node:url'
const configuration=()=>typeof definition==='function'?definition({command:'serve',mode:'test'}):definition
afterEach(()=>vi.unstubAllEnvs())
it('keeps the standard backend default and the independent blog proxy',()=>{
    vi.stubEnv('BLOG_API_URL','')
    const proxy=configuration().server.proxy
    expect(proxy['/api']).toBe('http://localhost:8080')
    expect(proxy['/blog']).toBe('http://127.0.0.1:5176')
    expect(configuration().envDir).toBe(fileURLToPath(new URL('../../',import.meta.url)))
})
it('routes public API traffic to the configured development backend',()=>{
    vi.stubEnv('BLOG_API_URL','http://127.0.0.1:8076')
    expect(configuration().server.proxy['/api']).toBe('http://127.0.0.1:8076')
})
