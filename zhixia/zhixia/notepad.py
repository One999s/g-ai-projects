"""Opt-in UIA execution. Importing this module never opens an app or device."""
from dataclasses import dataclass, asdict
import hashlib
import importlib.metadata
import os
from pathlib import Path
import platform
import time
from typing import Callable, Protocol
from .tools import PolicyError, WorkspaceFiles


class DesktopUnavailable(RuntimeError):
    pass


class ExecutionStopped(RuntimeError):
    pass


@dataclass(frozen=True)
class WindowIdentity:
    pid: int
    hwnd: int
    executable: str
    process_created_at: str


@dataclass(frozen=True)
class NotepadPlan:
    device_id: str
    adapter_version: str
    executable: str
    relative_path: str
    text: str
    overwrite: bool = False
    encoding: str = 'utf-8'
    file_menu_name: str = 'File'
    save_as_menu_name: str = 'Save As...'
    save_dialog_title: str = 'Save As'


class NotepadBackend(Protocol):
    """The injected test backend is NOT evidence of Windows GUI functionality."""
    def inspect(self) -> dict: ...
    def launch(self, plan: NotepadPlan, cancelled: Callable[[], bool]) -> WindowIdentity: ...
    def input_and_save(self, plan: NotepadPlan, window: WindowIdentity, target: Path,
                       cancelled: Callable[[], bool]) -> None: ...


class FileEvidence:
    """Independent read-only verifier; no call to an execution backend."""
    def __init__(self, workspace: Path):
        self.files = WorkspaceFiles(workspace)

    def target(self, plan: NotepadPlan) -> Path:
        from .contracts import Action
        if plan.file_menu_name not in {'File', '文件', '文件(F)'} or plan.save_as_menu_name not in {
                'Save As...', 'Save as', 'Save As', '另存为', '另存为(A)...', '另存为(A)'}:
            raise PolicyError('only explicitly supported exact File/Save As labels are allowed')
        if plan.save_dialog_title not in {'Save As', '另存为'}:
            raise PolicyError('unsupported exact Save As dialog title')
        if plan.overwrite or plan.encoding != 'utf-8':
            raise PolicyError('only no-overwrite UTF-8 plans are supported')
        return self.files.validate(Action('file.verify', {'path': plan.relative_path, 'text': plan.text}))

    def verify(self, plan: NotepadPlan) -> dict | None:
        path = self.target(plan)
        if not path.is_file() or path.stat().st_size > self.files.MAX_BYTES:
            return None
        data = path.read_bytes()
        if data != plan.text.encode('utf-8'):
            return None
        return {'path': str(path), 'sha256': hashlib.sha256(data).hexdigest(),
                'bytes': len(data), 'encoding': 'utf-8', 'verified': True,
                'verification': 'independent_disk_bytes', 'observed_at': time.time()}


