#!/usr/bin/env python3
"""Offline English development previews. Never approves audio or synthesizes unsupported Chinese."""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import content_candidates as content


def sha(raw): return hashlib.sha256(raw).hexdigest()


def inspect(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 1000 <= path.stat().st_size <= 3 * 1024 * 1024:
        raise ValueError('Invalid candidate audio file')
    probe = subprocess.run(['ffprobe', '-v', 'error', '-protocol_whitelist', 'file,pipe',
                            '-show_streams', '-show_format', '-of', 'json', str(path)],
                           check=True, capture_output=True, timeout=10)
    meta = json.loads(probe.stdout)
    streams = meta.get('streams', [])
    if len(streams) != 1 or streams[0].get('codec_name') != 'flac' or streams[0].get('channels') != 1 or int(streams[0].get('sample_rate', 0)) != 16000:
        raise ValueError('Candidate must be mono 16kHz FLAC')
    duration = float(meta['format']['duration'])
    if not math.isfinite(duration) or not 0.5 <= duration <= 60: raise ValueError('Audio duration out of range')
    decoded = subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-threads', '1',
                              '-protocol_whitelist', 'file,pipe', '-i', str(path),
                              '-f', 's16le', '-acodec', 'pcm_s16le', '-ar', '16000', '-ac', '1', 'pipe:1'],
                             check=True, capture_output=True, timeout=15).stdout
    if not 16000 <= len(decoded) <= 1920000: raise ValueError('Decoded size out of range')
    samples = array.array('h'); samples.frombytes(decoded)
    if sys.byteorder != 'little': samples.byteswap()
    peak = max(abs(x) for x in samples) / 32768
    rms = math.sqrt(sum(x*x for x in samples) / len(samples)) / 32768
    clipped = sum(abs(x) >= 32767 for x in samples)
    if rms < 0.002 or clipped / len(samples) > 0.001: raise ValueError('Silent or excessively clipped candidate')
    return {'audioSha256': sha(path.read_bytes()), 'bytes': path.stat().st_size,
            'durationMillis': round(duration * 1000), 'sampleRate': 16000, 'channels': 1,
            'peak': round(peak, 6), 'rms': round(rms, 6), 'clippedSamples': clipped}


def render(candidate, output):
    pack = content.load(candidate); output = Path(output)
    if output.exists(): raise ValueError('Output exists; audio candidates are never overwritten')
    version = subprocess.run(['ffmpeg', '-version'], check=True, capture_output=True, text=True).stdout.splitlines()[0]
    output.mkdir(parents=True); (output / 'en').mkdir()
    entries = []
    for row in pack['questions']:
        spoken = content.transcript(row, 'en') + '\n'
        relative = 'en/' + row['id'] + '.flac'
        transcript_file = 'en/' + row['id'] + '.txt'
        (output / transcript_file).write_text(spoken, encoding='utf-8')
        with tempfile.TemporaryDirectory(prefix='quiz-audio-', dir='/tmp') as tmp:
            textfile = Path(tmp) / 'spoken.txt'; textfile.write_text(spoken, encoding='utf-8')
            subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-threads', '1', '-filter_threads', '1',
                            '-f', 'lavfi', '-i', f'flite=textfile={textfile}:voice=slt',
                            '-ar', '16000', '-ac', '1', '-c:a', 'flac', '-compression_level', '8',
                            '-fflags', '+bitexact', '-flags:a', '+bitexact', '-map_metadata', '-1',
                            str(output / relative)], check=True, timeout=30)
        entries.append({'id': row['id'], 'locale': 'en', 'file': relative,
                        'transcriptFile': transcript_file, 'transcriptSha256': sha(spoken.encode()),
                        'voice': 'flite:slt', 'listened': False, 'approved': False, **inspect(output / relative)})
    manifest = {'audioCandidateSchemaVersion': 1, 'status': 'draft', 'approved': False,
                'candidateSha256': sha(Path(candidate).read_bytes()), 'engine': version,
                'generation': 'offline synthetic development preview',
                'humanListeningCompleted': False, 'rightsReview': 'pending-human',
                'missingLocales': {'zh-CN': 'No suitable authorized local Chinese renderer configured'},
                'entries': entries}
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return verify(candidate, output)


def verify(candidate, output):
    pack = content.load(candidate); output = Path(output)
    manifest = json.loads((output / 'manifest.json').read_text(), object_pairs_hook=content.unique_object, parse_constant=lambda _: content.fail('Non-finite metric'))
    content.exact(manifest, 'audioCandidateSchemaVersion status approved candidateSha256 engine generation humanListeningCompleted rightsReview missingLocales entries')
    if manifest.get('missingLocales') != {'zh-CN': 'No suitable authorized local Chinese renderer configured'}: raise ValueError('Unsupported locale claim')
    if manifest.get('audioCandidateSchemaVersion') != 1 or manifest.get('status') != 'draft' or manifest.get('approved') is not False or manifest.get('humanListeningCompleted') is not False or manifest.get('rightsReview') != 'pending-human':
        raise ValueError('Candidate cannot claim approval or listening')
    if manifest.get('candidateSha256') != sha(Path(candidate).read_bytes()): raise ValueError('Candidate source hash mismatch')
    entries = manifest.get('entries', [])
    if len(entries) != len(pack['questions']): raise ValueError('Audio candidate count mismatch')
    by_id = {row['id']: row for row in pack['questions']}; seen = set()
    for entry in entries:
        content.exact(entry, 'id locale file transcriptFile transcriptSha256 voice listened approved audioSha256 bytes durationMillis sampleRate channels peak rms clippedSamples')
        identity = entry.get('id')
        if identity not in by_id or identity in seen: raise ValueError('Duplicate or foreign audio ID')
        seen.add(identity)
        relative, textfile = 'en/' + identity + '.flac', 'en/' + identity + '.txt'
        if entry.get('locale') != 'en' or entry.get('file') != relative or entry.get('transcriptFile') != textfile or entry.get('voice') != 'flite:slt': raise ValueError('Audio binding mismatch')
        if entry.get('approved') is not False or entry.get('listened') is not False: raise ValueError('Preview cannot claim approval')
        if (output / 'en').is_symlink() or (output / textfile).is_symlink(): raise ValueError('Symlink not permitted')
        expected = (content.transcript(by_id[identity], 'en') + '\n').encode('utf-8')
        if (output / textfile).read_bytes() != expected or entry.get('transcriptSha256') != sha(expected): raise ValueError('Question/options transcript drift')
        measured = inspect(output / relative)
        if measured['durationMillis'] + 1000 > by_id[identity]['locales']['en']['readingMillis']:
            raise ValueError('Reading window is shorter than audio plus safety margin')
        if any(entry.get(key) != value for key, value in measured.items()): raise ValueError('Audio hash or measurement drift')
    return {'validCandidates': len(entries), 'approved': False, 'listened': False, 'missingLocales': ['zh-CN']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify(args.candidate, args.output) if args.verify else render(args.candidate, args.output)))
