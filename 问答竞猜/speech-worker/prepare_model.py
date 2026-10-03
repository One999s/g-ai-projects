#!/usr/bin/env python3
"""Explicit preparation of one hash-pinned public model. Never called by the running worker."""
import argparse
import json
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('quiz_model_verification',Path(__file__).with_name('private_asr.py'))
verification=importlib.util.module_from_spec(spec);spec.loader.exec_module(verification)
file_hash,verified_model=verification.file_hash,verification.verified_model


def prepare(output):
    from huggingface_hub import snapshot_download
    output=Path(output)
    source=json.loads(Path(__file__).with_name('model-source.json').read_text())
    if output.exists() and any(output.iterdir()):
        verified_model(output)
        return
    snapshot_download(source['repository'],revision=source['revision'],local_dir=output,
                      allow_patterns=list(source['files']),token=False,max_workers=2)
    for name,sha in source['files'].items():
        p=output/name
        if p.is_symlink() or not p.is_file() or file_hash(p)!=sha:raise ValueError('Pinned public model hash mismatch')
    (output/'model-manifest.json').write_text(json.dumps(source,indent=2)+'\n')
    verified_model(output)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
    prepare(parser.parse_args().output);print('Pinned model verified; no user audio transmitted')
