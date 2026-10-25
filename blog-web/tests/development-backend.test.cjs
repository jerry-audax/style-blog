const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs')
const os=require('node:os')
const path=require('node:path')
const {developmentBackend}=require('../tools/development-backend.cjs')
function fixture(run){
    const root=fs.mkdtempSync(path.join(os.tmpdir(),'blog-development-api-'))
    try{return run(root)}finally{fs.rmSync(root,{recursive:true,force:true})}
}
test('development publisher and Vite share the backend from the local override',()=>fixture(root=>{
    fs.writeFileSync(path.join(root,'.env.development.local'),'BLOG_API_URL=http://127.0.0.1:8076\n')
    assert.equal(developmentBackend({root,env:{}}),'http://127.0.0.1:8076')
}))
test('explicit environment wins over the local development file',()=>fixture(root=>{
    fs.writeFileSync(path.join(root,'.env.development.local'),'BLOG_API_URL=http://127.0.0.1:8076\n')
    assert.equal(developmentBackend({root,env:{BLOG_API_URL:'http://127.0.0.1:8085'}}),'http://127.0.0.1:8085')
}))
test('missing local overrides preserve the standard backend default',()=>fixture(root=>{
    assert.equal(developmentBackend({root,env:{}}),'http://localhost:8080')
}))
