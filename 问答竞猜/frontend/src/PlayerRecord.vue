<script setup>
import {computed,nextTick,onMounted,onUnmounted,ref,shallowRef} from 'vue'
import {validateRecord} from './server-record'
const props=defineProps({api:{type:Object,required:true},locale:{type:String,default:'zh-CN'}})
const emit=defineEmits(['close','expired'])
const record=shallowRef(null),error=shallowRef(null),loading=ref(false),paused=ref(false),heading=ref(null)
let generation=0,alive=true,controller=null
const en=computed(()=>props.locale==='en'),t=(zh,english)=>en.value?english:zh
const number=n=>new Intl.NumberFormat(en.value?'en':'zh-CN').format(n)
const date=n=>new Intl.DateTimeFormat(en.value?'en':'zh-CN',{dateStyle:'medium',timeStyle:'short'}).format(n)
const problem=computed(()=>error.value?.status===429?t('查看过于频繁，请稍后重试','Too many requests. Try again shortly.'):error.value?.code==='IDENTITY_ADAPTER_NOT_CONFIGURED'?t('真实登录尚未接入，无法读取战绩','Sign-in is not connected yet. Records are unavailable.'):t('战绩暂时无法读取，请重试','Your record could not be loaded. Please retry.'))
function clear(){generation++;controller?.abort();controller=null;record.value=null;error.value=null;loading.value=false}
async function load(){
 if(loading.value||!alive||document.hidden)return
 clear();paused.value=false;const token=generation;controller=new AbortController();loading.value=true
 try{const value=validateRecord(await props.api.record(controller.signal));if(!alive||token!==generation)return;record.value=value}
 catch(e){if(!alive||token!==generation)return;record.value=null;if([401,403].includes(e?.status)){clear();emit('expired',e)}else error.value=e}
 finally{if(alive&&token===generation){loading.value=false;controller=null}}
}
function visibility(){if(document.hidden){clear();paused.value=true}else load()}
function close(){clear();emit('close')}
onMounted(async()=>{document.addEventListener('visibilitychange',visibility);await nextTick();if(!alive)return;heading.value?.focus();load()})
onUnmounted(()=>{alive=false;clear();document.removeEventListener('visibilitychange',visibility)})
</script>
<template>
<section class="player-record" aria-labelledby="record-title" @keydown.esc.stop.prevent="close">
 <div class="record-masthead"><div><span class="eyebrow">PERSONAL RECORD</span><h2 id="record-title" ref="heading" tabindex="-1">{{t('你的挑战足迹','Your challenge record')}}</h2></div><button class="text-button" @click="close" :aria-label="t('关闭战绩','Close record')">{{t('收起','CLOSE')}} ×</button></div>
 <p class="record-note">{{t('当前登录账号与站点 · 仅统计完整完成的挑战','Current account and site · completed challenges only')}}</p>
 <p v-if="loading" class="record-message" role="status">{{t('正在读取服务器战绩…','Loading your server record…')}}</p>
 <div v-else-if="error" class="record-message" role="alert"><p>{{problem}}</p><button class="text-button" @click="load">{{t('重试战绩','RETRY RECORD')}}</button></div>
 <div v-else-if="paused" class="record-message" role="status">{{t('页面离开时已清空战绩，回来后重新读取','Records were cleared while away and will reload when you return')}}</div>
 <template v-else-if="record">
  <dl class="record-totals"><div><dt>{{t('最高得分','PERSONAL BEST')}}</dt><dd>{{number(record.progress.bestScore)}}<small>/ 750</small></dd></div><div><dt>{{t('完成挑战','COMPLETED')}}</dt><dd>{{number(record.progress.gamesPlayed)}}</dd></div><div><dt>{{t('累计积分','TOTAL POINTS')}}</dt><dd>{{number(record.progress.totalScore)}}</dd></div><div><dt>{{t('最长连对','BEST STREAK')}}</dt><dd>{{record.progress.bestStreak}}<small>/ 5</small></dd></div></dl>
  <p class="record-correct">{{t('累计答对','TOTAL CORRECT')}} <strong>{{number(record.progress.correctAnswers)}}</strong></p>
  <p v-if="!record.recent.length" class="record-empty">{{t('聚光灯正在等你。完成第一场挑战，留下自己的纪录。','The spotlight is yours. Finish your first challenge to set a personal record.')}}</p>
  <template v-else><div class="record-list-label">{{t('最近完成 · 最多20场','RECENT FINISHES · UP TO 20')}}</div><ol class="record-list"><li v-for="item in record.recent" :key="item.sessionId"><time :datetime="new Date(item.completedAt).toISOString()">{{date(item.completedAt)}}</time><span>{{item.locale==='zh-CN'?'中文':'English'}}</span><span>{{t('答对','CORRECT')}} {{item.correctAnswers}}/5</span><strong>{{item.score}}<small> PTS</small></strong></li></ol></template>
  <button class="text-button record-refresh" @click="load">↻ {{t('更新战绩','REFRESH RECORD')}}</button>
 </template>
</section>
</template>
<style>
.player-record{max-width:1000px;margin:15px auto 55px;padding:30px 36px;border-top:1px solid #b79b5b70;border-bottom:1px solid #b79b5b35;background:linear-gradient(135deg,#14203b99,#0a142788);color:#afbad0}.record-masthead{display:flex;justify-content:space-between;gap:20px;align-items:center}.record-masthead h2{font-size:27px;color:#edd9ab;font-weight:500;letter-spacing:.04em;margin:10px 0}.record-masthead h2:focus-visible{outline:1px solid #eacb85;outline-offset:6px}.record-note,.record-correct{font-size:11px;line-height:1.8;color:#8294b4}.record-totals{display:grid;grid-template-columns:repeat(4,1fr);gap:24px;margin:30px 0 18px}.record-totals>div{border-left:1px solid #cbb47855;padding-left:20px}.record-totals dt{font-size:9px;letter-spacing:.12em;color:#b8a477}.record-totals dd{font-size:32px;color:#f0dfba;margin:10px 0;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.record-totals small{font-size:11px;color:#7f8aa1;margin-left:6px}.record-correct strong{font-size:16px;color:#cdbd9c;margin-left:8px}.record-empty,.record-message{padding:24px 0;line-height:1.8;font-size:13px;color:#cbb891}.record-list-label{font-size:9px;letter-spacing:.12em;margin-top:28px;padding-bottom:12px;color:#998867}.record-list{list-style:none;padding:0;margin:0}.record-list li{display:grid;grid-template-columns:2fr 1fr 1fr 1fr;gap:15px;align-items:center;padding:17px 0;border-top:1px solid #a5afc51b;font-size:11px}.record-list strong{text-align:right;color:#e7cd97;font-size:21px;font-variant-numeric:tabular-nums}.record-list small{font-size:8px;letter-spacing:.1em;color:#8791a5}.record-refresh{margin-top:24px}.player-record button{min-height:44px}.player-record button:focus-visible{outline:2px solid #eacb85;outline-offset:4px}@media(max-width:680px){.player-record{padding:22px 14px}.record-masthead h2{font-size:21px}.record-totals{grid-template-columns:repeat(2,1fr);gap:18px}.record-totals dd{font-size:29px}.record-list li{grid-template-columns:1.5fr 1fr;gap:8px}.record-list time{grid-column:1}.record-list strong{grid-column:2;grid-row:auto}.record-list li>span{font-size:10px}}
</style>
