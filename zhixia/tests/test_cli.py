"""Each command runs in a new process, exercising persisted state end to end."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class CLITests(unittest.TestCase):
    def test_separate_process_create_approve_execute_verify(self):
        with tempfile.TemporaryDirectory() as directory:
            def run(*args):
                process = subprocess.run([sys.executable, '-m', 'zhixia', '--home', directory, *args],
                                         capture_output=True, text=True, encoding='utf-8', check=True,
                                         env={**os.environ, 'PYTHONIOENCODING': 'utf-8'})
                return json.loads(process.stdout)
            created = run('create', '--path', 'hello.txt', '--text', '你好，知夏', '--provider', 'fake-b')
            self.assertEqual(created['provider_mode'], 'deterministic_fake_no_model_call')
            task = created['task_id']
            self.assertEqual(run('tick', task)['state'], 'waiting_approval')
            shown = run('show', task)
            self.assertEqual(shown['task']['provider'], 'fake-b')
            run('approve', shown['approvals'][-1]['id'])
            self.assertEqual(run('tick', task)['state'], 'ready')
            self.assertEqual(run('tick', task)['state'], 'succeeded')
            self.assertEqual(run('show', task)['task']['state'], 'succeeded')
            self.assertEqual((Path(directory) / 'workspace/hello.txt').read_bytes(), '你好，知夏'.encode())

    def test_recover_requires_stopped_worker_acknowledgement(self):
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.run([sys.executable, '-m', 'zhixia', '--home', directory, 'recover', 'test'],
                                     capture_output=True, text=True, check=False)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn('--worker-stopped', process.stderr)
