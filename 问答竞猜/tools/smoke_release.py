#!/usr/bin/env python3
"""Loopback executable-JAR checks with a sanitized environment and no external services."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def stop(process):
    if process.poll() is None:
        process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def smoke(jar):
    jar = Path(jar).resolve()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL') if key in os.environ}
    command = ['java', '-Xmx192m', '-XX:ActiveProcessorCount=2', '-jar', str(jar),
               '--spring.config.location=classpath:/application.yml',
               '--server.address=127.0.0.1', '--server.port=0', '--quiz.redis.enabled=false']
    with tempfile.TemporaryDirectory(prefix='quiz-packaged-smoke-') as tmp:
        log = Path(tmp) / 'boot.log'
        with log.open('w') as output:
            process = subprocess.Popen([*command, '--spring.profiles.active=release-smoke'],
                                       cwd=tmp, env=env, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 45
                port = None
                while time.monotonic() < deadline:
                    text = log.read_text(errors='replace')[-100000:]
                    match = re.search(r'Tomcat started on port (\d+)', text)
                    if match:
                        port = int(match.group(1))
                        break
                    if process.poll() is not None:
                        raise RuntimeError('Packaged default-mode startup failed')
                    time.sleep(0.1)
                if not port:
                    raise RuntimeError('Packaged startup deadline exceeded')
                origin = 'http://127.0.0.1:' + str(port)
                with opener.open(origin + '/api/quiz/status', timeout=3) as response:
                    if json.load(response).get('productionReady') is not False:
                        raise RuntimeError('Status overstated production readiness')
                request = urllib.request.Request(origin + '/api/quiz/sessions', data=b'invalid-json',
                                                 headers={'Content-Type': 'application/json'})
                try:
                    opener.open(request, timeout=3)
                    raise RuntimeError('Packaged business endpoint unexpectedly admitted a request')
                except urllib.error.HTTPError as error:
                    body = json.load(error)
                    if error.code != 503 or body.get('error', {}).get('code') != 'IDENTITY_ADAPTER_NOT_CONFIGURED':
                        raise RuntimeError('Packaged identity boundary did not fail closed before JSON')
            finally:
                stop(process)
        # Missing shared-db target must stop startup, with no external target in this environment.
        with log.open('w') as output:
            process = subprocess.Popen([*command, '--spring.profiles.active=shared-db'],
                                       cwd=tmp, env=env, stdout=output, stderr=subprocess.STDOUT)
            try:
                code = process.wait(timeout=30)
                text = log.read_text(errors='replace')[-100000:]
                if code == 0 or 'SHARED_DATABASE_' not in text or 'Tomcat started on port' in text:
                    raise RuntimeError('Missing shared database target did not stop packaged startup safely')
            finally:
                stop(process)
    return {'loopbackPackagedHttp': True, 'missingIdentityRejectedBeforeBody': True,
            'missingSharedTargetRejectedBeforeListening': True, 'externalServicesUsed': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar')
    print(json.dumps(smoke(parser.parse_args().jar)))
