import {computed,ref,toValue,watch,onBeforeUnmount} from 'vue'
import * as api from '../api/index.js'
import {useMediaUpload} from './useMediaUpload'
import {validateImageCapabilities} from '../media/imageCapabilities'
import {validateImageUploadResult} from '../media/imageCatalog'
import {isSafeImageUrl,validateImageUpload} from '../utils/images'

/** Component-local draft consent wrapped around the shared durable upload coordinator. */
export function useImageUpload({auth,purpose,onSucceeded}) {
    const capabilities=ref(null), localError=ref(''), legacyBusy=ref(false)
    let generation=0, selection=0, identityGeneration=0, disposed=false, capabilityRequest=null
    const intents=new Map()
    const owner=()=>auth.initialized === true && auth.user?.owner === true && typeof auth.verifiedUserId === 'string'
        && /^[1-9][0-9]{0,18}$/.test(auth.verifiedUserId) && BigInt(auth.verifiedUserId)<=9223372036854775807n && String(auth.user.userId) === auth.verifiedUserId ? auth.verifiedUserId : ''
    const currentPurpose=()=>String(toValue(purpose) || '').toLowerCase()
    const stamp=()=>({generation,selection:++selection,owner:owner(),token:auth.token,purpose:currentPurpose()})
    const current=s=>!disposed && s.generation===generation && s.selection===selection && s.owner===owner() && s.token===auth.token && s.purpose===currentPurpose()
    const uploader=useMediaUpload({auth,capabilities:()=>capabilities.value,targetType:'IMAGE_CREATE',onSucceeded:(result,operation)=>{
        const intent=intents.get(operation.operationId)
        if (!intent || !current(intent)) return
        const image=validateImageUploadResult(result,operation)
        intents.delete(operation.operationId);localError.value=''
        return onSucceeded?.(image,operation)
    }})
    const imageCapabilities=computed(()=>validateImageCapabilities(capabilities.value))
    const enabled=computed(()=>Boolean(owner() && imageCapabilities.value?.uploadEnabled))
    const busy=computed(()=>legacyBusy.value || uploader.busy.value)
    const error=computed(()=>localError.value || uploader.error.value)

    // History never inherits an old editor's consent. Adoption is a new, explicit draft choice.
    function availableAvatar(operation) {
        if(disposed || !owner() || currentPurpose()!=='avatar' || imageCapabilities.value?.storage!=='postgres') return null
        const saved=uploader.operations.value.find(row=>row.operationId===operation?.operationId
            && row.operationVersion===operation.operationVersion && row.targetType==='IMAGE_CREATE'
            && row.slot==='AVATAR' && row.status==='SUCCEEDED')
        if(!saved)return null
        try{return {operation:saved,image:validateImageUploadResult(saved.result,saved)}}catch{return null}
    }
    const canAdopt=operation=>Boolean(availableAvatar(operation))
    function adopt(operation) {
        if(busy.value)return null
        const saved=availableAvatar(operation)
        if(!saved)return null
        const intent=stamp()
        if(!current(intent))return null
        intents.clear();localError.value=''
        onSucceeded?.(saved.image,saved.operation)
        return saved.image
    }

    function invalidate() { generation++; selection++; intents.clear(); localError.value='';uploader.suspend() }
    async function loadCapabilities() {
        if (disposed || !owner()) return null
        if (imageCapabilities.value) return imageCapabilities.value
        if (capabilityRequest) return capabilityRequest
        const who=owner(), token=auth.token, epoch=identityGeneration
        const task=(async()=>{
            try {
                const raw=await api.getMediaCapabilities()
                if (disposed || who!==owner() || token!==auth.token || epoch!==identityGeneration) return null
                const validated=validateImageCapabilities(raw)
                if (!validated) throw new Error('图片能力未知，请核对前后端版本，未回退旧上传')
                capabilities.value=raw;localError.value=''
                if (validated.storage==='postgres' && validated.uploadEnabled) await uploader.restore()
                return validated
            } catch(cause) {
                if (!disposed && who===owner() && token===auth.token && epoch===identityGeneration) {
                    if(cause?.status===401)auth.clear?.()
                    else localError.value=cause?.message || '图片能力加载失败，请核对版本'
                }
                return null
            }
        })()
        capabilityRequest=task
        try{return await task}finally{if(capabilityRequest===task)capabilityRequest=null}
    }

    async function start(file) {
        if (busy.value || disposed) return null
        localError.value=''
        try { validateImageUpload(file,currentPurpose()) } catch(cause) { localError.value=cause.message;return null }
        const intent=stamp()
        const config=await loadCapabilities()
        if (!current(intent)) return null
        if (!config || !config.uploadEnabled || !owner()) { localError.value ||= '图片上传不可用，请核对博主身份、版本与能力开关';return null }
        if (config.storage==='legacy-json') {
            legacyBusy.value=true
            try {
                const image=await api.uploadImage(file,intent.purpose)
                if (!current(intent)) return null
                if (!isSafeImageUrl(image?.url,true)) throw new Error('图片链接无效，请刷新图片库核对，勿重复上传')
                await onSucceeded?.(image,null)
                return image
            } catch(cause) { if(current(intent))localError.value=cause?.message || '上传结果未确认，请刷新核对，勿重复上传';return null }
            finally { legacyBusy.value=false }
        }
        const sending=uploader.start({targetType:'IMAGE_CREATE',slot:intent.purpose==='avatar'?'AVATAR':'IMAGE',imagePurpose:intent.purpose.toUpperCase()},file)
        const operationId=uploader.activeOperationId.value
        if(operationId)intents.set(operationId,intent)
        const result=await sending
        if(!current(intent))return null
        if(result?.status==='SUCCEEDED') {
            try {return validateImageUploadResult(result.result,result)}catch{localError.value='已完成回执无法安全显示，请按原编号核对';return null}
        }
        if(result && !['FAILED','CONFLICT'].includes(result.status)) localError.value='结果未确认，请按原编号查询或恢复，勿重复上传'
        return null
    }

    watch(()=>[owner(),auth.token],()=>{
        identityGeneration++;invalidate();capabilities.value=null;capabilityRequest=null
        if(owner())void loadCapabilities()
    },{immediate:true,flush:'sync'})
    watch(currentPurpose,invalidate,{flush:'sync'})
    onBeforeUnmount(()=>{disposed=true;invalidate()})
    return {uploader,capabilities,imageCapabilities,enabled,busy,error,start,loadCapabilities,invalidate,canAdopt,adopt}
}
