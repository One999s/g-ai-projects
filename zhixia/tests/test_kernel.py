import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zhixia.contracts import Action, State, TaskSummary
from zhixia.kernel import Kernel
from zhixia.providers import FakeProvider, LiveProviderConfig, ProviderUnavailable, Router
from zhixia.tools import PolicyError


class KernelTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.now = 1000
        self.kernel = Kernel(self.home / 'state.db', self.home / 'workspace', lambda: self.now)

    def tearDown(self):
        self.kernel.close()
        self.temp.cleanup()

    def create(self, text='你好，知夏\n', target='notes/测试.txt'):
        return self.kernel.create(target, text, Router([FakeProvider()]))

    def approve(self, task):
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        approval = self.kernel.pending(task)[-1]['id']
        self.kernel.decide(approval, True)
        return approval

    def test_persistent_approval_write_and_verification(self):
        task = self.create()
        approval = self.approve(task)
        self.kernel.close()
        self.kernel = Kernel(self.home / 'state.db', self.home / 'workspace', lambda: self.now)
        self.assertEqual(self.kernel.tick(task), State.READY)
        self.assertEqual(self.kernel.tick(task), State.SUCCEEDED)
        self.assertEqual((self.home / 'workspace/notes/测试.txt').read_bytes(), '你好，知夏\n'.encode())
        ops = self.kernel.db.execute('SELECT * FROM operations').fetchall()
        self.assertEqual(len(ops), 2)
        self.assertTrue(all(json.loads(op['receipt'])['verified'] for op in ops))
        self.assertEqual(ops[0]['approval_id'], approval)
        self.assertEqual(self.kernel.tick(task), State.SUCCEEDED)
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM operations').fetchone()[0], 2)
        with self.assertRaises(ValueError):
            self.kernel.decide(approval, True)

    def test_approval_expiry_before_decision(self):
        task = self.create()
        self.kernel.tick(task, approval_ttl=1)
        approval = self.kernel.pending(task)[-1]['id']
        self.now += 2
        with self.assertRaises(ValueError):
            self.kernel.decide(approval, True)
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        self.assertNotEqual(approval, self.kernel.pending(task)[-1]['id'])

    def test_approval_expiry_after_decision(self):
        task = self.create()
        self.approve(task)
        self.now += 301
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        self.assertFalse((self.home / 'workspace/notes/测试.txt').exists())

    def test_changed_plan_cannot_reuse_approval(self):
        task = self.create()
        self.approve(task)
        plan = self.kernel.task(task)['plan']
        plan[0]['arguments']['text'] = 'changed'
        self.kernel.db.execute('UPDATE tasks SET plan=? WHERE id=?', (json.dumps(plan), task))
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        self.assertFalse((self.home / 'workspace/notes/测试.txt').exists())

    def test_deny_is_terminal(self):
        task = self.create()
        self.kernel.tick(task)
        self.kernel.decide(self.kernel.pending(task)[-1]['id'], False)
        self.assertEqual(self.kernel.tick(task), State.CANCELLED)

    def test_cancel_paused_does_not_execute(self):
        task = self.create()
        self.approve(task)
        self.kernel.cancel(task)
        self.assertEqual(self.kernel.tick(task), State.CANCELLED)
        self.assertFalse((self.home / 'workspace/notes/测试.txt').exists())

    def test_cancel_during_execution_records_effect(self):
        task = self.create()
        self.approve(task)
        execute = self.kernel.files.execute
        def cancel_then_execute(action):
            self.kernel.cancel(task)
            return execute(action)
        with patch.object(self.kernel.files, 'execute', cancel_then_execute):
            self.assertEqual(self.kernel.tick(task), State.CANCELLED)
        self.assertTrue((self.home / 'workspace/notes/测试.txt').exists())
        self.assertEqual(self.kernel.task(task)['step'], 1)

    def test_second_worker_cannot_claim_running_action(self):
        task = self.create()
        self.approve(task)
        execute = self.kernel.files.execute
        def check_second_worker(action):
            other = Kernel(self.home / 'state.db', self.home / 'workspace', lambda: self.now)
            try:
                self.assertEqual(other.tick(task), State.RUNNING)
            finally:
                other.close()
            return execute(action)
        with patch.object(self.kernel.files, 'execute', check_second_worker):
            self.assertEqual(self.kernel.tick(task), State.READY)

    def test_actual_process_crash_after_write_reconciles_without_replay(self):
        task = self.create()
        self.approve(task)
        script = '''import os,sys
from pathlib import Path
from zhixia.kernel import Kernel
k=Kernel(Path(sys.argv[1]),Path(sys.argv[2]),lambda:1000)
execute=k.files.execute
def crash(action):
    execute(action)
    os._exit(73)
k.files.execute=crash
k.tick(sys.argv[3])
'''
        child = subprocess.run([sys.executable, '-c', script, str(self.home / 'state.db'),
                                str(self.home / 'workspace'), task], check=False)
        self.assertEqual(child.returncode, 73)
        self.assertEqual(self.kernel.task(task)['state'], State.RUNNING)
        self.assertEqual(self.kernel.recover(task), State.NEEDS_RECONCILIATION)
        with patch.object(self.kernel.files, 'execute', side_effect=AssertionError('must not replay')):
            self.assertEqual(self.kernel.reconcile(task), State.READY)
        self.assertEqual(self.kernel.tick(task), State.SUCCEEDED)

    def test_concurrent_abandon_cannot_be_revived_by_reconciliation(self):
        task = self.create()
        self.approve(task)
        execute = self.kernel.files.execute
        def write_then_lose_receipt(action):
            execute(action)
            raise OSError('lost receipt')
        with patch.object(self.kernel.files, 'execute', write_then_lose_receipt):
            self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        other = Kernel(self.home / 'state.db', self.home / 'workspace', lambda: self.now)
        reconcile = self.kernel.files.reconcile
        def read_then_abandon(action):
            receipt = reconcile(action)
            other.abandon(task)
            return receipt
        try:
            with patch.object(self.kernel.files, 'reconcile', read_then_abandon):
                with self.assertRaisesRegex(ValueError, 'no longer completable'):
                    self.kernel.reconcile(task)
        finally:
            other.close()
        self.assertEqual(self.kernel.task(task)['state'], State.FAILED)
        self.assertEqual(self.kernel.tick(task), State.FAILED)
        operation = self.kernel.db.execute('SELECT status FROM operations WHERE task_id=?', (task,)).fetchone()
        self.assertEqual(operation[0], 'abandoned')

    def test_unknown_missing_effect_is_not_replayed(self):
        task = self.create()
        self.approve(task)
        with patch.object(self.kernel.files, 'execute', side_effect=OSError('private failure detail')):
            self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.kernel.reconcile(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertFalse((self.home / 'workspace/notes/测试.txt').exists())
        events = ' '.join(row[0] for row in self.kernel.db.execute('SELECT detail FROM events'))
        self.assertNotIn('private failure detail', events)
        self.kernel.abandon(task)
        self.assertEqual(self.kernel.task(task)['state'], State.FAILED)

    def test_existing_different_file_never_overwritten(self):
        path = self.home / 'workspace/existing.txt'
        path.write_text('original')
        task = self.create(target='existing.txt')
        self.approve(task)
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(path.read_text(), 'original')

    def test_separate_verify_detects_tamper(self):
        task = self.create()
        self.approve(task)
        self.kernel.tick(task)
        (self.home / 'workspace/notes/测试.txt').write_text('tampered')
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)

    def test_workspace_database_binding(self):
        with self.assertRaises(ValueError):
            Kernel(self.home / 'state.db', self.home / 'other')

    def test_traversal_absolute_stream_and_runtime_denied(self):
        for path in ['../escape.txt', '/tmp/escape.txt', 'C:\\escape.txt', '\\\\host\\share', 'file.txt:secret', '.runtime/state.db', 'CON.txt', 'NUL', 'file.txt.', 'file.txt ']:
            with self.subTest(path=path), self.assertRaises(PolicyError):
                self.create(target=path)

    def test_symlink_denied(self):
        outside = self.home / 'outside'
        outside.mkdir()
        try:
            (self.home / 'workspace/link').symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest('OS does not permit symlinks for this account')
        with self.assertRaises(PolicyError):
            self.create(target='link/out.txt')

    def test_hardlinked_target_denied(self):
        outside = self.home / 'outside.txt'
        outside.write_text('protected')
        os.link(outside, self.home / 'workspace/link.txt')
        with self.assertRaises(PolicyError):
            self.create(target='link.txt')

    def test_arbitrary_shell_and_oversize_denied(self):
        with self.assertRaises(PolicyError):
            self.kernel.files.execute(Action('shell', {'command': 'echo test'}))
        with self.assertRaises(PolicyError):
            self.create('x' * (1024 * 1024 + 1))


