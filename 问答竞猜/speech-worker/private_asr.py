#!/usr/bin/env python3
"""Single-request loopback worker. Model data must already exist locally and match a manifest."""
import argparse
import hashlib
import importlib.util
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
import re
from pathlib import Path
import socket
import struct
import time

MAX_BYTES = 192044
MODEL_PROFILES = {'tiny': 'model-source.json', 'small-2ec96c54': 'model-source-small.json'}
DEFAULT_MODEL_FILE_LIMIT = 100 * 1024 * 1024
SMALL_MODEL_BYTES = 483546902


def pcm_bytes(data):
    if not 3244 <= len(data) <= MAX_BYTES or (len(data) - 44) % 2:
        raise ValueError('Canonical WAV required')
    if data[:4] != b'RIFF' or data[8:16] != b'WAVEfmt ' or data[36:40] != b'data':
        raise ValueError('Canonical WAV required')
    if struct.unpack_from('<I', data, 4)[0] != len(data)-8 or struct.unpack_from('<IHHIIHH', data, 16) != (16,1,1,16000,32000,2,16) or struct.unpack_from('<I', data, 40)[0] != len(data)-44:
        raise ValueError('Canonical WAV required')
    return memoryview(data)[44:]


def file_hash(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): value.update(block)
    return value.hexdigest()


def source_manifest(profile='tiny'):
    if profile not in MODEL_PROFILES: raise ValueError('Explicit approved model profile required')
    return json.loads(Path(__file__).with_name(MODEL_PROFILES[profile]).read_text())


def verified_model(directory, profile='tiny'):
    root = Path(directory).resolve()
    manifest_path = root / 'model-manifest.json'
    if manifest_path.is_symlink() or not manifest_path.is_file() or manifest_path.stat().st_size > 16384:
        raise ValueError('Explicit local model manifest required')
    manifest = json.loads(manifest_path.read_text())
    expected = source_manifest(profile)
    if manifest != expected: raise ValueError('Unreviewed model revision or manifest')
    files = manifest.get('files', {})
    if not {'model.bin','config.json','tokenizer.json'} <= set(files) or not set(files) <= {'model.bin','config.json','tokenizer.json','vocabulary.json','vocabulary.txt','preprocessor_config.json'}:
        raise ValueError('Incomplete local model')
    for name, expected in files.items():
        path = root / name
        limit = DEFAULT_MODEL_FILE_LIMIT
        if profile == 'small-2ec96c54' and name == 'model.bin':
            limit = SMALL_MODEL_BYTES  # Exact reviewed artifact; never a generic larger-model allowance.
        if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
            raise ValueError('Local model hash or size mismatch')
        if profile == 'small-2ec96c54' and path.stat().st_size != manifest['sizes'][name]:
            raise ValueError('Pinned small model size mismatch')
        if file_hash(path) != expected: raise ValueError('Local model hash or size mismatch')
    return root


class LocalWhisper:
    def __init__(self, directory, profile='tiny'):
        root = verified_model(directory, profile)
        # Refuse the telemetry-capable dependency before any model/runtime import.
        # The pinned non-batched path has vad_filter=False and does not require ONNX VAD.
        if importlib.util.find_spec('onnxruntime') is not None:
            raise RuntimeError('ONNX_RUNTIME_MUST_BE_ABSENT')
        # Local asset selection does not itself prove zero network egress; runtime acceptance is separate.
        os.environ['HF_HUB_OFFLINE'] = '1'
        os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
        from faster_whisper import WhisperModel
        self.model = WhisperModel(str(root), device='cpu', compute_type='int8', cpu_threads=2,
                                  num_workers=1, local_files_only=True)

    def __call__(self, data, locale):
        import numpy as np
        audio = np.frombuffer(pcm_bytes(data), dtype='<i2').astype(np.float32) / 32768.0
        if float(np.sqrt(np.mean(audio * audio))) < 0.002: return ''
        segments, _ = self.model.transcribe(audio, language='zh' if locale == 'zh-CN' else 'en',
                                           beam_size=1, best_of=1, temperature=0,
                                           condition_on_previous_text=False, vad_filter=False,
                                           without_timestamps=True, word_timestamps=False,
                                           max_new_tokens=48)
        words = []
        for segment in segments:
            words.append(segment.text.strip())
            if sum(len(x) for x in words) > 160: raise ValueError('Transcript limit')
        return ' '.join(words).strip()


class LocalServer(HTTPServer):
    request_queue_size = 4
    allow_reuse_address = False
    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(1.0)
        return connection, address


def make_server(transcribe, port=9609):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'
        server_version = 'QuizPrivateASR'
        sys_version = ''
        def log_message(self, *_): pass  # Do not log audio, transcript, request targets or headers.
        def reply(self, status, value):
            raw = json.dumps(value, ensure_ascii=False).encode('utf-8')
            self.close_connection = True
            try:
                self.send_response(status)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(raw)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers(); self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError): pass
        def do_GET(self):
            if self.path == '/health': self.reply(200, {'ready': True, 'audioPersisted': False})
            else: self.reply(404, {'error': 'NOT_FOUND'})
        def do_POST(self):
            if self.path != '/internal/quiz/asr': self.reply(404, {'error':'NOT_FOUND'}); return
            if self.headers.get('Transfer-Encoding') or self.headers.get('Content-Encoding') or self.headers.get('Content-Type') != 'audio/wav':
                self.reply(415, {'error':'CANONICAL_WAV_REQUIRED'}); return
            locale = self.headers.get('X-Quiz-Language')
            lengths = self.headers.get_all('Content-Length') or []
            if locale not in ('en','zh-CN') or len(lengths) != 1 or not re.fullmatch(r'[0-9]{1,6}', lengths[0]):
                self.reply(400, {'error':'INVALID_REQUEST'}); return
            length = int(lengths[0])
            if not 3244 <= length <= MAX_BYTES: self.reply(413, {'error':'AUDIO_SIZE_LIMIT'}); return
            data = bytearray(); end = time.monotonic() + 2
            try:
                while len(data) < length:
                    if time.monotonic() >= end: raise TimeoutError()
                    block = self.rfile.read1(min(8192,length-len(data)))
                    if not block: raise ValueError('Incomplete audio')
                    data.extend(block)
                pcm_bytes(data)
            except (ValueError, TimeoutError, socket.timeout):
                self.reply(400, {'error':'INVALID_AUDIO'}); return
            try:
                text = transcribe(bytes(data), locale)
                if not isinstance(text, str) or len(text) > 160 or any(ord(x) < 32 or 127 <= ord(x) <= 159 for x in text): raise ValueError()
                self.reply(200, {'text':text.strip()})
            except Exception:
                self.reply(503, {'error':'RECOGNITION_UNAVAILABLE'})
            finally:
                data.clear()
    return LocalServer(('127.0.0.1',port), Handler)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--model-profile', choices=tuple(MODEL_PROFILES), default='tiny')
    parser.add_argument('--port', type=int, default=9609)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535: parser.error('Invalid port')
    processor = LocalWhisper(args.model_dir, args.model_profile)
    with make_server(processor, args.port) as server:
        print('Quiz private ASR ready on literal loopback', flush=True)
        server.serve_forever()
