import {beforeEach, expect, it, vi} from 'vitest'
import {validateImageCapabilities} from '../media/imageCapabilities'
import {normalizeImageCatalogPage, validateImageUploadResult} from '../media/imageCatalog'
import {imageKey, imageName, imageFileSize, formatImageSize} from '../utils/images'
vi.mock('../api/http', () => ({default: {get: vi.fn()}}))
import http from '../api/http'
import {listImages} from '../api'

const ID='00000000-0000-0000-0000-000000000701'
const pg={storage:'postgres',uploadProtocol:'tracked-image-v1',uploadEnabled:true,deleteEnabled:false,avatarBytes:2097152,imageBytes:10485760}
const image=(extra={})=>({assetId:ID,assetVersion:'0',fileName:'中文+100%.png',url:'https://example.test/file/blog/image.png',mimeType:null,fileSize:null,status:'ACTIVE',createdAt:'2026-10-18T00:00:00Z',updatedAt:'2026-10-18T00:00:00Z',...extra})
beforeEach(()=>vi.resetAllMocks())

it('only accepts explicit image capability versions and exact byte limits',()=>{
    expect(validateImageCapabilities({images:pg})).toEqual(pg)
    expect(validateImageCapabilities({images:{...pg,uploadEnabled:false,uploadProtocol:null}})).not.toBeNull()
    for(const raw of [{}, {metadataVersionRequired:false}, {images:{...pg,uploadProtocol:'tracked-v1'}}, {images:{...pg,avatarBytes:2097153}}, {images:{...pg,storage:'guess'}}, {images:{...pg,deleteEnabled:true}}])
        expect(validateImageCapabilities(raw)).toBeNull()
})
it('uses asset identity without fabricating a provider path and preserves null versus zero',()=>{
    const page=normalizeImageCatalogPage({images:[image(),image({assetId:ID.replace('701','702'),fileSize:0})],total:'53',offset:0,count:20},pg)
    expect(page.total).toBe(53); expect(imageKey(page.images[0])).toBe(ID); expect(imageName(page.images[0])).toBe('中文+100%.png')
    expect(page.images[0].path).toBeUndefined();expect(imageFileSize(page.images[0])).toBeNull();expect(formatImageSize(imageFileSize(page.images[0]))).toBe('大小未知')
    expect(formatImageSize(imageFileSize(page.images[1]))).toBe('0 B')
})
it('does not round unsafe totals or accept mixed malformed catalogue fields',()=>{
    for(const total of ['9007199254740993',53,'-1','01','1.0','9223372036854775808'])
        expect(()=>normalizeImageCatalogPage({images:[],total,offset:0,count:20},pg)).toThrow()
    for(const invalid of [image({assetVersion:0}),image({url:'javascript:alert(1)'}),image({status:'DELETED'}),image({assetId:'blog/image.png'})])
        expect(()=>normalizeImageCatalogPage({images:[invalid],total:'1',offset:0,count:20},pg)).toThrow()
})
it('requests second pages and literal search from PostgreSQL only when explicitly selected',async()=>{
    http.get.mockResolvedValue({images:[image()],total:'53',offset:50,count:20})
    const page=await listImages(50,20,'中文+100%_',pg)
    expect(http.get).toHaveBeenCalledWith('/api/admin/media/images',{params:{offset:50,count:20,search:'中文+100%_'}})
    expect(page.total).toBe(53)
    expect(()=>listImages(0,20,'',null)).toThrow(); expect(http.get).toHaveBeenCalledOnce()
})
it('legacy routing is explicit rather than inferred from missing capabilities',async()=>{
    const legacy={...pg,storage:'legacy-json',uploadProtocol:'legacy',deleteEnabled:true}
    http.get.mockResolvedValue({images:[{path:'blog/covers/历史.png',url:'https://example.test/file/blog/a.png',mediaType:'image/png',size:0}],total:1})
    const page=await listImages(0,20,'',legacy)
    expect(http.get).toHaveBeenCalledWith('/api/images',{params:{offset:0,count:20,search:''}})
    expect(imageKey(page.images[0])).toBe('blog/covers/历史.png')
})
it('accepts only an exclusive committed image result with matching UUID and decimal version',()=>{
    const op={targetId:ID,slot:'IMAGE'}, valid={targetType:'IMAGE_CREATE',image:image({mimeType:'image/png'}),music:null,video:null}
    expect(validateImageUploadResult(valid,op).assetId).toBe(ID)
    for(const bad of [{...valid,music:{}},{...valid,targetType:'MUSIC_TRACK'},{...valid,image:image({mimeType:'audio/mpeg'})},{...valid,image:image({assetId:ID.replace('701','702')})}])
        expect(()=>validateImageUploadResult(bad,op)).toThrow()
})
