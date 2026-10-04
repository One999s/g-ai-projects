import {QuizApiError} from './server-api.js'
import {validatePlan} from './challenges.js'
const token=s=>typeof s==='string'&&s!=='free'&&/^[a-z][a-z0-9-]{0,39}$/.test(s)
const label=s=>typeof s==='string'&&!!s.trim()&&s.length<=100&&!/[\u0000-\u001f\u007f-\u009f]/.test(s)
const exact=(v,keys)=>v&&Object.keys(v).sort().join(',')===keys.split(' ').sort().join(',')
const bad=()=>{throw new QuizApiError('INVALID_JOURNEY',502)}
export function validateChapter(c){
 if(!exact(c,'campaignId campaignVersion definitionHash title levelId levelTitle index total requiredCorrect priorLevelIds')||!token(c.campaignId)||!token(c.campaignVersion)||!token(c.levelId)||!label(c.title)||!label(c.levelTitle)||typeof c.definitionHash!=='string'||!/^[a-f0-9]{64}$/.test(c.definitionHash)||c.total!==3||!Number.isInteger(c.index)||c.index<1||c.index>3||!Number.isInteger(c.requiredCorrect)||c.requiredCorrect<1||c.requiredCorrect>5||!Array.isArray(c.priorLevelIds)||c.priorLevelIds.length!==c.index-1||c.priorLevelIds.some(x=>!token(x))||new Set(c.priorLevelIds).size!==c.priorLevelIds.length)bad()
 return Object.freeze({...c,priorLevelIds:Object.freeze([...c.priorLevelIds])})
}
export function validateJourney(v,locale){
 if(!exact(v,'version locale campaignId campaignVersion title definitionHash levels')||v.locale!==locale||!label(v.version)||!Array.isArray(v.levels))bad()
 if(v.campaignId===null){if(v.campaignVersion!==null||v.title!==null||v.definitionHash!==null||v.levels.length)bad();return Object.freeze({...v,levels:Object.freeze([])})}
 if(!token(v.campaignId)||!token(v.campaignVersion)||!label(v.title)||!/^[a-f0-9]{64}$/.test(v.definitionHash)||v.levels.length!==3)bad()
 let open=true,lastDifficulty=0,lastThreshold=0;const ids=new Set()
 const levels=v.levels.map((l,i)=>{if(!exact(l,'id title index requiredCorrect slots unlocked passed attempts bestScore bestCorrect')||!token(l.id)||ids.has(l.id)||!label(l.title)||l.index!==i+1||!Number.isInteger(l.requiredCorrect)||l.requiredCorrect<1||l.requiredCorrect>5||l.requiredCorrect<lastThreshold||typeof l.passed!=='boolean'||l.unlocked!==open||!Number.isSafeInteger(l.attempts)||l.attempts<0||!Number.isInteger(l.bestScore)||l.bestScore<0||l.bestScore>750||!Number.isInteger(l.bestCorrect)||l.bestCorrect<0||l.bestCorrect>5||l.passed!==(l.bestCorrect>=l.requiredCorrect)||(!l.attempts&&(l.bestScore||l.bestCorrect))||(!open&&l.attempts))bad();const p=validatePlan({id:l.id,title:l.title,slots:l.slots});const sum=p.slots.reduce((a,s)=>a+s.difficulty,0);if(sum<=lastDifficulty)bad();lastDifficulty=sum;lastThreshold=l.requiredCorrect;ids.add(l.id);open=open&&l.passed;return Object.freeze({...l,slots:p.slots})})
 return Object.freeze({...v,levels:Object.freeze(levels)})
}
