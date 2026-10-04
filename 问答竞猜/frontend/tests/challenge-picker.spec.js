import {test,expect,vi,afterEach} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import ChallengePicker from '../src/ChallengePicker.vue'
let wrapper
const catalog={locale:'en',version:'v3',plans:[{id:'rising',title:'Test route',slots:[1,1,2,2,3].map(difficulty=>({category:'space',difficulty}))}]}
afterEach(()=>wrapper?.unmount())
function setup(api){wrapper=mount(ChallengePicker,{props:{api,locale:'en',disabled:false}})}
test('user explicitly fetches routes then selects versioned metadata',async()=>{const api={challenges:vi.fn().mockResolvedValue(catalog)};setup(api);expect(api.challenges).not.toHaveBeenCalled();await wrapper.find('button').trigger('click');await flushPromises();await wrapper.findAll('input')[1].setValue();expect(wrapper.emitted('choose').at(-1)).toEqual([{id:'rising',version:'v3'}]);expect(wrapper.text()).toContain('editorial difficulty')})
test('changing language discards selection and ignores obsolete response',async()=>{let done;setup({challenges:vi.fn().mockReturnValue(new Promise(r=>done=r))});await wrapper.find('button').trigger('click');await wrapper.setProps({locale:'zh-CN'});done(catalog);await flushPromises();expect(wrapper.text()).not.toContain('Test route');expect(wrapper.emitted('choose').at(-1)).toEqual([null])})
test('authentication denial is forwarded while other failure stays retryable',async()=>{setup({challenges:vi.fn().mockRejectedValue({status:401})});await wrapper.find('button').trigger('click');await flushPromises();expect(wrapper.emitted('expired')).toHaveLength(1);expect(wrapper.findAll('input')).toHaveLength(1)})
test('unmount and disabled state cancel a pending catalog',async()=>{let signal;setup({challenges:vi.fn((_locale,s)=>{signal=s;return new Promise(()=>{})})});await wrapper.find('button').trigger('click');await wrapper.setProps({disabled:true});expect(signal.aborted).toBe(true);wrapper.unmount();expect(signal.aborted).toBe(true)})
