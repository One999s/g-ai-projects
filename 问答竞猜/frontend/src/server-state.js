import {validatePlan} from './challenges.js'
import {QuizApiError} from './server-api.js'
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export function validateSnapshot(s){
 const bad=()=>{throw new QuizApiError('INVALID_SESSION_SNAPSHOT',502)}
 if(!s||!UUID.test(s.sessionId)||!UUID.test(s.roundId)||!['en','zh-CN'].includes(s.locale)||!['LOADING','READING','ANSWERING','REVEALING','FINISHED','ABANDONED'].includes(s.phase))bad()
 if(!Number.isSafeInteger(s.revision)||s.revision<0||!Number.isInteger(s.roundNumber)||s.roundNumber<1||s.roundNumber>5||!Number.isInteger(s.score)||s.score<0||s.score>750||!Number.isInteger(s.streak)||s.streak<0||s.streak>5)bad()
 for(const key of ['serverNow','opensAt','deadline','loadingDeadline'])if(!Number.isSafeInteger(s[key])||s[key]<0)bad()
 const hidden=['LOADING','ABANDONED'].includes(s.phase)
 if(hidden){if(s.question!==null||!Array.isArray(s.options)||s.options.length!==0||s.reveal!==null||!Array.isArray(s.eliminated)||s.eliminated.length!==0)bad()}
 else if(typeof s.question!=='string'||!s.question||s.question.length>1000||!Array.isArray(s.options)||s.options.length!==4||s.options.some(x=>typeof x!=='string'||!x||x.length>400))bad()
 if(!Array.isArray(s.eliminated)||s.eliminated.length>2||new Set(s.eliminated).size!==s.eliminated.length||s.eliminated.some(x=>!Number.isInteger(x)||x<0||x>3)||typeof s.lifelineUsed!=='boolean')bad()
 if(s.reveal!==null){const r=s.reveal;if(!r||r.roundId!==s.roundId||!Number.isInteger(r.correct)||r.correct<0||r.correct>3||(r.selected!==null&&(!Number.isInteger(r.selected)||r.selected<0||r.selected>3))||typeof r.explanation!=='string'||!Number.isInteger(r.awarded)||r.awarded<0||r.awarded>200)bad()}
 if(['LOADING','READING','ANSWERING'].includes(s.phase)&&s.reveal!==null)bad()
 if(['REVEALING','FINISHED'].includes(s.phase)&&s.reveal===null)bad()
 if(s.challenge!=null){try{validatePlan(s.challenge)}catch{bad()}if(typeof s.challengeVersion!=='string'||!s.challengeVersion.trim()||s.challengeVersion.length>100)bad()}else if(s.challengeVersion!=null)bad()
 return Object.freeze({...s,challenge:s.challenge?validatePlan(s.challenge):null,options:Object.freeze([...s.options]),eliminated:Object.freeze([...s.eliminated]),reveal:s.reveal?Object.freeze({...s.reveal}):null})
}
export function isNewer(current,next){if(!current)return true;if(current.sessionId!==next.sessionId)return false;return next.revision>current.revision||(next.revision===current.revision&&next.serverNow>=current.serverNow)}
export function commandResolved(command,s){if(!command||command.sessionId!==s.sessionId)return false;if(['ABANDONED','FINISHED'].includes(s.phase))return true;if(command.roundId&&command.roundId!==s.roundId)return true;if(command.kind==='answer')return s.phase==='REVEALING';if(command.kind==='ready')return s.phase!=='LOADING';if(command.kind==='fifty')return s.lifelineUsed;return false}

export function validateVoiceCandidate(value,session,now){
 const keys=['sessionId','roundId','choice','transcript','expiresAt','serverNow','requiresConfirmation']
 if(!value||Object.keys(value).length!==keys.length||keys.some(k=>!Object.hasOwn(value,k))||value.sessionId!==session?.sessionId||value.roundId!==session?.roundId||!['READING','ANSWERING'].includes(session.phase)||value.requiresConfirmation!==true||value.expiresAt!==session.deadline||!Number.isSafeInteger(value.serverNow)||value.serverNow<session.opensAt||value.serverNow>=value.expiresAt||now>=value.expiresAt||typeof value.transcript!=='string'||value.transcript.length>160||/[\u0000-\u001f\u007f-\u009f]/.test(value.transcript)||(value.choice!==null&&(!Number.isInteger(value.choice)||value.choice<0||value.choice>3||session.eliminated.includes(value.choice))))throw new QuizApiError('INVALID_VOICE_CANDIDATE',502)
 return Object.freeze({...value})
}
