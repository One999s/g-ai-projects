import {QuizApiError} from './server-api.js'
const bad=()=>{throw new QuizApiError('INVALID_CHALLENGE_CATALOG',502)}
export function validatePlan(p){
 if(!p||Object.keys(p).sort().join(',')!=='id,slots,title'||typeof p.id!=='string'||! /^[a-z][a-z0-9-]{0,39}$/.test(p.id)||p.id==='free'||typeof p.title!=='string'||!p.title.trim()||p.title.length>100||/[\u0000-\u001f\u007f-\u009f]/.test(p.title)||!Array.isArray(p.slots)||p.slots.length!==5)bad()
 let difficulty=0
 const slots=p.slots.map(s=>{if(!s||Object.keys(s).sort().join(',')!=='category,difficulty'||typeof s.category!=='string'||!/^[a-z][a-z0-9-]{0,31}$/.test(s.category)||!Number.isInteger(s.difficulty)||s.difficulty<1||s.difficulty>3||s.difficulty<difficulty)bad();difficulty=s.difficulty;return Object.freeze({...s})})
 return Object.freeze({...p,slots:Object.freeze(slots)})
}
export function validateCatalog(v,locale){
 if(!v||Object.keys(v).sort().join(',')!=='locale,plans,version'||v.locale!==locale||typeof v.version!=='string'||!v.version.trim()||v.version.length>100||!Array.isArray(v.plans)||v.plans.length>12)bad()
 const plans=v.plans.map(validatePlan);if(new Set(plans.map(p=>p.id)).size!==plans.length)bad()
 return Object.freeze({...v,plans:Object.freeze(plans)})
}
export function categoryName(category,en=false){return ({space:en?'Space':'宇宙',ocean:en?'Ocean':'海洋',measurement:en?'Measurement':'计量',heritage:en?'Heritage':'人文遗产'})[category]||category}
