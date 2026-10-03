#!/usr/bin/env python3
"""Prepare a new isolated runtime from an explicit registry dependency list, never launch it."""
import argparse
from pathlib import Path
import re
import subprocess
import sys


def reviewed_requirements():
    lock=Path(__file__).with_name('requirements.lock')
    names=[]
    for line in lock.read_text().splitlines():
        if not line or line.startswith('#'):continue
        if not re.fullmatch(r'[A-Za-z0-9_-]+==[A-Za-z0-9.]+',line):raise ValueError('Unpinned runtime dependency')
        names.append(line.split('==')[0].replace('_','-').lower())
    if 'onnxruntime' in names or any('onnx' in name for name in names):raise ValueError('ONNX is forbidden in this runtime')
    if 'faster-whisper' not in names or 'ctranslate2' not in names or len(names)!=len(set(names)):raise ValueError('Incomplete runtime')
    return lock


def install(directory):
    lock=reviewed_requirements();directory=Path(directory).resolve()
    if directory.exists():raise ValueError('Use a new isolated environment; existing environments are not modified')
    subprocess.run([sys.executable,'-I','-m','venv',str(directory)],check=True)
    python=directory/'bin/python'
    subprocess.run([str(python),'-I','-m','pip','install','--only-binary=:all:','--no-deps','-r',str(lock)],check=True)
    # Discovery only; no ONNX/faster-whisper/native inference module is imported here.
    subprocess.run([str(python),'-I','-c',"import importlib.util; assert importlib.util.find_spec('onnxruntime') is None"],check=True)
    print('Explicit runtime prepared without ONNX; model/worker was not started')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path)
    install(parser.parse_args().directory)