class ProviderTests(unittest.TestCase):
    summary = TaskSummary('t', 'explicit goal', 'ready', 0, 2)

    def test_fake_fallback_exports_explicit_summary(self):
        fallback = FakeProvider('fake-b')
        name, plan = Router([FakeProvider(unavailable=True), fallback]).plan(self.summary, 'a.txt', 'hello')
        self.assertEqual(name, 'fake-b')
        self.assertEqual(fallback.last_summary, self.summary)
        self.assertEqual(plan[0].tool, 'file.write')

    def test_budget_gate(self):
        provider = FakeProvider()
        provider.cost_microusd = 1
        with self.assertRaises(ProviderUnavailable):
            Router([provider], 0).plan(self.summary, 'a.txt', 'x')

    def test_unexpected_error_does_not_trigger_fallback(self):
        provider = FakeProvider()
        with patch.object(provider, 'plan', side_effect=ValueError('bad output')):
            with self.assertRaises(ValueError):
                Router([provider, FakeProvider('fake-b')]).plan(self.summary, 'a', 'x')

    def test_provider_endpoint_validation(self):
        for endpoint in ['http://example.com', 'https://user:secret@example.com', 'https://example.com?key=secret']:
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                LiveProviderConfig('openai', 'configured-model', endpoint, 'ZX_TEST_KEY').validate()
        with patch.dict(os.environ, {'ZX_TEST_KEY': 'test-not-a-real-key'}):
            for kind in ['openai', 'claude', 'grok', 'deepseek', 'compatible']:
                LiveProviderConfig(kind, 'configured-model', 'https://api.example.com/v1', 'ZX_TEST_KEY').validate()
        for kind in ['ollama', 'vllm']:
            LiveProviderConfig(kind, 'local-model', 'http://127.0.0.1:11434').validate()

    def test_live_connect_is_explicitly_not_implemented(self):
        with self.assertRaises(NotImplementedError):
            LiveProviderConfig('ollama', 'test', 'http://127.0.0.1:11434').connect()


if __name__ == '__main__':
    unittest.main()
