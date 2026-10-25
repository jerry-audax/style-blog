// Complete, literal server contracts; no provider token, private environment or network.
export const trackedImageCapabilities = {
    metadataWrite:true,metadataVersionRequired:true,mediaUpload:true,mediaReplace:true,mediaDelete:false,catalogDelete:false,
    uploadProtocol:'tracked-v1',uploadLimits:{audioBytes:104857600,coverBytes:10485760,lyricsBytes:524288,videoBytes:209715200,chunkBytes:16000000},deleteProtocol:null,
    images:{storage:'postgres',uploadProtocol:'tracked-image-v1',uploadEnabled:true,deleteEnabled:false,avatarBytes:2097152,imageBytes:10485760}
}
export const legacyImageCapabilities = {
    metadataWrite:true,metadataVersionRequired:false,mediaUpload:true,mediaReplace:true,mediaDelete:true,catalogDelete:true,
    uploadProtocol:null,uploadLimits:null,deleteProtocol:null,
    images:{storage:'legacy-json',uploadProtocol:'legacy',uploadEnabled:true,deleteEnabled:true,avatarBytes:2097152,imageBytes:10485760}
}
