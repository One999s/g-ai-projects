// Transport only. Production identity is provided by the existing same-origin application.
// This module never creates tokens, sends claimed user/site IDs or imports demo answer keys.
export class QuizApiError extends Error {
 constructor(code,status=0,requestId=null){super(code);this.name='QuizApiError';this.code=code;this.status=status;this.requestId=requestId;this.retryable=status===0||status>=500}
}
export function createQuizApi({base='/api/quiz',fetcher=globalThis.fetch.bind(globalThis)}={}){
 const root=base.replace(/\/$/,'')
 function id(value){if(typeof value!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value))throw new QuizApiError('INVALID_RESOURCE_ID',400);return value}
 async function send(path,method='GET',body){
  let response;try{response=await fetcher(root+path,{method,credentials:'same-origin',cache:'no-store',headers:body===undefined?{}:{'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})})}catch{throw new QuizApiError('NETWORK_ERROR')}
  let value;try{value=await response.json()}catch{throw new QuizApiError('INVALID_API_RESPONSE',response.status)}
  if(value?.version!==1||value?.mode!=='server'||!Object.hasOwn(value,'data')||!Object.hasOwn(value,'error'))throw new QuizApiError('INVALID_API_RESPONSE',response.status)
  if(!response.ok||value.error!==null)throw new QuizApiError(value.error?.code||'HTTP_ERROR',response.status,value.requestId)
  return value.data
 }
 const roundPath=(session,round)=>`/sessions/${id(session)}/rounds/${id(round)}`
 return {create:(locale,idempotencyKey)=>send('/sessions','POST',{locale,idempotencyKey}),current:session=>send(`/sessions/${id(session)}/current`),ready:(session,round)=>send(roundPath(session,round)+'/ready','POST'),answer:(session,round,choice,idempotencyKey)=>send(roundPath(session,round)+'/answer','POST',{choice,idempotencyKey}),next:(session,round)=>send(roundPath(session,round)+'/next','POST'),fifty:(session,round)=>send(roundPath(session,round)+'/fifty-fifty','POST'),abandon:session=>send(`/sessions/${id(session)}/abandon`,'POST'),progress:()=>send('/me/progress'),archive:(limit=20)=>{if(!Number.isInteger(limit)||limit<1||limit>100)throw new QuizApiError('INVALID_PAGE_LIMIT',400);return send('/me/sessions?limit='+limit)}}
}
