import test from 'node:test'
import assert from 'node:assert/strict'
import {questions} from '../src/questions.js'
import {createGame,tick,answer,next,fifty,ANSWER_MS,READ_MS} from '../src/engine.js'
const fresh=()=>createGame(questions,1000,()=>.5)
test('new game chooses five distinct questions without modifying bank',()=>{const copy=JSON.stringify(questions),g=fresh();assert.equal(new Set(g.deck.map(q=>q.id)).size,5);assert.equal(JSON.stringify(questions),copy);assert.equal(g.phase,'reading')})
test('answers remain locked until reading deadline',()=>{const g=fresh();assert.equal(answer(g,g.deck[0].answer,g.opensAt-1),false);tick(g,g.opensAt);assert.equal(g.phase,'answering');assert.equal(answer(g,g.deck[0].answer,g.opensAt),true)})
test('five correct answers produce exactly 750 and no double settlement',()=>{const g=fresh();for(let i=0;i<5;i++){tick(g,g.opensAt);assert.equal(answer(g,g.deck[i].answer,g.opensAt),true);assert.equal(answer(g,g.deck[i].answer,g.opensAt),false);next(g,g.deadline+1)}assert.equal(g.score,750);assert.equal(g.bestStreak,5);assert.equal(g.results.length,5);assert.equal(g.phase,'finished');assert.equal(next(g,999999),false)})
test('exact deadline rejects otherwise correct answer as timeout',()=>{const g=fresh();answer(g,g.deck[0].answer,g.deadline);assert.equal(g.results[0].choice,null);assert.equal(g.score,0)})
test('hidden or delayed ticks resolve timeout exactly once',()=>{const g=fresh();tick(g,g.deadline+100000);tick(g,g.deadline+200000);assert.equal(g.results.length,1);assert.equal(g.phase,'revealing')})
test('wrong answer resets streak before later success',()=>{const g=fresh();answer(g,g.deck[0].answer,g.opensAt);next(g,9000);answer(g,(g.deck[1].answer+1)%4,g.opensAt);next(g,10000);answer(g,g.deck[2].answer,g.opensAt);assert.equal(g.score,200);assert.equal(g.streak,1)})
test('fifty removes only two wrong answers and is once per game',()=>{const g=fresh();assert.equal(fifty(g,g.opensAt),true);assert.equal(g.eliminated.length,2);assert.ok(!g.eliminated.includes(g.deck[0].answer));assert.equal(fifty(g,g.opensAt),false);assert.equal(answer(g,g.eliminated[0],g.opensAt),false);answer(g,g.deck[0].answer,g.opensAt);next(g,40000);assert.deepEqual(g.eliminated,[]);assert.equal(fifty(g,g.opensAt),false)})
test('fifty cannot race an expired deadline',()=>{const g=fresh();assert.equal(fifty(g,g.deadline),false);assert.equal(g.phase,'revealing');assert.equal(g.lifelineUsed,false)})
test('invalid choices cannot change state',()=>{for(const c of [-1,4,1.1,'1',undefined,null]){const g=fresh();assert.equal(answer(g,c,g.opensAt),false);assert.equal(g.results.length,0)}})
test('next cannot skip an unanswered question',()=>{const g=fresh();assert.equal(next(g,g.opensAt),false);assert.equal(g.index,0)})
test('a new game resets consumed tools and all result state',()=>{const g=fresh();fifty(g,g.opensAt);answer(g,g.deck[0].answer,g.opensAt);const h=fresh();assert.equal(h.score,0);assert.equal(h.lifelineUsed,false);assert.equal(h.results.length,0)})
test('all demo questions have aligned bilingual choices and one valid answer',()=>{assert.equal(new Set(questions.map(q=>q.id)).size,questions.length);for(const q of questions){assert.equal(q.title.length,2);assert.equal(q.options.length,2);assert.ok(q.answer>=0&&q.answer<4);for(const options of q.options){assert.equal(options.length,4);assert.equal(new Set(options).size,4)}}})
