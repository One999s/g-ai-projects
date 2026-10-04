#!/usr/bin/env python3
"""Explicit local fixed-fixture evaluation. Installs/downloads nothing; never run automatically in CI."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import threading
import time

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
MAX_RSS = 3 * 1024**3


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checks', choices=['all', 'chinese-confirmation'], default='all')
    parser.add_argument('--python', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--model-profile', choices=['tiny', 'small-2ec96c54'], required=True)
    parser.add_argument('--maven', type=Path, required=True)
    parser.add_argument('--m2', type=Path, required=True)
    parser.add_argument('--english-wav', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for path in [args.python, args.model_dir, args.maven, args.m2, args.english_wav, args.output]:
        if not path.is_absolute(): parser.error('All input and output paths must be explicit absolute paths')
    args.output.mkdir(exist_ok=False)
    env = os.environ.copy()
    env.update(HF_HUB_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1', HF_HUB_DISABLE_IMPLICIT_TOKEN='1',
               HF_TOKEN='', HUGGING_FACE_HUB_TOKEN='', OMP_NUM_THREADS='2', MKL_NUM_THREADS='2',
               OPENBLAS_NUM_THREADS='2', MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2',
               QUIZ_REAL_ASR_MODEL_PROFILE=args.model_profile)
    peak = 0
    stop_reason = None
    stopped = threading.Event()
    started = time.monotonic()
    run_results = []
    log = (args.output/'worker.log').open('w')
    worker = subprocess.Popen([str(args.python), '-I', str(HERE/'private_asr.py'), '--model-dir', str(args.model_dir),
                               '--model-profile', args.model_profile, '--port', '29609'], env=env, stdout=log, stderr=subprocess.STDOUT)

    def observe():
        nonlocal peak, stop_reason
        while not stopped.wait(.04):
            if worker.poll() is not None: return
            try:
                current = int(Path(f'/proc/{worker.pid}/statm').read_text().split()[1]) * os.sysconf('SC_PAGE_SIZE')
                peak = max(peak, current)
                if current > MAX_RSS or time.monotonic()-started > 240:
                    stop_reason = 'RSS_LIMIT' if current > MAX_RSS else 'TOTAL_TIME_LIMIT'
                    worker.kill()
                    return
            except (OSError, ValueError, IndexError):
                if worker.poll() is None:
                    stop_reason = 'RSS_OBSERVATION_FAILED'
                    worker.kill()
                return

    watcher = threading.Thread(target=observe, daemon=True)
    watcher.start()
    try:
        for _ in range(100):
            if worker.poll() is not None: raise RuntimeError('Worker exited before health readiness')
            try:
                connection = http.client.HTTPConnection('127.0.0.1', 29609, timeout=.2)
                connection.request('GET', '/health')
                response = connection.getresponse()
                ready = response.status == 200 and json.loads(response.read()).get('ready') is True
                connection.close()
                if ready: break
            except (OSError, ValueError): pass
            time.sleep(.1)
        else: raise RuntimeError('Worker readiness timeout')
        cases = [
            ('chinese-http', 'RealSpeechHttpIntegrationTest', {'QUIZ_REAL_ASR_INTEGRATION':'true', 'QUIZ_REAL_ASR_LOCALE':'zh-CN', 'QUIZ_REAL_ASR_RECEIPT':str(args.output/'zh')}),
            ('english-http', 'RealSpeechHttpIntegrationTest', {'QUIZ_REAL_ASR_INTEGRATION':'true', 'QUIZ_REAL_ASR_LOCALE':'en', 'QUIZ_REAL_ASR_WAV':str(args.english_wav), 'QUIZ_REAL_ASR_RECEIPT':str(args.output/'en')}),
            ('queue', 'RealSpeechQueueIntegrationTest', {'QUIZ_REAL_ASR_QUEUE':'true', 'QUIZ_REAL_ASR_QUEUE_RECEIPT':str(args.output/'queue.json')})]
        if args.checks == 'chinese-confirmation':
            name, _, variables = cases[0]
            cases = [(name, 'RealSpeechHttpIntegrationTest,SpeechRulesTest,GameHttpFlowTest', variables)]
        for name, test, variables in cases:
            if worker.poll() is not None: raise RuntimeError('Worker exited during evaluation')
            case_env = env.copy()
            # Prevent inherited opt-in flags from causing an unrelated native test to run.
            for key in ['QUIZ_REAL_ASR_INTEGRATION', 'QUIZ_REAL_ASR_QUEUE', 'QUIZ_REAL_ASR_LOCALE']:
                case_env.pop(key, None)
            case_env.update(variables)
            with (args.output/(name+'.log')).open('w') as output:
                result = subprocess.run([str(args.maven), '-o', '-B', '-ntp', '-Dmaven.repo.local='+str(args.m2),
                                         '-Dmaven.compiler.release=', '-Dmaven.compiler.source=21', '-Dmaven.compiler.target=21',
                                         '-DforkCount=0', '-Dtest='+test, 'test'], cwd=PROJECT/'backend', env=case_env,
                                        stdout=output, stderr=subprocess.STDOUT, timeout=90)
            run_results.append({'case':name, 'exitCode':result.returncode})
    finally:
        if worker.poll() is None: worker.terminate()
        try: worker.wait(timeout=3)
        except subprocess.TimeoutExpired: worker.kill(); worker.wait(timeout=3)
        stopped.set(); watcher.join(1); log.close()
        evidence = {'modelProfile':args.model_profile, 'checks':args.checks, 'workerPeakRssBytesSampled':peak, 'rssSampleMillis':40,
                    'rssLimitBytes':MAX_RSS, 'stopReason':stop_reason, 'workerExited':worker.poll() is not None,
                    'elapsedSeconds':round(time.monotonic()-started,3), 'results':run_results,
                    'nativeNetworkObservationComplete':False, 'commercialConcurrencyAcceptance':False,
                    'testOnlyIdentityQuestionBankQuotaAndClock':True,
                    'workerSha256':hashlib.sha256((HERE/'private_asr.py').read_bytes()).hexdigest()}
        (args.output/'process.json').write_text(json.dumps(evidence,indent=2)+'\n')
        print(json.dumps(evidence))
    if stop_reason or any(x['exitCode'] for x in run_results): raise SystemExit(1)


if __name__ == '__main__': main()
