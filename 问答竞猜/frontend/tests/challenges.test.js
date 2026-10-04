import test from 'node:test'
import assert from 'node:assert/strict'
import {validatePlan,validateCatalog} from '../src/challenges.js'
const plan=()=>({id:'rising',title:'Test route',slots:[1,1,2,2,3].map(difficulty=>({category:'space',difficulty}))})
test('catalog exposes immutable metadata with the exact requested locale',()=>{const v=validateCatalog({locale:'en',version:'v3',plans:[plan()]},'en');assert.ok(Object.isFrozen(v.plans[0].slots));assert.throws(()=>validateCatalog(v,'zh-CN'))})
test('wrong count decreasing difficulty and extra answer fields are refused',()=>{for(const p of [{...plan(),slots:[]},{...plan(),slots:[3,1,2,2,3].map(difficulty=>({category:'space',difficulty}))},{...plan(),answer:2}])assert.throws(()=>validatePlan(p))})
test('duplicate identifiers reserved free and unbounded category values fail closed',()=>{assert.throws(()=>validateCatalog({locale:'en',version:'v3',plans:[plan(),plan()]},'en'));assert.throws(()=>validatePlan({...plan(),id:'free'}));const p=plan();p.slots[0].category='../secret';assert.throws(()=>validatePlan(p))})
test('old packs can return an empty plan list without invented routes',()=>assert.equal(validateCatalog({locale:'en',version:'v2',plans:[]},'en').plans.length,0))
