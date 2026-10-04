import {describe, expect, it, vi} from 'vitest'

vi.mock('../src/api/http', () => ({default: {get: vi.fn()}}))
import {paginationParams, commentList} from '../src/api'
import http from '../src/api/http'

describe('public read contracts', () => {
    it('keeps default pagination', () => expect(paginationParams()).toEqual({pageNum: 1, pageSize: 10}))
    it('keeps bounded custom pagination', () => expect(paginationParams(3, 20)).toEqual({pageNum: 3, pageSize: 20}))
    it('requests only the historical comment read endpoint', () => {
        commentList('19')
        expect(http.get).toHaveBeenLastCalledWith('/api/comment/article/19')
    })
})
