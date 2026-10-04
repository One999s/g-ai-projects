import {QuizApiError} from './server-api.js'
// Same-origin, hash-bound reviewed narration. Optional; no synthesis or external audio URL.
export function createNarrationPlayer({fetcher=globalThis.fetch.bind(globalThis),Context=globalThis.AudioContext||globalThis.webkitAudioContext,digest=bytes=>crypto.subtle.digest('SHA-256',bytes),timeoutMs=3000}={}){
 let epoch=0,controller=null,context=null,buffer=null,node=null,timer=null,bound=null,armed=null,resumed=null
 function cancel(){epoch++;controller?.abort();controller=null;clearTimeout(timer);timer=null;try{node?.stop()}catch{}node=null;buffer=null;bound=null;armed=null;resumed=null;try{const p=context?.close();p?.catch(()=>{})}catch{}context=null}
 function arm(s){
  cancel()
  if(typeof Context!=='function'||s?.phase!=='LOADING'||![s.sessionId,s.roundId].every(x=>typeof x==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(x)))return false
  try{context=new Context();armed={sessionId:s.sessionId,roundId:s.roundId};resumed=Promise.resolve(context.resume()).then(()=>true,()=>false);return true}catch{cancel();return false}
 }
 async function prepare(api,s){
  if(!context||!armed||controller||s?.phase!=='READING'||armed.sessionId!==s.sessionId||armed.roundId!==s.roundId)return false
  const token=epoch,local=context;controller=new AbortController();const signal=controller.signal
  let ownTimer=null
  const operation=(async()=>{
   if(!await resumed)throw Error('AUDIO_UNAVAILABLE')
   if(token!==epoch||signal.aborted)return false
   const m=await api.narration(s.sessionId,s.roundId,signal)
   if(token!==epoch||signal.aborted)return false
   if(m?.available===false)return false
   const keys=['available','sessionId','roundId','sha256','durationMillis','byteLength','contentType','serverNow','expiresAt','readingMillis']
   if(!m||Object.keys(m).sort().join()!==keys.sort().join()||m.available!==true||m.sessionId!==s.sessionId||m.roundId!==s.roundId||m.contentType!=='audio/wav'||!/^[a-f0-9]{64}$/.test(m.sha256)||!Number.isInteger(m.byteLength)||m.byteLength<3244||m.byteLength>1920044||!Number.isInteger(m.durationMillis)||m.durationMillis<100||m.durationMillis>59000||!Number.isInteger(m.readingMillis)||m.readingMillis>60000||m.durationMillis+1000>m.readingMillis||!Number.isSafeInteger(m.serverNow)||!Number.isSafeInteger(m.expiresAt)||m.expiresAt<=m.serverNow||m.expiresAt!==s.opensAt)throw Error('INVALID_NARRATION')
   const r=await fetcher(`/api/quiz/sessions/${s.sessionId}/rounds/${s.roundId}/narration/audio`,{credentials:'same-origin',cache:'no-store',redirect:'error',signal})
   if([401,403].includes(r.status))throw new QuizApiError(r.status===401?'AUTHENTICATION_REQUIRED':'ACCESS_DENIED',r.status)
   if(!r.ok||r.headers.get('Content-Type')?.split(';')[0]!=='audio/wav'||Number(r.headers.get('Content-Length'))!==m.byteLength)throw Error('INVALID_NARRATION')
   const reader=r.body?.getReader();if(!reader)throw Error('INVALID_NARRATION')
   const bytes=new Uint8Array(m.byteLength);let offset=0
   try{while(true){const part=await reader.read();if(token!==epoch||signal.aborted)throw Error('CANCELLED');if(part.done)break;if(offset+part.value.byteLength>bytes.length)throw Error('INVALID_NARRATION');bytes.set(part.value,offset);offset+=part.value.byteLength}}finally{await reader.cancel().catch(()=>{});reader.releaseLock()}
   if(offset!==bytes.length)throw Error('INVALID_NARRATION')
   const hash=Array.from(new Uint8Array(await digest(bytes))).map(x=>x.toString(16).padStart(2,'0')).join('')
   if(hash!==m.sha256)throw Error('INVALID_NARRATION')
   const decoded=await local.decodeAudioData(bytes.buffer)
   if(token!==epoch||signal.aborted)return false
   if(decoded.numberOfChannels!==1||Math.abs(decoded.duration*1000-m.durationMillis)>2)throw Error('INVALID_NARRATION')
   buffer=decoded;bound={sessionId:s.sessionId,roundId:s.roundId,durationMillis:m.durationMillis,readingMillis:m.readingMillis};return true
  })()
  try{const result=await Promise.race([operation,new Promise((_,reject)=>{ownTimer=setTimeout(()=>reject(Error('NARRATION_TIMEOUT')),timeoutMs);timer=ownTimer;signal.addEventListener('abort',()=>reject(Error('CANCELLED')),{once:true})})]);if(!result&&token===epoch)cancel();return result}
  catch(error){if(token===epoch)cancel();if([401,403].includes(error?.status))throw error;return false}finally{clearTimeout(ownTimer);if(timer===ownTimer)timer=null}
 }
 function play(s,now){
  if(node||!buffer||!bound||s?.phase!=='READING'||bound.sessionId!==s.sessionId||bound.roundId!==s.roundId||!Number.isFinite(now)||now>=s.opensAt)return false
  // Play the whole prompt or use text. Never skip its beginning or extend the server clock.
  if(s.deadline-s.opensAt!==20000||now+bound.durationMillis+1000>s.opensAt)return false
  try{node=context.createBufferSource();node.buffer=buffer;node.connect(context.destination);node.start(0);node.stop(context.currentTime+Math.max(0,(s.opensAt-now)/1000));return true}catch{cancel();return false}
 }
 return {arm,prepare,play,cancel}
}
