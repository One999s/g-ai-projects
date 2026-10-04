import {test,expect,vi,beforeEach,afterEach} from 'vitest'
import {mount} from '@vue/test-utils'
import ServerStage from '../src/ServerStage.vue'
const animation=vi.hoisted(()=>({fromTo:vi.fn(),set:vi.fn(),kills:[]}))
vi.mock('gsap',()=>({gsap:animation}))
let wrapper,frames,observer,context,width=800,height=700,next=0
beforeEach(()=>{
 frames=new Map();next=0;animation.fromTo.mockReset();animation.set.mockClear();animation.kills=[]
 animation.fromTo.mockImplementation(()=>{const kill=vi.fn();animation.kills.push(kill);return{kill}})
 vi.stubGlobal('requestAnimationFrame',vi.fn(fn=>{frames.set(++next,fn);return next}));vi.stubGlobal('cancelAnimationFrame',vi.fn(id=>frames.delete(id)))
 vi.stubGlobal('ResizeObserver',class{constructor(fn){this.notify=fn;this.observe=vi.fn();this.disconnect=vi.fn();observer=this}})
 context=Object.fromEntries(['setTransform','clearRect','beginPath','arc','fill'].map(k=>[k,vi.fn()]))
 vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue(context)
 vi.spyOn(HTMLElement.prototype,'clientWidth','get').mockImplementation(()=>width)
 vi.spyOn(HTMLElement.prototype,'clientHeight','get').mockImplementation(()=>height)
 Object.defineProperty(document,'hidden',{configurable:true,value:false});width=800;height=700
})
afterEach(()=>{wrapper?.unmount();vi.restoreAllMocks();vi.unstubAllGlobals();Object.defineProperty(document,'hidden',{configurable:true,value:false})})
function scene(props={}){wrapper=mount(ServerStage,{props})}
test('decoration contains no question, interactive controls or audio',()=>{scene();expect(wrapper.attributes('aria-hidden')).toBe('true');expect(wrapper.findAll('button,a,input,audio')).toHaveLength(0);expect(wrapper.text()).toBe('');expect(animation.fromTo).not.toHaveBeenCalled()})
test('server reveal animates once while identical polling never replays',async()=>{scene({phase:'ANSWERING',roundKey:'one'});animation.fromTo.mockClear();await wrapper.setProps({phase:'REVEALING',outcome:'correct'});expect(animation.fromTo).toHaveBeenCalledTimes(3);await wrapper.setProps({phase:'REVEALING',roundKey:'one',outcome:'correct'});expect(animation.fromTo).toHaveBeenCalledTimes(3);expect(wrapper.attributes('data-tone')).toBe('correct')})
test('next round cancels reveal and loading does not display a result',async()=>{scene({phase:'REVEALING',roundKey:'one',outcome:'correct'});const old=[...animation.kills];animation.fromTo.mockClear();await wrapper.setProps({phase:'LOADING',roundKey:'two',outcome:''});expect(old.every(k=>k.mock.calls.length===1)).toBe(true);expect(animation.fromTo).not.toHaveBeenCalled();expect(wrapper.attributes('data-tone')).toBe('loading')})
test('syncing and abandonment never invent success',async()=>{scene({phase:'SYNCING'});await wrapper.setProps({phase:'ABANDONED'});expect(animation.fromTo).not.toHaveBeenCalled()})
test('reduced motion draws static stars without RAF or GSAP',()=>{scene({phase:'FINISHED',reduced:true});expect(animation.fromTo).not.toHaveBeenCalled();expect(frames.size).toBe(0);expect(context.arc).toHaveBeenCalledTimes(44)})
test('motion toggle kills active animations and frame',async()=>{scene({phase:'ANSWERING'});const kills=[...animation.kills];expect(frames.size).toBe(1);await wrapper.setProps({reduced:true});expect(kills.every(k=>k.mock.calls.length===1)).toBe(true);expect(frames.size).toBe(0)})
test('pagehide stops animations; cached restore does not replay victory',()=>{scene({phase:'REVEALING',outcome:'correct'});const count=animation.fromTo.mock.calls.length;window.dispatchEvent(new Event('pagehide'));expect(frames.size).toBe(0);expect(animation.kills.every(k=>k.mock.calls.length===1)).toBe(true);window.dispatchEvent(new Event('pageshow'));expect(frames.size).toBe(1);expect(animation.fromTo).toHaveBeenCalledTimes(count)})
test('hidden phase changes stay silent and visible restore does not replay',async()=>{scene();Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));await wrapper.setProps({phase:'FINISHED'});expect(frames.size).toBe(0);expect(animation.fromTo).not.toHaveBeenCalled();Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));expect(frames.size).toBe(1);expect(animation.fromTo).not.toHaveBeenCalled()})
test('retina allocation is bounded and resize keeps only one pending frame',()=>{width=4000;height=3000;vi.stubGlobal('devicePixelRatio',4);scene();const el=wrapper.find('canvas').element;expect(el.width*el.height).toBeLessThan(2510000);observer.notify();observer.notify();expect(frames.size).toBe(1)})
test('unmount releases frames observers listeners and owned tweens',()=>{scene({phase:'FINISHED'});const count=animation.fromTo.mock.calls.length;wrapper.unmount();expect(frames.size).toBe(0);expect(observer.disconnect).toHaveBeenCalledOnce();window.dispatchEvent(new Event('pageshow'));window.dispatchEvent(new Event('resize'));expect(frames.size).toBe(0);expect(animation.fromTo).toHaveBeenCalledTimes(count)})
test('unavailable canvas retains CSS decoration without work loop',()=>{HTMLCanvasElement.prototype.getContext.mockReturnValue(null);scene({phase:'READING'});expect(wrapper.find('.stage-arches').exists()).toBe(true);expect(frames.size).toBe(0)})