class PywinautoNotepad:
    VERSION = 'pywinauto-0.6.8/notepad-classic-v1'

    @staticmethod
    def _windows_directory() -> Path:
        import ctypes
        buffer = ctypes.create_unicode_buffer(32768)
        count = ctypes.windll.kernel32.GetWindowsDirectoryW(buffer, len(buffer))
        if not count or count >= len(buffer):
            raise DesktopUnavailable('Windows directory cannot be verified')
        return Path(buffer.value)

    def inspect(self) -> dict:
        if platform.system() != 'Windows':
            raise DesktopUnavailable('BLOCKED: native Windows interactive desktop is required')
        import struct
        import sys
        if (platform.python_implementation() != 'CPython' or sys.version_info[:2] != (3, 11)
                or struct.calcsize('P') != 8):
            raise DesktopUnavailable('BLOCKED: candidate dependency lock requires CPython 3.11 x64')
        try:
            version = importlib.metadata.version('pywinauto')
        except importlib.metadata.PackageNotFoundError:
            raise DesktopUnavailable('BLOCKED: audited optional pywinauto dependency is not installed') from None
        if version != '0.6.8':
            raise DesktopUnavailable('BLOCKED: pywinauto version must be exactly 0.6.8')
        for package, expected in {'six': '1.16.0', 'comtypes': '1.2.0', 'pywin32': '306'}.items():
            try:
                if importlib.metadata.version(package) != expected:
                    raise DesktopUnavailable('BLOCKED: optional dependency version differs from audited candidate lock')
            except importlib.metadata.PackageNotFoundError:
                raise DesktopUnavailable('BLOCKED: optional dependency is missing') from None
        import ctypes
        # No administrator rights or UIAccess are requested or accepted for this adapter.
        if ctypes.windll.shell32.IsUserAnAdmin():
            raise DesktopUnavailable('BLOCKED: restart as a non-elevated ordinary user')
        executable = self._windows_directory() / 'System32' / 'notepad.exe'
        if not executable.is_file() or executable.is_symlink():
            raise DesktopUnavailable('BLOCKED: verified system Notepad executable is unavailable')
        return {'device_id': hashlib.sha256(platform.node().encode('utf-8')).hexdigest(),
                'executable': str(executable.resolve()), 'adapter': self.VERSION,
                'platform': platform.platform(), 'gui_validation': 'NOT_RUN'}

    def _check(self, plan: NotepadPlan, cancelled: Callable[[], bool]):
        if cancelled():
            raise ExecutionStopped('cancel requested; external effects may remain')
        observed = self.inspect()
        if (observed['device_id'] != plan.device_id or observed['executable'] != plan.executable
                or observed['adapter'] != plan.adapter_version):
            raise DesktopUnavailable('device or executable changed since approval')

    @staticmethod
    def _process_info(pid: int) -> tuple[str, str]:
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                      wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            raise DesktopUnavailable('process identity cannot be read')
        try:
            buffer = ctypes.create_unicode_buffer(32768)
            size = wintypes.DWORD(len(buffer))
            if not kernel.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
                raise DesktopUnavailable('process image cannot be read')
            created, exited, kernel_time, user_time = [wintypes.FILETIME() for _ in range(4)]
            kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
            if not kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited),
                                          ctypes.byref(kernel_time), ctypes.byref(user_time)):
                raise DesktopUnavailable('process creation time cannot be verified')
            birth = str((created.dwHighDateTime << 32) | created.dwLowDateTime)
            return str(Path(buffer.value).resolve()), birth
        finally:
            kernel.CloseHandle(handle)

    def _window(self, plan: NotepadPlan, identity: WindowIdentity, *, require_enabled: bool = True):
        from pywinauto import Application
        if identity.pid <= 0 or identity.hwnd <= 0 or identity.executable != plan.executable:
            raise DesktopUnavailable('invalid bound window identity')
        actual_image, birth = self._process_info(identity.pid)
        if birth != identity.process_created_at:
            raise DesktopUnavailable("process identity was recycled")
        if os.path.normcase(actual_image) != os.path.normcase(plan.executable):
            raise DesktopUnavailable('process executable differs; packaged/rehosted Notepad is not supported yet')
        app = Application(backend='uia').connect(process=identity.pid, timeout=5)
        window = app.window(handle=identity.hwnd).wrapper_object()
        if window.element_info.process_id != identity.pid or window.handle != identity.hwnd:
            raise DesktopUnavailable('window/PID changed')
        if not window.is_visible() or (require_enabled and not window.is_enabled()):
            raise DesktopUnavailable('bound window is not interactable')
        return app, window

    def launch(self, plan: NotepadPlan, cancelled: Callable[[], bool]) -> WindowIdentity:
        self._check(plan, cancelled)
        from pywinauto import Application
        app = Application(backend='uia').start('"' + plan.executable + '"', timeout=10)
        window = app.top_window()
        window.wait('visible enabled', timeout=10)
        wrapper = window.wrapper_object()
        image, birth = self._process_info(app.process)
        identity = WindowIdentity(app.process, wrapper.handle, image, birth)
        self._check(plan, cancelled)
        self._window(plan, identity)  # Reject launchers that changed process / executable.
        return identity

    def _open_save_dialog(self, plan: NotepadPlan, identity: WindowIdentity, window,
                          cancelled: Callable[[], bool]) -> None:
        # Direct UIA patterns only. Never global keyboard/mouse, even after a focus check.
        self._check(plan, cancelled)
        self._window(plan, identity)
        menus = [item for bar in window.descendants(control_type='MenuBar')
                 for item in bar.children(control_type='MenuItem')
                 if item.element_info.name == plan.file_menu_name
                 and item.element_info.process_id == identity.pid]
        if len(menus) != 1:
            raise DesktopUnavailable('approved File menu is not uniquely identifiable')
        menu = menus[0]
        menu.iface_expand_collapse.Expand()
        self._check(plan, cancelled)
        self._window(plan, identity)
        save_items = [item for item in menu.descendants(control_type='MenuItem')
                      if item.element_info.name == plan.save_as_menu_name
                      and item.element_info.process_id == identity.pid]
        if len(save_items) != 1:
            raise DesktopUnavailable('approved Save As menu is not uniquely identifiable')
        save_items[0].iface_invoke.Invoke()

    def input_and_save(self, plan: NotepadPlan, identity: WindowIdentity, target: Path,
                       cancelled: Callable[[], bool]) -> None:
        self._check(plan, cancelled)
        app, window = self._window(plan, identity)
        if target.exists() or not target.parent.is_dir():
            raise PolicyError('save target must be new and its parent must already exist')
        # No title guessing, blind typing, clipboard, tab selection or vision fallback.
        if window.descendants(control_type='Tab'):
            raise DesktopUnavailable('tabbed Notepad UI requires a separately validated adapter')
        editors = [item for item in window.descendants(control_type='Edit')
                   if item.is_visible() and item.is_enabled()]
        if len(editors) != 1:
            raise DesktopUnavailable('exactly one supported UIA Edit control is required')
        editor = editors[0]
        if editor.iface_value.CurrentValue:
            raise PolicyError('editor is not empty; existing document will not be modified')
        self._check(plan, cancelled)
        self._window(plan, identity)
        if editor.iface_value.CurrentIsReadOnly:
            raise DesktopUnavailable("editor ValuePattern is read-only")
        editor.iface_value.SetValue(plan.text)
        if editor.iface_value.CurrentValue != plan.text:
            raise DesktopUnavailable('UIA input verification failed')
        self._check(plan, cancelled)
        self._window(plan, identity)
        self._open_save_dialog(plan, identity, window, cancelled)
        # Classic native Save As dialog only; unknown UI/control trees fail closed.
        dialog = app.window(class_name='#32770', title=plan.save_dialog_title)
        dialog.wait('visible enabled', timeout=10)
        filename = dialog.child_window(auto_id='1001', control_type='Edit').wrapper_object()
        save = dialog.child_window(auto_id='1', control_type='Button').wrapper_object()
        self._check(plan, cancelled)
        self._window(plan, identity, require_enabled=False)
        dialog_window = dialog.wrapper_object()
        if dialog_window.element_info.process_id != identity.pid:
            raise DesktopUnavailable('save dialog process differs')
        import ctypes
        from ctypes import wintypes
        ctypes.windll.user32.GetWindow.argtypes = [wintypes.HWND, wintypes.UINT]
        ctypes.windll.user32.GetWindow.restype = wintypes.HWND
        if ctypes.windll.user32.GetWindow(dialog_window.handle, 4) != identity.hwnd:
            raise DesktopUnavailable('Save As dialog is not owned by the approved window')
        if target.exists():
            raise PolicyError('target appeared before save; overwrite denied')
        if filename.iface_value.CurrentIsReadOnly:
            raise DesktopUnavailable("save filename ValuePattern is read-only")
        filename.iface_value.SetValue(str(target))
        if filename.iface_value.CurrentValue != str(target):
            raise DesktopUnavailable('save path control verification failed')
        self._check(plan, cancelled)
        save.invoke()
        # Do not accept any overwrite, extension, elevation or unexpected modal prompt.
        dialog.wait_not('exists', timeout=10)
        self._check(plan, cancelled)
        self._window(plan, identity)  # Still-modal overwrite/error prompts keep the owner disabled.


