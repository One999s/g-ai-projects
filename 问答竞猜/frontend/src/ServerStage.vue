<script setup>
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {gsap} from 'gsap'
// Decoration only: no question text, answer index, clock, command or audio access.
const props=defineProps({phase:{type:String,default:'IDLE'},roundKey:{type:String,default:''},outcome:{type:String,default:''},reduced:Boolean})
const canvas=ref(null),halo=ref(null),sweep=ref(null),rings=ref(null)
const tone=computed(()=>props.phase==='REVEALING'?(props.outcome==='correct'?'correct':'learn'):props.phase==='FINISHED'?'finale':props.phase.toLowerCase())
let frame=0,observer,context=null,live=false,hidden=false,lastFrame=0,started=0,width=0,height=0
const tweens=[]
function stop(){cancelAnimationFrame(frame);frame=0;for(const tween of tweens.splice(0))tween.kill();gsap.set([halo.value,sweep.value,rings.value].filter(Boolean),{clearProps:'transform,opacity'})}
function draw(time=0){
 if(!live||hidden||document.hidden||!context)return
 const ctx=context;ctx.clearRect(0,0,width,height)
 const drift=props.reduced?0:(time-started)/15000
 for(let i=0;i<44;i++){const x=(Math.sin(i*19.17)*.5+.5)*width,y=((i*71.13+drift*13)%height+height)%height;ctx.fillStyle=`rgba(184,210,255,${.12+(Math.sin(i+drift)+1)*.10})`;ctx.beginPath();ctx.arc(x,y,i%7===0?1.3:.65,0,Math.PI*2);ctx.fill()}
 if(!props.reduced)frame=requestAnimationFrame(paint)
}
function paint(time){if(time-lastFrame<32){frame=requestAnimationFrame(paint);return}lastFrame=time;draw(time)}
function resize(){
 cancelAnimationFrame(frame);frame=0
 const el=canvas.value;if(!live||!el||hidden||document.hidden)return
 width=Math.min(el.clientWidth,1920);height=Math.min(el.clientHeight,1000)
 if(!width||!height)return
 const ratio=Math.min(window.devicePixelRatio||1,2,Math.sqrt(2500000/(width*height)))
 el.width=Math.round(width*ratio);el.height=Math.round(height*ratio)
 try{context=el.getContext('2d')}catch{context=null}
 if(!context)return
 context.setTransform(ratio,0,0,ratio,0,0);draw(performance.now())
}
function cue(){
 stop();if(!live||hidden||document.hidden)return
 if(props.reduced){resize();return}
 // Never animate controls or conceal server text while a deadline is running.
 if(['READING','ANSWERING','REVEALING','FINISHED'].includes(props.phase)){
  tweens.push(gsap.fromTo(halo.value,{scale:.88,opacity:.2},{scale:1,opacity:1,duration:1.1,ease:'power2.out'}))
  tweens.push(gsap.fromTo(sweep.value,{rotation:-24,opacity:0},{rotation:24,opacity:0,duration:1.5,keyframes:[{opacity:.45,duration:.5},{opacity:0,duration:1}],ease:'sine.inOut'}))
 }
 if(['REVEALING','FINISHED'].includes(props.phase))tweens.push(gsap.fromTo(rings.value,{scale:.65,opacity:.7},{scale:1.22,opacity:0,duration:1.4,ease:'power2.out'}))
 resize()
}
function conceal(){hidden=true;stop()}
function reveal(){hidden=document.hidden;stop();if(!hidden)resize()} // Resuming never replays a victory.
watch(()=>[props.roundKey,props.phase,props.outcome],cue,{flush:'post'})
watch(()=>props.reduced,()=>{stop();resize()},{flush:'post'})
onMounted(()=>{live=true;hidden=document.hidden;started=performance.now();if(typeof ResizeObserver!=='undefined'){observer=new ResizeObserver(resize);observer.observe(canvas.value)}window.addEventListener('resize',resize);window.addEventListener('pagehide',conceal);window.addEventListener('pageshow',reveal);document.addEventListener('visibilitychange',reveal);cue()})
onUnmounted(()=>{live=false;stop();observer?.disconnect();window.removeEventListener('resize',resize);window.removeEventListener('pagehide',conceal);window.removeEventListener('pageshow',reveal);document.removeEventListener('visibilitychange',reveal);context=null})
</script>
<template>
<div class="server-scenery" :data-tone="tone" :class="{'still-stage':reduced}" aria-hidden="true">
 <canvas ref="canvas" class="stage-stars"></canvas>
 <div ref="halo" class="stage-halo"></div><div ref="sweep" class="stage-sweep"></div>
 <div class="stage-arches"><i></i><i></i><i></i></div><div ref="rings" class="stage-rings"></div>
 <div class="stage-rail rail-left"></div><div class="stage-rail rail-right"></div>
</div>
</template>
<style>
.server-scenery{--stage-color:115,152,224;position:absolute;inset:0 0 auto;height:1000px;max-height:100dvh;overflow:hidden;pointer-events:none;z-index:0;contain:strict}
.server-scenery[data-tone=loading]{--stage-color:199,170,112}.server-scenery[data-tone=correct],.server-scenery[data-tone=finale]{--stage-color:239,197,114}.server-scenery[data-tone=learn]{--stage-color:140,172,216}
.stage-stars{width:100%;height:100%;position:absolute;inset:0}.stage-halo{position:absolute;left:12%;top:12%;width:76%;height:70%;background:radial-gradient(ellipse,rgba(var(--stage-color),.14),transparent 64%);transform-origin:50% 45%}
.stage-sweep{position:absolute;top:-35%;left:40%;width:20%;height:125%;opacity:0;transform-origin:50% 0;background:linear-gradient(180deg,rgba(var(--stage-color),.2),transparent 85%);clip-path:polygon(48% 0,52% 0,100% 100%,0 100%)}
.stage-arches{position:absolute;inset:18% 11% 5%;mask-image:linear-gradient(transparent,#000 24%,#000 60%,transparent)}.stage-arches i{position:absolute;inset:0;border:1px solid rgba(var(--stage-color),.1);border-radius:50% 50% 0 0}.stage-arches i:nth-child(2){inset:5% 5% 0}.stage-arches i:nth-child(3){inset:10% 10% 0}
.stage-rings{position:absolute;left:25%;top:16%;width:50%;aspect-ratio:1;border:1px solid rgba(var(--stage-color),.5);border-radius:50%;box-shadow:0 0 35px rgba(var(--stage-color),.08),inset 0 0 35px rgba(var(--stage-color),.08);opacity:0}
.stage-rail{position:absolute;top:35%;height:42%;width:1px;background:linear-gradient(transparent,rgba(var(--stage-color),.4),transparent)}.rail-left{left:5%}.rail-right{right:5%}
.still-stage .stage-sweep,.still-stage .stage-rings{display:none}
@media(max-width:680px){.stage-arches{inset:22% -25% 8%}.stage-rings{left:5%;width:90%;top:27%}.stage-halo{left:-20%;width:140%}.server-scenery{height:850px}.stage-rail{display:none}}
@media(prefers-reduced-motion:reduce){.stage-sweep,.stage-rings{display:none}}
</style>
