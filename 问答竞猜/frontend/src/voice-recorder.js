// Recording begins only after a user action and permission. No SpeechRecognition/cloud API.
export class VoiceCaptureError extends Error {constructor(code){super(code);this.code=code}}
export function encodeWave(samples,sampleRate){
 if(!(samples instanceof Float32Array)||!Number.isInteger(sampleRate)||sampleRate<16000||sampleRate>96000)throw new VoiceCaptureError('INVALID_PCM')
 if(samples.length<sampleRate/10)throw new VoiceCaptureError('AUDIO_TOO_SHORT')
 if(samples.length>sampleRate*6)throw new VoiceCaptureError('AUDIO_TOO_LONG')
 const count=Math.floor(samples.length*16000/sampleRate),result=new Uint8Array(44+count*2),view=new DataView(result.buffer)
 const tag=(offset,value)=>[...value].forEach((c,i)=>view.setUint8(offset+i,c.charCodeAt(0)))
 tag(0,'RIFF');view.setUint32(4,result.length-8,true);tag(8,'WAVE');tag(12,'fmt ');view.setUint32(16,16,true);view.setUint16(20,1,true);view.setUint16(22,1,true);view.setUint32(24,16000,true);view.setUint32(28,32000,true);view.setUint16(32,2,true);view.setUint16(34,16,true);tag(36,'data');view.setUint32(40,count*2,true)
 const ratio=sampleRate/16000
 for(let i=0;i<count;i++){
  const from=i*ratio,to=Math.min(samples.length,(i+1)*ratio);let sum=0
  for(let j=Math.floor(from);j<Math.min(samples.length,Math.ceil(to));j++){const value=samples[j];if(!Number.isFinite(value))throw new VoiceCaptureError('INVALID_PCM');sum+=Math.max(-1,Math.min(1,value))*(Math.min(to,j+1)-Math.max(from,j))}
  const value=sum/(to-from);view.setInt16(44+i*2,Math.round(value*(value<0?32768:32767)),true)
 }
 return result
}
export function createVoiceRecorder({onLimit=()=>{},onError=()=>{},maxMillis=6000}={}){
 if(!Number.isInteger(maxMillis)||maxMillis<100||maxMillis>6000)throw new VoiceCaptureError('INVALID_DURATION')
 let state='new',stream,context,source,node,silent,limitTimer,setupTimer,cancelSetup,chunks=[],frames=0
 const wipe=()=>{for(const chunk of chunks)chunk.fill(0);chunks=[];frames=0}
 function close(){clearTimeout(limitTimer);clearTimeout(setupTimer);if(node){node.port.onmessage=null;node.port.close?.();node.disconnect()}source?.disconnect();silent?.disconnect();stream?.getTracks().forEach(track=>track.stop());context?.close().catch(()=>{})}
 function cancel(){cancelSetup?.(new VoiceCaptureError('CAPTURE_CANCELLED'));cancelSetup=null;state='cancelled';close();wipe()}
 async function start(){
  if(state!=='new')throw new VoiceCaptureError('CAPTURE_ALREADY_USED')
  const Context=globalThis.AudioContext||globalThis.webkitAudioContext,media=globalThis.navigator?.mediaDevices
  if(globalThis.isSecureContext===false||!Context||!globalThis.AudioWorkletNode||!media?.getUserMedia)throw new VoiceCaptureError('MIC_UNSUPPORTED')
  state='opening'
  const setup=async()=>{
   context=new Context({sampleRate:48000})
   if(!context.audioWorklet)throw new VoiceCaptureError('MIC_UNSUPPORTED')
   if(!Number.isInteger(context.sampleRate)||context.sampleRate<16000||context.sampleRate>96000)throw new VoiceCaptureError('MIC_UNSUPPORTED')
   await context.resume()
   if(state!=='opening')throw new VoiceCaptureError('CAPTURE_CANCELLED')
   const incoming=await media.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false})
   if(state!=='opening'){incoming.getTracks().forEach(track=>track.stop());throw new VoiceCaptureError('CAPTURE_CANCELLED')}
   stream=incoming
   if(!Number.isInteger(context.sampleRate)||context.sampleRate<16000||context.sampleRate>96000)throw new VoiceCaptureError('MIC_UNSUPPORTED')
   await context.audioWorklet.addModule(new URL('./pcm-worklet.js',import.meta.url).href,{credentials:'same-origin'})
   if(state!=='opening')throw new VoiceCaptureError('CAPTURE_CANCELLED')
   node=new globalThis.AudioWorkletNode(context,'quiz-pcm-capture',{numberOfInputs:1,numberOfOutputs:1,outputChannelCount:[1]})
   source=context.createMediaStreamSource(stream);silent=context.createGain();silent.gain.value=0
   node.port.onmessage=event=>{
    if(state!=='recording')return
    if(event.data?.type==='samples'){if(!(event.data.buffer instanceof ArrayBuffer)||event.data.buffer.byteLength%4){cancel();onError(new VoiceCaptureError('INVALID_PCM'));return}const chunk=new Float32Array(event.data.buffer),left=context.sampleRate*6-frames;if(chunk.length>left){cancel();onError(new VoiceCaptureError('INVALID_PCM'));return}chunks.push(chunk);frames+=chunk.length}
    else if(event.data?.type==='limit')onLimit()
   }
   node.onprocessorerror=()=>{cancel();onError(new VoiceCaptureError('MIC_UNAVAILABLE'))}
   for(const track of stream.getTracks())track.addEventListener?.('ended',()=>{if(state==='recording'||state==='opening'){cancel();onError(new VoiceCaptureError('MIC_UNAVAILABLE'))}},{once:true})
   state='recording';source.connect(node);node.connect(silent);silent.connect(context.destination);limitTimer=setTimeout(onLimit,maxMillis)
  }
  try{await Promise.race([setup(),new Promise((_,reject)=>{setupTimer=setTimeout(()=>reject(new VoiceCaptureError('MIC_SETUP_TIMEOUT')),15000)}),new Promise((_,reject)=>{cancelSetup=reject})])}
  catch(error){cancel();throw error instanceof VoiceCaptureError?error:new VoiceCaptureError(error?.name==='NotAllowedError'?'MIC_PERMISSION_DENIED':'MIC_UNAVAILABLE')}
  finally{clearTimeout(setupTimer);cancelSetup=null}
 }
 function stop(){
  if(state!=='recording')throw new VoiceCaptureError('CAPTURE_NOT_ACTIVE')
  state='stopped';close();const samples=new Float32Array(frames);let offset=0
  for(const chunk of chunks){samples.set(chunk,offset);offset+=chunk.length}
  try{return encodeWave(samples,context.sampleRate)}finally{samples.fill(0);wipe()}
 }
 return{start,stop,cancel}
}
