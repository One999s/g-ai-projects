"""Synthetic backend contract tests only. These never prove a Windows GUI path."""
from dataclasses import asdict
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from zhixia.contracts import ExecutionContext, State
from zhixia.kernel import Kernel
from zhixia.notepad import (DesktopUnavailable, ExecutionStopped, NotepadPlan, NotepadTasks,
                           PywinautoNotepad, WindowIdentity)
from zhixia.tools import PolicyError


class ContractBackend:
    def __init__(self):
        self.started = 0
        self.saved = 0
        self.device = 'synthetic-test-device'
        self.fail_after_save = False
        self.cancel_hook = None

    def inspect(self):
        return {'device_id': self.device, 'executable': '/synthetic/Notepad.exe',
                'adapter': 'contract-backend-v1', 'gui_validation': 'NOT_RUN'}

    def launch(self, plan, cancelled):
        if cancelled():
            raise ExecutionStopped()
        self.started += 1
        return WindowIdentity(123, 456, plan.executable, 'synthetic-birth-time')

    def input_and_save(self, plan, window, target, cancelled):
        if self.cancel_hook:
            self.cancel_hook()
        if cancelled():
            raise ExecutionStopped()
        self.saved += 1
        # This fixture writes bytes to exercise the verifier; production uses only UIA.
        target.write_bytes(plan.text.encode())
        if self.fail_after_save:
            raise OSError('simulated loss after external effect')


class NotepadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.backend = ContractBackend()
        self.now = 1000
        self.service = NotepadTasks(self.home / 'state.db', self.home / 'workspace', self.backend, lambda: self.now)
        self.kernel = self.service.kernel

    def tearDown(self):
        self.kernel.close()
        self.temp.cleanup()

    def approve(self, task):
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        approval = self.kernel.pending(task)[-1]['id']
        self.kernel.decide(approval, True)
        return approval

    def launch(self):
        task = self.service.prepare_launch('hello.txt', '你好，知夏')
        self.assertEqual(self.backend.started, 0)
        self.approve(task)
        self.assertEqual(self.kernel.tick(task), State.SUCCEEDED)
        self.assertEqual(self.backend.started, 1)
        return task

    def test_identical_concurrent_launches_use_explicit_operation_context(self):
        first = self.service.prepare_launch('hello.txt', 'text')
        second = self.service.prepare_launch('hello.txt', 'text')
        self.approve(first)
        self.approve(second)
        other = NotepadTasks(self.home / 'state.db', self.home / 'workspace', self.backend, lambda: self.now)
        original_launch = self.backend.launch
        nested = []
        def launch_with_overlap(plan, cancelled):
            if not nested:
                nested.append(True)
                self.assertEqual(other.kernel.tick(second), State.SUCCEEDED)
            return original_launch(plan, cancelled)
        try:
            with patch.object(self.backend, 'launch', launch_with_overlap):
                self.assertEqual(self.kernel.tick(first), State.SUCCEEDED)
        finally:
            other.kernel.close()
        self.assertEqual(self.backend.started, 2)
        self.assertEqual(self.kernel.task(second)['state'], State.SUCCEEDED)

    def test_desktop_adapter_rejects_missing_or_forged_context(self):
        from zhixia.contracts import Action
        task = self.service.prepare_launch('hello.txt', 'text')
        action = Action(**self.kernel.task(task)['plan'][0])
        with self.assertRaises(PolicyError):
            self.service.adapter.execute(action)
        with self.assertRaises(PolicyError):
            self.service.adapter.execute_claimed(action, ExecutionContext(task, 'not-a-claimed-operation'))
        self.assertEqual(self.backend.started, 0)

    def test_save_menu_uses_bound_uia_even_when_foreground_changes(self):
        plan = NotepadPlan('device', 'version', 'notepad.exe', 'hello.txt', 'text')
        identity = WindowIdentity(123, 456, 'notepad.exe', 'birth')
        routed = []
        foreground = [identity.hwnd]
        save = SimpleNamespace(element_info=SimpleNamespace(name='Save As...', process_id=123),
                               iface_invoke=SimpleNamespace(Invoke=lambda: routed.append(('save', 123))))
        def expand():
            foreground[0] = 999  # Another app steals focus between inspection and action.
            routed.append(('expand', 123))
        menu = SimpleNamespace(element_info=SimpleNamespace(name='File', process_id=123),
            iface_expand_collapse=SimpleNamespace(Expand=expand), descendants=lambda **kwargs: [save])
        bar = SimpleNamespace(children=lambda **kwargs: [menu])
        window = SimpleNamespace(descendants=lambda **kwargs: [bar],
            type_keys=lambda *args, **kwargs: self.fail('global keyboard must never be used'),
            set_focus=lambda: self.fail('focus does not authorize global routing'))
        backend = PywinautoNotepad()
        with patch.object(backend, '_check'), patch.object(backend, '_window'):
            backend._open_save_dialog(plan, identity, window, lambda: False)
        self.assertEqual(foreground[0], 999)
        self.assertEqual(routed, [('expand', 123), ('save', 123)])

    def test_unknown_menu_tree_fails_without_keyboard_fallback(self):
        plan = NotepadPlan('device', 'version', 'notepad.exe', 'hello.txt', 'text')
        identity = WindowIdentity(123, 456, 'notepad.exe', 'birth')
        window = SimpleNamespace(descendants=lambda **kwargs: [],
            type_keys=lambda *args, **kwargs: self.fail('no keyboard fallback'))
        backend = PywinautoNotepad()
        with patch.object(backend, '_check'), patch.object(backend, '_window'):
            with self.assertRaises(DesktopUnavailable):
                backend._open_save_dialog(plan, identity, window, lambda: False)

    def test_two_separate_approvals_and_evidence_marked_synthetic(self):
        launch = self.launch()
        task = self.service.prepare_input(launch)
        self.assertEqual(self.backend.saved, 0)
        plan = self.kernel.task(task)['plan'][0]['arguments']
        self.assertEqual(plan['window']['pid'], 123)
        self.assertEqual(plan['window']['hwnd'], 456)
        self.assertFalse(plan['plan']['overwrite'])
        self.approve(task)
        self.assertEqual(self.kernel.tick(task), State.READY)
        self.assertEqual(self.kernel.tick(task), State.SUCCEEDED)
        receipt = json.loads(self.kernel.db.execute(
            'SELECT receipt FROM operations WHERE task_id=? AND step=1', (task,)).fetchone()[0])
        self.assertTrue(receipt['verified'])
        self.assertFalse(receipt['real_windows_acceptance'])
        self.assertEqual(receipt['uia_receipt']['execution_mode'], 'contract_test')
        self.assertEqual(self.backend.saved, 1)

    def test_non_windows_check_is_blocked_without_importing_uia(self):
        with patch('zhixia.notepad.platform.system', return_value='Linux'):
            with self.assertRaisesRegex(DesktopUnavailable, 'native Windows'):
                PywinautoNotepad().inspect()
        self.assertNotIn('pywinauto', sys.modules)

    def test_cli_no_command_or_check_never_claims_success_on_linux(self):
        if sys.platform == 'win32':
            self.skipTest('specific Linux blocker check')
        for tail in [[], ['check']]:
            result = subprocess.run([sys.executable, '-m', 'zhixia.notepad_cli', *tail],
                                    text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 2)
            evidence = json.loads(result.stdout)
            self.assertEqual(evidence['status'], 'BLOCKED')
            self.assertEqual(evidence['windows_acceptance'], 'NOT_RUN')

    def test_cli_execution_requires_explicit_enable(self):
        result = subprocess.run([sys.executable, '-m', 'zhixia.notepad_cli', 'run', 'task'],
                                text=True, capture_output=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('--enable-uia', result.stderr)

    def test_approval_from_launch_cannot_be_reused_for_input(self):
        launch = self.service.prepare_launch('hello.txt', 'text')
        approval = self.approve(launch)
        self.kernel.tick(launch)
        task = self.service.prepare_input(launch)
        with self.assertRaises(ValueError):
            self.kernel.decide(approval, True)
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        self.assertEqual(self.backend.saved, 0)

    def test_pid_or_text_change_invalidates_input_approval(self):
        task = self.service.prepare_input(self.launch())
        self.approve(task)
        plan = self.kernel.task(task)['plan']
        plan[0]['arguments']['window']['pid'] = 999
        self.kernel.db.execute('UPDATE tasks SET plan=? WHERE id=?', (json.dumps(plan), task))
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        self.assertEqual(self.backend.saved, 0)

    def test_device_change_blocks_before_gui(self):
        task = self.service.prepare_launch('hello.txt', 'text')
        self.approve(task)
        self.backend.device = 'other-device'
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.backend.started, 0)

    def test_no_overwrite_before_launch_or_before_input(self):
        target = self.home / 'workspace/hello.txt'
        target.write_text('existing')
        with self.assertRaises(PolicyError):
            self.service.prepare_launch('hello.txt', 'text')
        target.unlink()
        task = self.service.prepare_input(self.launch())
        self.approve(task)
        target.write_text('race')
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(target.read_text(), 'race')
        self.assertEqual(self.backend.saved, 0)

    def test_unknown_gui_side_effect_not_proven_by_file_alone(self):
        task = self.service.prepare_input(self.launch())
        self.approve(task)
        self.backend.fail_after_save = True
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertTrue((self.home / 'workspace/hello.txt').exists())
        self.assertEqual(self.kernel.reconcile(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.backend.saved, 1)

    def test_missing_or_tampered_independent_evidence_is_not_success(self):
        task = self.service.prepare_input(self.launch())
        self.approve(task)
        self.kernel.tick(task)
        (self.home / 'workspace/hello.txt').write_text('tamper')
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.kernel.reconcile(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.backend.saved, 1)

    def test_cancel_before_input_preserves_launched_window_without_edit(self):
        task = self.service.prepare_input(self.launch())
        self.approve(task)
        self.kernel.cancel(task)
        self.assertEqual(self.kernel.tick(task), State.CANCELLED)
        self.assertEqual(self.backend.saved, 0)
        self.assertEqual(self.backend.started, 1)

    def test_cancel_during_input_becomes_unknown_not_rollback(self):
        task = self.service.prepare_input(self.launch())
        self.approve(task)
        self.backend.cancel_hook = lambda: self.kernel.cancel(task)
        self.assertEqual(self.kernel.tick(task), State.NEEDS_RECONCILIATION)
        self.assertEqual(self.backend.saved, 0)
        self.assertTrue(self.kernel.task(task)['cancel_requested'])

    def test_one_input_task_per_launch_is_transactionally_reserved(self):
        launch = self.launch()
        task = self.service.prepare_input(launch)
        other = NotepadTasks(self.home / 'state.db', self.home / 'workspace', self.backend, lambda: self.now)
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                other.prepare_input(launch)
        finally:
            other.kernel.close()
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM tasks').fetchone()[0], 2)
        self.assertEqual(self.kernel.db.execute('SELECT child_task_id FROM task_children').fetchone()[0], task)

    def test_adapter_database_cannot_be_opened_as_file_executor(self):
        with self.assertRaisesRegex(ValueError, 'another execution adapter'):
            Kernel(self.home / 'state.db', self.home / 'workspace')

    def test_persistent_reopen_keeps_pending_window_approval(self):
        task = self.service.prepare_input(self.launch())
        self.kernel.tick(task)
        self.kernel.close()
        self.service = NotepadTasks(self.home / 'state.db', self.home / 'workspace', self.backend, lambda: self.now)
        self.kernel = self.service.kernel
        self.assertEqual(self.kernel.task(task)['state'], State.WAITING_APPROVAL)
        self.kernel.decide(self.kernel.pending(task)[-1]['id'], True)
        self.assertEqual(self.kernel.tick(task), State.READY)


if __name__ == '__main__':
    unittest.main()
