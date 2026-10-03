// Same-origin AudioWorklet. It never opens a network connection or makes a decision.
class QuizPcmCapture extends AudioWorkletProcessor {
  constructor() { super(); this.count=0; this.maximum=Math.floor(sampleRate*6); this.done=false }
  process(inputs) {
    if(this.done)return false
    const channel=inputs[0]?.[0]
    if(!channel)return true
    const size=Math.min(channel.length,this.maximum-this.count)
    if(size>0){const copy=channel.slice(0,size);this.port.postMessage({type:'samples',buffer:copy.buffer},[copy.buffer]);this.count+=size}
    if(this.count>=this.maximum){this.done=true;this.port.postMessage({type:'limit'});return false}
    return true
  }
}
registerProcessor('quiz-pcm-capture',QuizPcmCapture)