class NotepadAdapter:
    """Reuses Kernel's existing task/approval/operation state machine."""
    adapter_id = 'notepad-uia'
    policy_version = 'notepad-classic-v1'

    def __init__(self, workspace: Path, backend: NotepadBackend | None = None):
        self.evidence = FileEvidence(workspace)
        self.workspace = self.evidence.files.workspace
        self.backend = backend if backend is not None else PywinautoNotepad()
        self.kernel = None  # Bound once by NotepadTasks; CLI cannot inject a backend.

    @staticmethod
    def requires_approval(action) -> bool:
        return action.tool != 'notepad.verify'

    def validate(self, action):
        if action.tool not in {'notepad.launch', 'notepad.input_save', 'notepad.verify'}:
            raise PolicyError('only bounded Notepad actions are supported by this adapter')
        expected = {'plan'} if action.tool == 'notepad.launch' else {'plan', 'window', 'launch_task_id'}
        if action.tool == 'notepad.verify':
            expected = expected | {'input_task_id'}
        if set(action.arguments) != expected:
            raise PolicyError('unexpected Notepad action arguments')
        try:
            plan = NotepadPlan(**action.arguments['plan'])
            if not isinstance(plan.device_id, str) or not isinstance(plan.executable, str):
                raise ValueError('invalid identity')
            if action.tool != 'notepad.launch':
                identity = WindowIdentity(**action.arguments['window'])
                if (type(identity.pid) is not int or type(identity.hwnd) is not int
                        or identity.pid <= 0 or identity.hwnd <= 0 or identity.executable != plan.executable
                        or not isinstance(identity.process_created_at, str) or not identity.process_created_at):
                    raise ValueError('invalid window')
        except (TypeError, ValueError) as error:
            raise PolicyError('invalid Notepad plan or window identity') from error
        return self.evidence.target(plan)

    def _cancelled(self, task_id: str) -> bool:
        return bool(self.kernel.task(task_id)['cancel_requested'])

    def _check_context(self, action, context) -> str:
        from .contracts import digest
        row = self.kernel.db.execute(
            "SELECT * FROM operations WHERE id=? AND task_id=? AND status='running'",
            (context.operation_id, context.task_id)).fetchone()
        if (not row or row['arguments_hash'] != digest(action.to_dict())
                or row['policy_version'] != self.policy_version
                or self.kernel.task(context.task_id)['state'] != 'running'):
            raise PolicyError('matching durable claimed operation context is required')
        return context.task_id

    def _launch_receipt(self, action) -> dict:
        import json
        task = self.kernel.task(action.arguments['launch_task_id'])
        row = self.kernel.db.execute(
            "SELECT receipt FROM operations WHERE task_id=? AND step=0 AND status='succeeded'",
            (task['id'],)).fetchone()
        if task['state'] != 'succeeded' or not row:
            raise PolicyError('a completed approved launch is required')
        receipt = json.loads(row['receipt'])
        if (receipt.get('window') != action.arguments['window']
                or task['plan'][0]['arguments']['plan'] != action.arguments['plan']):
            raise PolicyError('input plan differs from its launch receipt')
        return receipt

    def execute(self, action) -> dict:
        raise PolicyError("Notepad execution requires an explicit claimed task/operation context")

    def execute_claimed(self, action, context) -> dict:
        task_id = self._check_context(action, context)
        target = self.validate(action)
        plan = NotepadPlan(**action.arguments['plan'])
        if action.tool == 'notepad.verify':
            return self._verify(action)
        cancelled = lambda: self._cancelled(task_id)
        observed = self.backend.inspect()
        if (observed['device_id'] != plan.device_id or observed['executable'] != plan.executable
                or observed['adapter'] != plan.adapter_version):
            raise DesktopUnavailable('device/executable differs from approved proposal')
        # Test implementations must explicitly declare their evidence non-real.
        mode = 'real_uia' if isinstance(self.backend, PywinautoNotepad) else 'contract_test'
        if action.tool == 'notepad.launch':
            if target.exists():
                raise PolicyError('target exists; launch cancelled before opening Notepad')
            identity = self.backend.launch(plan, cancelled)
            return {'window': asdict(identity), 'device_id': plan.device_id,
                    'adapter': observed['adapter'], 'execution_mode': mode,
                    'ui_call': 'launch', 'verified': False}
        self._launch_receipt(action)
        identity = WindowIdentity(**action.arguments['window'])
        if target.exists():
            raise PolicyError('target exists; overwrite is forbidden')
        self.backend.input_and_save(plan, identity, target, cancelled)
        return {'window': asdict(identity), 'device_id': plan.device_id,
                'adapter': observed['adapter'], 'execution_mode': mode,
                'ui_call': 'uia_input_and_save', 'verified': False}

    def _verify(self, action) -> dict:
        import json
        from .contracts import Action, digest
        args = action.arguments
        plan = NotepadPlan(**args['plan'])
        observed = self.backend.inspect()
        if (observed['device_id'] != plan.device_id or observed['executable'] != plan.executable
                or observed['adapter'] != plan.adapter_version):
            raise DesktopUnavailable('verification device or adapter differs from approval')
        input_task = self.kernel.task(args['input_task_id'])
        input_action = Action('notepad.input_save', {key: args[key] for key in ('plan', 'window', 'launch_task_id')})
        row = self.kernel.db.execute(
            "SELECT * FROM operations WHERE task_id=? AND step=0 AND status='succeeded'",
            (input_task['id'],)).fetchone()
        if not row or row['arguments_hash'] != digest(input_action.to_dict()):
            raise PolicyError('durable matching UIA execution receipt is required')
        ui_receipt = json.loads(row['receipt'])
        if ui_receipt.get('ui_call') != 'uia_input_and_save' or ui_receipt.get('window') != args['window']:
            raise PolicyError('UIA receipt does not prove the approved window path')
        evidence = self.evidence.verify(NotepadPlan(**args['plan']))
        if evidence is None:
            raise PolicyError('saved UTF-8 bytes do not match the approved text')
        return {**evidence, 'uia_receipt': ui_receipt, 'execution_operation_id': row['id'],
                'real_windows_acceptance': ui_receipt.get('execution_mode') == 'real_uia'}

    def reconcile(self, action) -> dict | None:
        self.validate(action)
        # File existence alone cannot prove that a lost GUI operation followed the approved UIA path.
        if action.tool != 'notepad.verify':
            return None
        try:
            return self._verify(action)
        except PolicyError:
            return None


