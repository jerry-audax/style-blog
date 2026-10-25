// @vitest-environment node
import {expect,it} from 'vitest'
import {readFileSync} from 'node:fs'
import config from '../../vite.config.js'
function location(text,name) {
    const start=text.indexOf(`location ${name} {`)
    if(start<0)return null
    const open=text.indexOf('{',start);let depth=1,end=open+1
    while(end<text.length&&depth){if(text[end]==='{')depth++;if(text[end]==='}')depth--;end++}
    if(depth)throw new Error('Unbalanced nginx block')
    return Object.fromEntries(text.slice(open+1,end-1).split(';').map(entry=>entry.replace(/#[^\n]*/g,'').trim().split(/\s+/)).filter(parts=>parts.length>1).map(([name,...value])=>[name,value.join(' ')]))
}
it('uses a specific tracked proxy without extending all API timeouts',()=>{
    const proxy=config.server.proxy['/api/admin/media/uploads/'];expect(proxy).toBeDefined();expect(proxy.target).toBe('http://localhost:8080');expect(proxy.timeout).toBe(1800000);expect(proxy.proxyTimeout).toBe(1800000)
    expect(config.server.proxy['/api']).toBe('http://localhost:8080')
})
it('nginx tracked routes preserve upstream and large-body safeguards without widening AI or image rules',()=>{
    const container=readFileSync(new URL('../../nginx.conf.template',import.meta.url),'utf8'), outer=readFileSync(new URL('../../../deploy/nginx-admin.jrtitan.xyz.conf',import.meta.url),'utf8')
    for(const [text,upstream] of [[container,'${BACKEND_URL}'],[outer,'http://127.0.0.1:5174']]){
        const route=location(text,'^~ /api/admin/media/uploads/');expect(route).not.toBeNull();expect(route.proxy_pass).toBe(upstream);expect(route.proxy_read_timeout).toBe('1800s');expect(route.proxy_send_timeout).toBe('1800s');expect(route.proxy_request_buffering).toBe('off');expect(parseInt(route.client_max_body_size)).toBeGreaterThanOrEqual(205);expect(route.proxy_set_header).toBeDefined()
    }
    expect(location(container,'/api/ai/').proxy_read_timeout).toBe('300s');expect(location(container,'/api/').proxy_read_timeout).toBeUndefined();expect(location(outer,'/').proxy_read_timeout).toBe('300s')
})
