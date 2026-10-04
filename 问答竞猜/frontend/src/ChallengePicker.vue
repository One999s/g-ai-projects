<script setup>
import {ref,shallowRef,onUnmounted,watch} from 'vue'
import {validateCatalog,categoryName} from './challenges'
const props=defineProps({api:Object,locale:String,disabled:Boolean})
const emit=defineEmits(['choose','expired'])
const catalog=shallowRef(null),choice=ref(''),busy=ref(false),error=ref('');let epoch=0,controller
const t=(zh,en)=>props.locale==='en'?en:zh
function clear(){epoch++;controller?.abort();catalog.value=null;choice.value='';busy.value=false;error.value='';emit('choose',null)}
function select(){const p=catalog.value?.plans.find(p=>p.id===choice.value);emit('choose',p?{id:p.id,version:catalog.value.version}:null)}
async function load(){if(busy.value||props.disabled)return;clear();const token=epoch,locale=props.locale;controller=new AbortController();busy.value=true;try{const c=validateCatalog(await props.api.challenges(locale,controller.signal),locale);if(token===epoch)catalog.value=c}catch(e){if(token!==epoch)return;if([401,403].includes(e.status)){clear();emit('expired',e)}else error.value=t('挑战列表暂不可用，可重试或选择自由挑战','Challenge list unavailable. Retry or play a free challenge.')}finally{if(token===epoch)busy.value=false}}
watch(()=>props.locale,clear)
watch(()=>props.disabled,value=>{if(value){epoch++;controller?.abort();busy.value=false}})
onUnmounted(()=>{epoch++;controller?.abort()})
</script>
<template><section class="challenge-picker" :aria-label="t('挑战路线','Challenge route')">
<div class="challenge-heading"><strong>{{t('选择你的挑战','CHOOSE YOUR CHALLENGE')}}</strong><button type="button" @click="load" :disabled="busy||disabled">{{busy?t('读取中…','LOADING…'):t('查看审核路线','EXPLORE REVIEWED ROUTES')}}</button></div>
<label class="challenge-choice" :class="{active:!choice}"><input type="radio" v-model="choice" value="" @change="select" :disabled="disabled"/><span><b>{{t('自由挑战','FREE CHALLENGE')}}</b><small>{{t('审核题包中随机五题','Five random questions from the approved pack')}}</small></span></label>
<label v-for="plan in catalog?.plans||[]" :key="plan.id" class="challenge-choice" :class="{active:choice===plan.id}"><input type="radio" v-model="choice" :value="plan.id" @change="select" :disabled="disabled"/><span><b>{{plan.title}}</b><small>{{t('五轮递进 · 编辑难度，非玩家实测','Five rounds · editorial difficulty, not player-calibrated')}}</small><span class="challenge-steps"><span v-for="(slot,i) in plan.slots" :key="i">{{i+1}} · {{categoryName(slot.category,locale==='en')}} <i>{{'●'.repeat(slot.difficulty)}}{{'○'.repeat(3-slot.difficulty)}}</i></span></span></span></label>
<p v-if="catalog&&!catalog.plans.length" class="challenge-note">{{t('当前题包仅支持自由挑战','This pack currently supports free challenges only')}}</p><p v-if="error" class="challenge-note" role="alert">{{error}}</p>
</section></template>
<style>
.challenge-picker{margin:0 0 26px;max-width:680px}.challenge-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px}.challenge-heading strong{color:#bba77f;font-size:10px;letter-spacing:.12em}.challenge-heading button{background:none;border:0;color:#b8c8e3;min-height:44px;text-decoration:underline;font-size:10px}.challenge-choice{display:flex;gap:12px;align-items:flex-start;padding:14px 17px;margin:8px 0;border:1px solid #63799944;background:#17233b33;cursor:pointer}.challenge-choice.active{border-color:#c5a36599;background:#b29a5220}.challenge-choice input{accent-color:#d7b777;margin-top:3px}.challenge-choice b{font-size:12px;font-weight:500;color:#ddc598}.challenge-choice small{display:block;font-size:9px;line-height:1.7;color:#8799b7;margin-top:6px}.challenge-steps{display:flex;flex-wrap:wrap;gap:8px 16px;margin-top:12px}.challenge-steps>span{font-size:9px;color:#a6b6cc}.challenge-steps i{font-size:8px;color:#d1b478;font-style:normal}.challenge-note{font-size:10px;color:#b5a58b;line-height:1.6}
</style>
