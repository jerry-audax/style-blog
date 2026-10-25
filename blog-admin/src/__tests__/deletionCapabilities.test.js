import {expect,it} from 'vitest'
import {validateDeletionCapabilities} from '../media/deletionCapabilities'
const caps={metadataWrite:true,metadataVersionRequired:true,mediaUpload:false,mediaReplace:false,mediaDelete:true,catalogDelete:true,uploadProtocol:null,uploadLimits:null,deleteProtocol:'tracked-delete-v1'}
it('recognizes deletion independently from upload',()=>{expect(validateDeletionCapabilities(caps)).toEqual({protocol:'tracked-delete-v1',mediaDelete:true,catalogDelete:true})})
it('retains read protocol when new submissions are disabled',()=>{expect(validateDeletionCapabilities({...caps,mediaDelete:false,catalogDelete:false})).toEqual({protocol:'tracked-delete-v1',mediaDelete:false,catalogDelete:false})})
it.each([null,{}, {...caps,deleteProtocol:null},{...caps,deleteProtocol:'future-v2'},{...caps,metadataVersionRequired:false},{...caps,mediaDelete:'true'},{...caps,catalogDelete:1}])('fails closed for unsupported capability %#',raw=>{expect(validateDeletionCapabilities(raw)).toBeNull()})
