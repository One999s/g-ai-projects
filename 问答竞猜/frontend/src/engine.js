export const ANSWER_MS=20000,READ_MS=2400
export function createGame(bank,now=0,random=Math.random){
 if(bank.length<5) throw new Error('Five unique questions required')
 const deck=[...bank];for(let i=deck.length-1;i>0;i--){const j=Math.floor(random()*(i+1));[deck[i],deck[j]]=[deck[j],deck[i]]}
 return {deck:deck.slice(0,5),index:0,phase:'reading',opensAt:now+READ_MS,deadline:now+READ_MS+ANSWER_MS,score:0,streak:0,bestStreak:0,lifelineUsed:false,eliminated:[],results:[]}
}
export function tick(g,now){if(g.phase==='reading'&&now>=g.opensAt)g.phase='answering';if(g.phase==='answering'&&now>=g.deadline)answer(g,null,now);return g}
export function answer(g,choice,now){
 if(!['reading','answering'].includes(g.phase)||now<g.opensAt)return false
 if(choice!==null&&(!Number.isInteger(choice)||choice<0||choice>3||g.eliminated.includes(choice)))return false
 if(now>=g.deadline)choice=null
 else if(choice===null)return false
 const correct=choice===g.deck[g.index].answer;g.streak=correct?g.streak+1:0;const points=correct?100+Math.min(g.streak-1,4)*25:0
 g.score+=points;g.bestStreak=Math.max(g.bestStreak,g.streak);g.results.push({id:g.deck[g.index].id,choice,correct,points});g.phase='revealing';return true
}
export function next(g,now){if(g.phase!=='revealing')return false;if(g.index===4){g.phase='finished';return true}g.index++;g.phase='reading';g.opensAt=now+READ_MS;g.deadline=g.opensAt+ANSWER_MS;g.eliminated=[];return true}
export function fifty(g,now,random=Math.random){tick(g,now);if(g.phase!=='answering'||g.lifelineUsed)return false;const wrong=[0,1,2,3].filter(x=>x!==g.deck[g.index].answer);const keep=Math.floor(random()*3);g.eliminated=wrong.filter((_,i)=>i!==keep);g.lifelineUsed=true;return true}
