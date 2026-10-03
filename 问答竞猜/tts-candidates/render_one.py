#!/usr/bin/env python3
"""One local Chinese development candidate. No server, uploads, runtime downloads or approval."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import resource
import sys
import threading
import time
import wave
from local_policy import prepare_environment, verified_bundle

HERE=Path(__file__).resolve().parent
LIMIT=3*1024**3


def guard_runtime():
    prepare_environment()
    # Application-local audit policy; never edits system/network settings.
    def audit(event,args):
        if event in {'socket.connect','socket.connect_ex','socket.getaddrinfo','socket.bind','subprocess.Popen','os.system'}:
            raise RuntimeError('Network and subprocess operations are disabled during candidate inference')
    sys.addaudithook(audit)
    def monitor():
        while True:
            try:
                rss=int(Path('/proc/self/statm').read_text().split()[1])*os.sysconf('SC_PAGE_SIZE')
                if rss>LIMIT: os._exit(73)
            except (OSError,ValueError,IndexError): os._exit(74)
            time.sleep(.1)
    threading.Thread(target=monitor,daemon=True).start()


def phonemes(text, chinese, symbols):
    # This bounded renderer accepts our Chinese prompts plus the four spoken option labels only.
    if re.sub(r'选项[A-D]','',text).isascii() or re.search(r'[a-zA-Z]',re.sub(r'选项[A-D]','',text)):
        raise ValueError('Only Chinese prompts and option labels A-D are supported')
    names={'A':(['ey'],[2]),'B':(['b','iy'],[0,2]),'C':(['s','iy'],[0,2]),'D':(['d','iy'],[0,2])}
    normalized=[];phones=[];tones=[];counts=[]
    for part in re.split('([ABCD])',text):
        if part in names:
            ps,ts=names[part];normalized.append(part.lower());phones+=ps
            tones += [t+symbols.language_tone_start_map['EN'] for t in ts];counts.append(len(ps))
        elif part:
            normal=chinese.text_normalize(part)
            if not normal:continue
            p,t,c=chinese._g2p([normal]);normalized.append(normal);phones+=p;tones+=t;counts+=c
    return ''.join(normalized),['_']+phones+['_'],[0]+tones+[0],[1]+counts+[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models',required=True,type=Path)
    parser.add_argument('--candidate',required=True,type=Path)
    parser.add_argument('--question-id',required=True)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--speed',type=float,default=1.0)
    args=parser.parse_args()
    if args.output.exists():raise ValueError('Output exists')
    if not 0.8<=args.speed<=1.3 or not math.isfinite(args.speed):raise ValueError('Speed outside review range')
    guard_runtime()
    models=verified_bundle(args.models,HERE/'model-source.json')
    sys.path.insert(0,str(HERE.parent/'tools'))
    import content_candidates as candidates
    pack=candidates.load(args.candidate)
    rows=[q for q in pack['questions'] if q['id']==args.question_id]
    if len(rows)!=1:raise ValueError('Unknown question')
    row=rows[0];text=candidates.transcript(row,'zh-CN')
    if len(text)>300:raise ValueError('Prompt too long')
    # Verify vendored runtime inputs before importing them.
    provenance=json.loads((HERE/'vendor-provenance.json').read_text())
    for entry in provenance['files']:
        p=HERE/'vendor'/entry['path']
        if hashlib.sha256(p.read_bytes()).hexdigest()!=entry['adaptedSha256']:raise ValueError('Vendor hash mismatch')
    sys.path.insert(0,str(HERE/'vendor'))
    import torch
    import numpy as np
    from transformers import BertTokenizerFast,BertForMaskedLM
    from melo.models import SynthesizerTrn
    from melo import commons
    from melo.text import chinese,symbols
    torch.set_num_threads(2);torch.set_num_interop_threads(1);torch.manual_seed(20261003)
    started=time.monotonic()
    config=json.loads((models/'melo/config.json').read_text())
    data=config['data'];sampling=int(data['sampling_rate'])
    model=SynthesizerTrn(len(config['symbols']),data['filter_length']//2+1,config['train']['segment_size']//data['hop_length'],
        n_speakers=data['n_speakers'],num_tones=config['num_tones'],num_languages=config['num_languages'],**config['model']).cpu().eval()
    state=torch.load(models/'melo/checkpoint.pth',map_location='cpu',weights_only=True)
    model.load_state_dict(state['model'],strict=True);del state
    # Direct verified BERT files avoid transformers 4.57.6 falsely classifying old local config as Mistral.
    tokenizer=BertTokenizerFast(vocab_file=str(models/'bert/vocab.txt'),tokenizer_file=str(models/'bert/tokenizer.json'),do_lower_case=True,model_max_length=512)
    bert_model=BertForMaskedLM.from_pretrained(str(models/'bert'),local_files_only=True,use_safetensors=True).cpu().eval()
    normal,phone,tone,counts=phonemes(text,chinese,symbols)
    ids={s:i for i,s in enumerate(config['symbols'])}
    phone=[ids[p] for p in phone];language=[symbols.language_id_map['ZH_MIX_EN']]*len(phone)
    if data['add_blank']:
        phone=commons.intersperse(phone,0);tone=commons.intersperse(tone,0);language=commons.intersperse(language,0)
        counts=[n*2 for n in counts];counts[0]+=1
    tokens=tokenizer(normal,return_tensors='pt')
    if tokens['input_ids'].shape[1]!=len(counts):raise ValueError('Token/phoneme alignment mismatch')
    with torch.inference_mode():
        hidden=bert_model(**tokens,output_hidden_states=True).hidden_states[-3][0]
        features=torch.cat([hidden[i].repeat(count,1) for i,count in enumerate(counts)]).T
        if features.shape!=(768,len(phone)):raise ValueError('Feature alignment mismatch')
        wav=model.infer(torch.tensor([phone]),torch.tensor([len(phone)]),torch.tensor([data['spk2id']['ZH']]),
            torch.tensor([tone]),torch.tensor([language]),torch.zeros(1,1024,len(phone)),features.unsqueeze(0),
            noise_scale=.6,noise_scale_w=.8,sdp_ratio=.2,length_scale=1/args.speed)[0][0,0].cpu().numpy()
    if not np.isfinite(wav).all() or not 0.1<=len(wav)/sampling<=60:raise ValueError('Invalid generated signal')
    output_samples=int(round(len(wav)*16000/sampling))
    signal=np.interp(np.arange(output_samples)*sampling/16000,np.arange(len(wav)),wav)
    pcm=(np.clip(signal,-1,1)*32767).round().astype('<i2').tobytes()
    duration=math.ceil(len(pcm)*1000/32000)
    if duration+1000>row['locales']['zh-CN']['readingMillis']:raise ValueError('Untruncated audio does not fit reading window')
    peak=float(np.abs(signal).max());rms=float(np.sqrt(np.mean(signal*signal)))
    if rms<.001 or peak>1:raise ValueError('Silence/clipping review failed')
    args.output.mkdir(parents=True)
    target=args.output/'candidate.wav'
    with wave.open(str(target),'wb') as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(16000);f.writeframes(pcm)
    (args.output/'transcript.txt').write_text(text+'\n')
    receipt={'candidateSchemaVersion':1,'questionId':row['id'],'locale':'zh-CN','textSha256':hashlib.sha256(text.encode()).hexdigest(),
      'sourceCandidateSha256':hashlib.sha256(args.candidate.read_bytes()).hexdigest(),'audioSha256':hashlib.sha256(target.read_bytes()).hexdigest(),
      'durationMillis':duration,'readingMillis':row['locales']['zh-CN']['readingMillis'],'peak':peak,'rms':rms,'speed':args.speed,
      'engine':'MeloTTS pinned Chinese-only PyTorch adapter','modelManifestSha256':hashlib.sha256((HERE/'model-source.json').read_bytes()).hexdigest(),
      'rendererSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
      'nativeNetworkObservation':False,
      'elapsedSeconds':round(time.monotonic()-started,2),'peakRssKiB':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
      'listened':False,'approved':False,'realUserAudio':False,'networkAudit':'Python audit operations denied; not a comprehensive native network observation'}
    (args.output/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(receipt,ensure_ascii=False))

if __name__=='__main__':main()
