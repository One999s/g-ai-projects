#!/usr/bin/env python3
"""Explicit preparation only: fixed official CPU wheel and hash-locked PyPI dependencies."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys
import venv

HERE=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--directory',type=Path,required=True);a=p.parse_args()
 root=a.directory.absolute()
 if root.exists():raise SystemExit('A new dedicated directory is required')
 if sys.version_info[:2]!=(3,12) or platform.system()!='Linux' or platform.machine()!='x86_64':raise SystemExit('Lock is Linux x86_64 CPython 3.12 only')
 root.mkdir(parents=True);venv.create(root/'venv',with_pip=True)
 py=root/'venv/bin/python';cache=root/'pip-cache';wheelhouse=root/'wheelhouse';wheelhouse.mkdir()
 base=[str(py),'-m','pip','--disable-pip-version-check','--cache-dir',str(cache)]
 spec=json.loads((HERE/'torch-source.json').read_text())
 if spec['index']!='https://download.pytorch.org/whl/cpu' or spec['version']!='2.14.1+cpu':raise ValueError('Unexpected torch source')
 subprocess.run(base+['download','--no-deps','--only-binary=:all:','--index-url',spec['index'],'torch=='+spec['version'],'--dest',str(wheelhouse)],check=True)
 wheel=wheelhouse/spec['filename']
 if wheel.stat().st_size!=spec['bytes'] or hashlib.sha256(wheel.read_bytes()).hexdigest()!=spec['sha256']:raise ValueError('CPU wheel hash mismatch')
 lock=(HERE/'requirements.lock').read_text();setup=next(s for s in lock.splitlines() if s.startswith('setuptools=='))
 (root/'setuptools.lock').write_text(setup+'\n')
 for file,extra in [(root/'setuptools.lock',[]),(HERE/'requirements.lock',['--no-build-isolation'])]:
  subprocess.run(base+['install','--no-deps','--require-hashes','--index-url','https://pypi.org/simple',*extra,'-r',str(file)],check=True)
 subprocess.run(base+['install','--no-deps','--no-index',str(wheel)],check=True)
 subprocess.run([str(py),'-c',"import importlib.util; assert importlib.util.find_spec('onnxruntime') is None; assert importlib.util.find_spec('hf_xet') is None"],check=True)
 print('Prepared fixed candidate environment. No model inference was executed.')

if __name__=='__main__':main()
