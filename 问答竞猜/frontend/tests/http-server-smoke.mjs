// Called only by the test-profile JVM. No test authentication is bundled in server-main or production.
import assert from 'node:assert/strict'
import {randomUUID,webcrypto} from 'node:crypto'
import {createNarrationPlayer} from '../src/narration.js'
import {createQuizApi} from '../src/server-api.js'
import {validateSnapshot,validateVoiceCandidate} from '../src/server-state.js'
import {validateCatalog} from '../src/challenges.js'
import {validateRecord} from '../src/server-record.js'
import {encodeWave} from '../src/voice-recorder.js'
const origin=process.argv[2]
assert.match(origin,/^http:\/\/127\.0\.0\.1:\d+$/)
const api=createQuizApi({base:origin+'/api/quiz',fetcher:(url,options)=>fetch(url,{...options,headers:{...options.headers,'X-Test-Actor':'owner'}})})
assert.equal(validateRecord(await api.record()).progress.gamesPlayed,0)
const catalog=validateCatalog(await api.challenges('en'),'en');assert.equal(catalog.plans.length,1);const challenge={id:catalog.plans[0].id,version:catalog.version}
const creation='create-'+randomUUID();let s=validateSnapshot(await api.create('en',creation,challenge));assert.equal(s.challenge.id,challenge.id)
assert.equal(s.phase,'LOADING');assert.equal(s.reveal,null);assert.equal(s.options.length,0);assert.equal(s.question,null);assert.equal('answer'in s,false)
assert.equal((await api.create('en',creation,challenge)).sessionId,s.sessionId)
for(let i=0;i<5;i++){
 const narrator=createNarrationPlayer({Context:class{constructor(){this.currentTime=0;this.destination={}}async resume(){}async close(){}async decodeAudioData(){return{duration:1,numberOfChannels:1}}createBufferSource(){return{connect(){},start(){},stop(){}}}},digest:b=>webcrypto.subtle.digest('SHA-256',b),fetcher:(path,options)=>fetch(origin+path,{...options,headers:{'X-Test-Actor':'owner'}})})
 assert.equal(narrator.arm(s),true) // Device preparation only; no question/audio request before Ready.
 s=validateSnapshot(await api.ready(s.sessionId,s.roundId))
 assert.equal(await narrator.prepare(api,s),true) // Actual authorized bytes/hash; fake audio device, no listening claim.
 assert.equal(narrator.play(s,Date.now()),true);narrator.cancel()
 await new Promise(r=>setTimeout(r,Math.max(0,s.opensAt-s.serverNow)+70))
 if(i===0){const caps=await api.capabilities();assert.equal(caps.voiceCandidateConfigured,true);const current=validateSnapshot(await api.current(s.sessionId));const voice=validateVoiceCandidate(await api.voice(s.sessionId,s.roundId,encodeWave(new Float32Array(4800).fill(.1),48000)),current,current.serverNow);assert.equal(voice.choice,2);assert.equal(voice.requiresConfirmation,true);assert.equal((await api.current(s.sessionId)).score,0)} // Test-only recognizer returns C; no microphone/model accuracy claim.
 const key='answer-'+randomUUID();const a=await api.answer(s.sessionId,s.roundId,2,key) // Synthetic test bank: all C, not real content.
 validateSnapshot(a.session);assert.equal(a.accepted,true);assert.equal(a.session.phase,'REVEALING');assert.equal(a.result.correct,2)
 const replay=await api.answer(s.sessionId,s.roundId,2,key);assert.deepEqual(replay.result,a.result)
 s=validateSnapshot(await api.next(s.sessionId,s.roundId))
}
assert.equal(s.phase,'FINISHED');assert.equal(s.score,750)
const progress=await api.progress();assert.equal(progress.gamesPlayed,1);assert.equal(progress.totalScore,750)
const history=await api.archive();assert.equal(history.length,1);assert.equal(history[0].sessionId,s.sessionId)
const record=validateRecord(await api.record());assert.equal(record.progress.gamesPlayed,1);assert.equal(record.progress.totalScore,750);assert.equal(record.recent[0].sessionId,s.sessionId)
console.log(JSON.stringify({passed:true,transport:'source JavaScript client -> actual loopback HTTP -> Spring -> JDBC/H2',score:s.score,rounds:5,productionIdentity:false}))
