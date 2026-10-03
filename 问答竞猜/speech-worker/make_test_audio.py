#!/usr/bin/env python3
"""Synthetic integration fixture, not a user's recording or an approved host voice."""
import argparse
from pathlib import Path
import struct
import subprocess


def generate(output):
    output=Path(output)
    if output.exists():raise ValueError('Synthetic fixture already exists')
    pcm=subprocess.run(['ffmpeg','-v','error','-nostdin','-threads','1','-filter_threads','1',
                        '-f','lavfi','-i',"flite=text='My answer is option C.':voice=slt",
                        '-ar','16000','-ac','1','-f','s16le','pipe:1'],check=True,capture_output=True,timeout=15).stdout
    if not 3200<=len(pcm)<=192000 or len(pcm)%2:raise ValueError('Synthetic fixture length')
    wave=b'RIFF'+struct.pack('<I',len(pcm)+36)+b'WAVEfmt '+struct.pack('<IHHIIHH',16,1,1,16000,32000,2,16)+b'data'+struct.pack('<I',len(pcm))+pcm
    output.write_bytes(wave)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
    generate(parser.parse_args().output);print('Synthetic Option C fixture created')
