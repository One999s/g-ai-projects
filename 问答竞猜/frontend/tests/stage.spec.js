import {describe,test,expect,vi,beforeEach,afterEach} from 'vitest'
import {mount} from '@vue/test-utils'
import App from '../src/App.vue'
import {questions} from '../src/questions.js'
vi.mock('../src/audio',()=>({createStudioAudio:()=>({enable:vi.fn().mockResolvedValue(),cue:vi.fn(),close:vi.fn()})}))
vi.mock('gsap',()=>({gsap:{fromTo:vi.fn(),killTweensOf:vi.fn()}}))
let wrapper
beforeEach(()=>{
 vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-03T00:00:00Z'))
 vi.stubGlobal('matchMedia',()=>({matches:false,addEventListener(){},removeEventListener(){}}))
 vi.stubGlobal('ResizeObserver',class{observe(){}disconnect(){}})
 vi.stubGlobal('SpeechSynthesisUtterance',class{constructor(text){this.text=text}})
 Object.defineProperty(window,'speechSynthesis',{configurable:true,value:{speak:vi.fn(),cancel:vi.fn()}})
 HTMLCanvasElement.prototype.getContext=()=>({setTransform(){},clearRect(){},beginPath(){},arc(){},fill(){}})
 wrapper=mount(App,{attachTo:document.body})
})
afterEach(()=>{wrapper.unmount();document.body.innerHTML='';vi.useRealTimers();vi.unstubAllGlobals()})
const clickText=async(text)=>{const btn=wrapper.findAll('button').find(b=>b.text().includes(text));expect(btn).toBeTruthy();await btn.trigger('click')}
const wait=async(ms)=>{vi.advanceTimersByTime(ms);await wrapper.vm.$nextTick()}
const correct=()=>{const title=wrapper.find('h2').text();const q=questions.find(q=>q.title.includes(title));return q.answer}
describe('stage DOM interaction (not real-device visual or audio acceptance)',()=>{
 test('lobby declares demo and starts locked reading',async()=>{expect(wrapper.text()).toContain('不上传成绩');await clickText('登上舞台');expect(wrapper.findAll('.answers button')).toHaveLength(4);expect(wrapper.find('.answers button').attributes('disabled')).toBeDefined();await wait(2400);expect(wrapper.find('.answers button').attributes('disabled')).toBeUndefined()})
 test('completes five questions and restarts without stale score',async()=>{await clickText('登上舞台');for(let i=0;i<5;i++){await wait(2400);await wrapper.findAll('.answers button')[correct()].trigger('click');expect(wrapper.find('.reveal').exists()).toBe(true);await clickText(i===4?'查看成绩':'下一道挑战')}expect(wrapper.find('.final-score').text()).toContain('750');await clickText('再来一局');expect(wrapper.find('.score-label').text()).toContain('0');expect(wrapper.find('.finale').exists()).toBe(false)})
 test('duplicate answer clicks do not change locked result',async()=>{await clickText('登上舞台');await wait(2400);const n=correct();await wrapper.findAll('.answers button')[n].trigger('click');const score=wrapper.find('.score-label').text();await wrapper.findAll('.answers button')[(n+1)%4].trigger('click');expect(wrapper.find('.score-label').text()).toBe(score)})
 test('simulated voice candidate never auto-submits',async()=>{await clickText('登上舞台');await wait(2400);await clickText('语音确认体验');await wrapper.find('select').setValue('ABCD'[correct()]);expect(wrapper.find('.reveal').exists()).toBe(false);await clickText('确认答案');expect(wrapper.find('.reveal').exists()).toBe(true)})
 test('timeout clears active candidate and prevents confirmation',async()=>{await clickText('登上舞台');await wait(2400);await clickText('语音确认体验');await wrapper.find('select').setValue('A');await wait(20000);expect(wrapper.find('select').exists()).toBe(false);expect(wrapper.text()).toContain('时间到');expect(wrapper.find('.score-label').text()).toContain('0')})
 test('language cannot change a live question, leaving then changes lobby',async()=>{await clickText('登上舞台');const title=wrapper.find('h2').text();await clickText('中文');expect(wrapper.find('h2').text()).toBe(title);await clickText('离开本局');await clickText('中文');expect(wrapper.text()).toContain('TAKE THE STAGE')})
 test('fifty marks exactly two unavailable choices',async()=>{await clickText('登上舞台');await wait(2400);await clickText('50:50');expect(wrapper.findAll('.answers .eliminated')).toHaveLength(2);expect(wrapper.text()).toContain('已使用')})
 test('keyboard A–D follows the same answer flow',async()=>{await clickText('登上舞台');await wait(2400);window.dispatchEvent(new KeyboardEvent('keydown',{key:'ABCD'[correct()],bubbles:true}));await wrapper.vm.$nextTick();expect(wrapper.find('.reveal').exists()).toBe(true)})
 test('narration gate is released only on completion and stale callbacks are ignored',async()=>{await clickText('登上舞台');await wrapper.find('input[type=checkbox]').setValue(true);const first=window.speechSynthesis.speak.mock.calls[0][0];await wait(3000);expect(wrapper.find('.answers button').attributes('disabled')).toBeDefined();first.onend();await wait(500);expect(wrapper.find('.answers button').attributes('disabled')).toBeUndefined();await clickText('离开本局');await clickText('登上舞台');first.onend();await wait(3000);expect(wrapper.find('.answers button').attributes('disabled')).toBeDefined()})
 test('global mute cancels narration and releases reading lock',async()=>{await clickText('声音关');await clickText('登上舞台');await wrapper.find('input[type=checkbox]').setValue(true);await clickText('声音开');expect(window.speechSynthesis.cancel).toHaveBeenCalled();expect(wrapper.find('input[type=checkbox]').element.checked).toBe(false);await wait(500);expect(wrapper.find('.answers button').attributes('disabled')).toBeUndefined()})
 test('reduced motion control adds explicit no-animation class',async()=>{const button=wrapper.find('button[title="减少动画"]');await button.trigger('click');expect(wrapper.find('.quiet-motion').exists()).toBe(true)})
})
