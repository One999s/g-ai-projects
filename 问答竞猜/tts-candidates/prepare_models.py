#!/usr/bin/env python3
"""Explicit public-weight download, separate from offline candidate inference."""
import argparse
import hashlib
import json
import os
from pathlib import Path

HERE=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);p.add_argument('--cache',type=Path,required=True);a=p.parse_args()
 root=a.directory.absolute();cache=a.cache.absolute()
 if root.exists():raise SystemExit('Choose a new model directory; existing data is not overwritten')
 cache.mkdir(parents=True,exist_ok=True)
 os.environ.update(HF_HOME=str(cache),HF_HUB_DISABLE_TELEMETRY='1',HF_HUB_DISABLE_XET='1',HF_HUB_DISABLE_IMPLICIT_TOKEN='1')
 from huggingface_hub import hf_hub_download
 spec=json.loads((HERE/'model-source.json').read_text());root.mkdir(parents=True)
 for source in spec['sources']:
  for name,expected in spec['files'].items():
   prefix=source['subdirectory']+'/'
   if not name.startswith(prefix):continue
   path=Path(hf_hub_download(repo_id=source['repository'],revision=source['revision'],filename=name[len(prefix):],local_dir=root/source['subdirectory'],token=False))
   if path.stat().st_size!=expected['size']:raise ValueError('Model size mismatch')
   h=hashlib.sha256()
   with path.open('rb') as f:
    for b in iter(lambda:f.read(1048576),b''):h.update(b)
   if h.hexdigest()!=expected['sha256']:raise ValueError('Model hash mismatch')
 print('Seven fixed model-data files verified. No model code or inference was executed.')

if __name__=='__main__':main()