class NotepadTasks:
    def __init__(self, database: Path, workspace: Path, backend: NotepadBackend | None = None, clock=time.time):
        from .kernel import Kernel
        self.adapter = NotepadAdapter(workspace, backend)
        self.kernel = Kernel(database, workspace, clock, adapter=self.adapter)
        self.adapter.kernel = self.kernel

    def prepare_launch(self, relative_path: str, text: str, file_menu_name: str = 'File',
                       save_as_menu_name: str = 'Save As...', save_dialog_title: str = 'Save As') -> str:
        from .contracts import Action
        observed = self.adapter.backend.inspect()  # No pywinauto import, process start or UI action.
        plan = NotepadPlan(observed['device_id'], observed['adapter'], observed['executable'], relative_path, text,
                           file_menu_name=file_menu_name, save_as_menu_name=save_as_menu_name,
                           save_dialog_title=save_dialog_title)
        target = self.adapter.evidence.target(plan)
        if target.exists() or not target.parent.is_dir():
            raise PolicyError('target must be new; create the intended parent directory manually first')
        return self.kernel.create_actions('仅启动记事本，等待绑定窗口的第二次审批', 'native-uia',
                                          [Action('notepad.launch', {'plan': asdict(plan)})])

    def prepare_input(self, launch_task_id: str) -> str:
        import json
        import uuid
        from .contracts import Action
        launch = self.kernel.task(launch_task_id)
        if launch['state'] != 'succeeded' or launch['plan'][0]['tool'] != 'notepad.launch':
            raise PolicyError('completed Notepad launch required')
        row = self.kernel.db.execute("SELECT receipt FROM operations WHERE task_id=? AND step=0 AND status='succeeded'",
                                     (launch_task_id,)).fetchone()
        receipt = json.loads(row['receipt'])
        task_id = uuid.uuid4().hex
        args = {'plan': launch['plan'][0]['arguments']['plan'], 'window': receipt['window'],
                'launch_task_id': launch_task_id}
        return self.kernel.create_actions('通过已批准记事本窗口输入保存并独立验证', 'native-uia',
                [Action('notepad.input_save', args),
                 Action('notepad.verify', {**args, 'input_task_id': task_id})], task_id, exclusive_parent=launch_task_id)
