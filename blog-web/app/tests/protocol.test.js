import {describe, it, expect} from 'vitest'
import {createSseParser} from '../src/utils/sse'
import {safeRedirect} from '../src/utils/navigation'

describe('Spring SSE text protocol', () => {
    it('preserves newlines across multi-line events and chunk boundaries', () => {
        let text = ''
        const parser = createSseParser((chunk) => {
            text += chunk
        })
        parser.push('data: 第一行\r\n')
        parser.push('data: 第二行\r\n\r')
        parser.push('\ndata: !\n\n')
        parser.finish()
        expect(text).toBe('第一行\n第二行!')
    })
    it('ignores heartbeat and metadata fields', () => {
        let text = ''
        const parser = createSseParser((chunk) => {
            text += chunk
        })
        parser.push(': heartbeat\n\nevent: message\ndata: test\n\n')
        parser.finish()
        expect(text).toBe('test')
    })
})
describe('login redirects', () => {
    it('preserves blog URLs and rejects external redirects', () => {
        expect(safeRedirect('/blog/article/19/')).toBe('/blog/article/19/')
        expect(safeRedirect('https://example.com/')).toBe('/')
        expect(safeRedirect('//example.com/')).toBe('/')
        expect(safeRedirect('/\\example.com')).toBe('/')
    })
})
