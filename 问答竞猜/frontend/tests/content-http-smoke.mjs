// Synthetic test profile only. Never shipped as production identity or operator authorization.
import assert from 'node:assert/strict'
import {createQuizApi} from '../src/server-api.js'
let document='';for await(const chunk of process.stdin)document+=chunk
const base=process.argv[2]
assert.match(base,/^http:\/\/127\.0\.0\.1:\d+$/)
const api=createQuizApi({base:base+'/api/quiz',fetcher:(url,options)=>fetch(url,{...options,headers:{...options.headers,'X-Test-Actor':'content-reviewer'}})})
const input={version:'JS_IMPORTED_V1',locale:'en',document,reason:'Assistant synthetic integration fixture only'}
const preview=await api.previewPack(input)
assert.equal(preview.chapterCount,3)
const result=await api.publishPack({...input,previewHash:preview.previewHash})
assert.equal(result.created,true)
assert.equal((await api.publishPack({...input,previewHash:preview.previewHash})).created,false)
assert.equal((await api.journey('en')).version,input.version)
const session=await api.create('en','js-imported-chapter',{chapterId:'chapter-1',version:input.version})
assert.equal(session.chapter.levelId,'chapter-1');assert.equal(session.question,null)
console.log('CONTENT_HTTP_IMPORT_CHAPTER_OK')
