"""No native/model imports: validate local-only candidate inputs before loading dependencies."""
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path


def prepare_environment():
    # Presence is rejected without importing the module that triggered the previous incident.
    if importlib.util.find_spec('onnxruntime') is not None:
        raise RuntimeError('ONNX is not permitted in this candidate environment')
    os.environ.update(HF_HUB_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1', TRANSFORMERS_OFFLINE='1',
                      HF_HUB_DISABLE_IMPLICIT_TOKEN='1', HF_TOKEN='', HUGGING_FACE_HUB_TOKEN='',
                      DO_NOT_TRACK='1', OMP_NUM_THREADS='2', MKL_NUM_THREADS='2',
                      OPENBLAS_NUM_THREADS='2', TOKENIZERS_PARALLELISM='false')


def unique(pairs):
    d={}
    for k,v in pairs:
        if k in d: raise ValueError('Duplicate JSON key')
        d[k]=v
    return d


def verified_bundle(root, manifest):
    root=Path(root)
    if not root.is_absolute() or root.resolve()!=root: raise ValueError('Explicit real local directory required')
    spec=json.loads(Path(manifest).read_text(),object_pairs_hook=unique)
    expected={'melo/config.json','melo/checkpoint.pth','bert/config.json','bert/model.safetensors','bert/tokenizer.json','bert/tokenizer_config.json','bert/vocab.txt'}
    if set(spec)!={'schemaVersion','sources','files'} or type(spec['schemaVersion']) is not int or spec['schemaVersion']!=1 or set(spec['files'])!=expected: raise ValueError('Unexpected model inventory')
    total=0
    for name,item in spec['files'].items():
        if name.startswith('/') or '..' in Path(name).parts: raise ValueError('Unsafe model path')
        if set(item)!={'size','sha256'} or type(item['size']) is not int or not 0<item['size']<700000000 or not re.fullmatch('[a-f0-9]{64}',item['sha256']): raise ValueError('Invalid model hash record')
        p=root/name
        if p.is_symlink() or not p.is_file() or p.resolve()!=p or p.stat().st_size!=item['size']: raise ValueError('Invalid local model file')
        total+=item['size']
        if total>1000000000: raise ValueError('Model bundle too large')
        h=hashlib.sha256()
        with p.open('rb') as f:
            for b in iter(lambda:f.read(1048576),b''):h.update(b)
        if h.hexdigest()!=item['sha256']: raise ValueError('Model hash mismatch')
    return root
