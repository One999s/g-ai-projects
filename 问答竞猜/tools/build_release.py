#!/usr/bin/env python3
"""Build a clean, committed Quiz tree twice; emit a hash-bound, non-production-ready bundle."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from smoke_release import smoke

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT.parent


def command(args, cwd=ROOT, capture=False):
    return subprocess.run(args, cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def inspect_jar(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        classes = [n for n in names if n.startswith('BOOT-INF/classes/')]
        forbidden = [n for n in classes if '/test/' in n or n.endswith('Test.class')
                     or 'GameHttpFlowTest' in n or n.endswith('application-test.yml')]
        if forbidden or any(b'X-Test-Actor' in jar.read(name) for name in classes):
            raise RuntimeError('Test-only classes/resources found in runtime JAR')
        if any(n.startswith('BOOT-INF/lib/') and any(word in n for word in ('/h2-', '/junit', '/mockito', '/spring-boot-test')) for n in names):
            raise RuntimeError('Test dependency found in runtime JAR')
        required = ['BOOT-INF/classes/com/gaiprojects/quiz/QuizApplication.class',
                    'BOOT-INF/classes/com/gaiprojects/quiz/api/IdentityAdmission.class',
                    'BOOT-INF/classes/com/gaiprojects/quiz/quota/RedisRequestQuota.class']
        if any(n not in names for n in required):
            raise RuntimeError('Required runtime protection missing from JAR')
        return {'runtimeClassesAndResources': len(classes), 'testFixturesAbsent': True,
                'dependencyJars': sorted(n.removeprefix('BOOT-INF/lib/') for n in names
                                         if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'))}


def report_counts(directory):
    files = sorted(directory.glob('TEST-*.xml'))
    if not files:
        raise RuntimeError('No fresh JUnit reports')
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in files:
        root = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(root.attrib.get(key, 0))
    if totals['failures'] or totals['errors']:
        raise RuntimeError('Backend test failures')
    totals['passed'] = totals['tests'] - totals['skipped']
    return totals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-arg', action='append', default=[])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    os.environ.setdefault('MAVEN_OPTS', '-Xmx256m -XX:ActiveProcessorCount=2')
    relevant = ['问答竞猜', '.github/workflows/quiz.yml', '.gitignore']
    dirty = command(['git', 'status', '--porcelain', '--untracked-files=all', '--', *relevant], capture=True)
    if dirty.strip():
        raise SystemExit('Commit the Quiz source before building a release; working changes are refused')
    commit = command(['git', 'rev-parse', 'HEAD'], capture=True).strip()
    tree = command(['git', 'rev-parse', 'HEAD^{tree}'], capture=True).strip()
    timestamp = command(['git', 'show', '-s', '--format=%cI', 'HEAD'], capture=True).strip()
    output = (args.output or PROJECT / '.release' / commit[:12]).resolve()
    if output.exists():
        raise SystemExit('Output already exists; immutable release directories are never overwritten')
    source_paths = command(['git', 'ls-files', '-z', '--', *relevant], capture=True).split('\0')
    if any((ROOT / p).is_symlink() for p in source_paths if p):
        raise RuntimeError('Release inputs cannot be symlinks')
    inputs = {p: digest(ROOT / p) for p in sorted(source_paths) if p}
    command(['npm', 'ci'], PROJECT / 'frontend')
    command(['npm', 'run', 'verify'], PROJECT / 'frontend')
    dist = PROJECT / 'frontend/dist'
    frontend_hashes = {str(p.relative_to(dist)): digest(p) for p in sorted(dist.rglob('*')) if p.is_file()}
    maven = [args.maven, '-B', '-ntp', '-DforkCount=1',
             '-DargLine=-Xmx256m -XX:ActiveProcessorCount=2', *args.maven_arg,
             '-Dproject.build.outputTimestamp=' + timestamp]
    command([*maven, 'clean', 'verify'], PROJECT / 'backend')
    tests = report_counts(PROJECT / 'backend/target/surefire-reports')
    jars = [p for p in (PROJECT / 'backend/target').glob('*.jar') if not p.name.endswith('.original')]
    if len(jars) != 1:
        raise RuntimeError('Expected exactly one executable JAR')
    jar = jars[0]
    jar_report = inspect_jar(jar)
    first_hash = digest(jar)
    # A new clean compilation confirms byte-for-byte repeatability, not merely repackaging one JAR.
    command([*maven, 'clean', 'package', '-DskipTests'], PROJECT / 'backend')
    if digest(jar) != first_hash:
        raise RuntimeError('Clean repeated JAR build was not reproducible')
    command(['npm', 'run', 'build'], PROJECT / 'frontend')
    if frontend_hashes != {str(p.relative_to(dist)): digest(p) for p in sorted(dist.rglob('*')) if p.is_file()}:
        raise RuntimeError('Repeated frontend build was not reproducible')
    smoke_result = smoke(jar)
    if any(digest(ROOT / p) != sha for p, sha in inputs.items()):
        raise RuntimeError('Source changed during build')
    output.mkdir(parents=True)
    shutil.copy2(jar, output / ('quiz-challenge-' + commit[:12] + '.jar'))
    shutil.copytree(PROJECT / 'frontend/dist', output / 'frontend')
    shutil.copytree(PROJECT / 'deploy', output / 'deploy')
    shutil.copytree(PROJECT / 'docs', output / 'docs')
    shutil.copy2(PROJECT / 'README.md', output / 'README.md')
    with tempfile.TemporaryFile() as tar:
        subprocess.run(['git', 'archive', '--format=tar', commit, '--', *relevant], cwd=ROOT,
                       stdout=tar, check=True)
        tar.seek(0)
        with (output / 'source.tar.gz').open('wb') as raw:
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as gz:
                shutil.copyfileobj(tar, gz)
    files = {str(p.relative_to(output)): {'sha256': digest(p), 'bytes': p.stat().st_size}
             for p in sorted(output.rglob('*')) if p.is_file()}
    receipt = {'schemaVersion': 1, 'commit': commit, 'tree': tree,
               'outputTimestamp': timestamp, 'productionReady': False,
               'javaVersion': command(['java', '--version'], capture=True).splitlines()[0],
               'nodeVersion': command(['node', '--version'], capture=True).strip(),
               'identityAdapterImplemented': False, 'productionDatabaseAccessed': False,
               'jarRebuiltFromCleanTwice': True, 'frontendBuiltTwice': True, 'packagedSmoke': smoke_result, 'jarSha256': first_hash,
               'backendTestsThisBuild': tests, 'jarInspection': jar_report,
               'buildOverridesUsed': bool(args.maven_arg), 'sourceInputs': inputs,
               'files': files,
               'externalServiceTests': 'Separate CI jobs; opt-in skips here are not passes',
               'ciRun': os.environ.get('GITHUB_RUN_ID'),
               'sourceUrl': 'https://github.com/One999s/g-ai-projects/commit/' + commit}
    (output / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    print('Verified release bundle: ' + str(output))


if __name__ == '__main__':
    main()
