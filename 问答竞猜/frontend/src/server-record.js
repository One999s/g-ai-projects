import {QuizApiError} from './server-api.js'
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const fields=(x,keys)=>x&&typeof x==='object'&&!Array.isArray(x)&&Object.keys(x).sort().join('|')===keys.split(' ').sort().join('|')
const integer=(x,max)=>Number.isSafeInteger(x)&&x>=0&&x<=max
export function validateRecord(value){
 const fail=()=>{throw new QuizApiError('INVALID_PLAYER_RECORD',502)}
 if(!fields(value,'progress recent limit')||value.limit!==20||!fields(value.progress,'gamesPlayed bestScore totalScore correctAnswers bestStreak')||!Array.isArray(value.recent))fail()
 const p=value.progress
 if(!integer(p.gamesPlayed,2147483647)||!integer(p.bestScore,750)||!integer(p.totalScore,p.gamesPlayed*750)||!integer(p.correctAnswers,p.gamesPlayed*5)||!integer(p.bestStreak,5)||p.bestScore>p.totalScore||p.bestStreak>p.correctAnswers||value.recent.length!==Math.min(p.gamesPlayed,20))fail()
 if(p.gamesPlayed===0&&Object.values(p).some(x=>x!==0))fail()
 const seen=new Set();let previous=null,total=0,correct=0
 const recent=value.recent.map(row=>{
  if(!fields(row,'sessionId locale score correctAnswers bestStreak completedAt')||typeof row.sessionId!=='string'||!uuid.test(row.sessionId)||seen.has(row.sessionId)||!['en','zh-CN'].includes(row.locale)||!integer(row.score,p.bestScore)||!integer(row.correctAnswers,5)||!integer(row.bestStreak,Math.min(p.bestStreak,row.correctAnswers))||!integer(row.completedAt,8640000000000000)||row.completedAt===0||row.score<row.correctAnswers*100||(row.correctAnswers===0&&row.score!==0)||(row.correctAnswers>0&&row.bestStreak===0))fail()
  if(previous&&(row.completedAt>previous.completedAt||(row.completedAt===previous.completedAt&&row.sessionId<previous.sessionId)))fail()
  seen.add(row.sessionId);previous=row;total+=row.score;correct+=row.correctAnswers;return Object.freeze({...row})
 })
 if(total>p.totalScore||correct>p.correctAnswers||(p.gamesPlayed<=20&&(total!==p.totalScore||correct!==p.correctAnswers)))fail()
 return Object.freeze({progress:Object.freeze({...p}),recent:Object.freeze(recent),limit:20})
}
