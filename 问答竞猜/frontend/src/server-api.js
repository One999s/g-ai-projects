// Transport only. Production identity is provided by the existing same-origin application.
// This module never creates tokens, sends claimed user/site IDs or imports demo answer keys.
export class QuizApiError extends Error {
 constructor(code,status=0,requestId=null){super(code);this.name='QuizApiError';this.code=code;this.status=status;this.requestId=requestId;this.retryable=status===0||status>=500}
}
export function createQuizApi({base='/api/quiz',fetcher=globalThis.fetch.bind(globalThis),timeoutMs=10000}={}){
 if(!Number.isInteger(timeoutMs)||timeoutMs<1||timeoutMs>30000)throw new TypeError('Invalid request timeout')
 const root=base.replace(/\/$/,'')
 function id(value){if(typeof value!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value))throw new QuizApiError('INVALID_RESOURCE_ID',400);return value}
 async function send(path,method='GET',body,contentType,signal){
  const controller=new AbortController();let timer,abortListener
  if(signal?.aborted)throw new QuizApiError('REQUEST_CANCELLED')
  const cancelled=new Promise((_,reject)=>{abortListener=()=>{controller.abort();reject(new QuizApiError('REQUEST_CANCELLED'))};signal?.addEventListener('abort',abortListener,{once:true})})
  const expired=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(new QuizApiError('REQUEST_TIMEOUT'))},timeoutMs)})
  try{return await Promise.race([request(path,method,body,controller.signal,contentType),expired,cancelled])}finally{clearTimeout(timer);signal?.removeEventListener('abort',abortListener)}
 }
 async function request(path,method,body,signal,contentType){
  let response;try{response=await fetcher(root+path,{method,signal,credentials:'same-origin',cache:'no-store',redirect:'error',headers:body===undefined?{}:{'Content-Type':contentType||'application/json'},...(body===undefined?{}:{body:contentType==='audio/wav'?body:JSON.stringify(body)})})}catch{throw new QuizApiError('NETWORK_ERROR')}
  let value;try{value=await response.json()}catch{throw new QuizApiError('INVALID_API_RESPONSE',response.ok?502:response.status)}
  if(value?.version!==1||value?.mode!=='server'||!Object.hasOwn(value,'data')||!Object.hasOwn(value,'error'))throw new QuizApiError('INVALID_API_RESPONSE',response.ok?502:response.status)
  if(!response.ok||value.error!==null)throw new QuizApiError(value.error?.code||'HTTP_ERROR',response.status,value.requestId)
  return value.data
 }
 const roundPath=(session,round)=>`/sessions/${id(session)}/rounds/${id(round)}`
 return {journey:(locale,signal)=>{if(!['en','zh-CN'].includes(locale))throw new QuizApiError('UNSUPPORTED_LOCALE',400);return send('/journey?locale='+encodeURIComponent(locale),'GET',undefined,undefined,signal)},challenges:(locale,signal)=>{if(!['en','zh-CN'].includes(locale))throw new QuizApiError('UNSUPPORTED_LOCALE',400);return send('/challenges?locale='+encodeURIComponent(locale),'GET',undefined,undefined,signal)},record:signal=>send('/me/record','GET',undefined,undefined,signal),narration:(session,round,signal)=>send(roundPath(session,round)+'/narration','GET',undefined,undefined,signal),capabilities:()=>send('/me/capabilities'),voice:(session,round,wave,signal)=>{if(!(wave instanceof Uint8Array)||wave.byteLength<3244||wave.byteLength>192044)throw new QuizApiError('INVALID_AUDIO',400);return send(roundPath(session,round)+'/voice-candidate','POST',wave,'audio/wav',signal)},create:(locale,idempotencyKey,challenge)=>send('/sessions','POST',{locale,idempotencyKey,...(challenge?{...(challenge.chapterId?{chapterId:challenge.chapterId}:{challengeId:challenge.id}),challengeVersion:challenge.version}:{})}),current:session=>send(`/sessions/${id(session)}/current`),ready:(session,round)=>send(roundPath(session,round)+'/ready','POST'),answer:(session,round,choice,idempotencyKey)=>send(roundPath(session,round)+'/answer','POST',{choice,idempotencyKey}),next:(session,round)=>send(roundPath(session,round)+'/next','POST'),fifty:(session,round)=>send(roundPath(session,round)+'/fifty-fifty','POST'),abandon:session=>send(`/sessions/${id(session)}/abandon`,'POST'),progress:()=>send('/me/progress'),archive:(limit=20)=>{if(!Number.isInteger(limit)||limit<1||limit>100)throw new QuizApiError('INVALID_PAGE_LIMIT',400);return send('/me/sessions?limit='+limit)}}
}
