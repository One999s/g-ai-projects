"""Explicit Windows-only acceptance CLI; check/default never imports pywinauto."""
import argparse
import json
from pathlib import Path
from .notepad import DesktopUnavailable, NotepadTasks, PywinautoNotepad


def main() -> int:
    parser = argparse.ArgumentParser(description='知夏受限 Notepad/UIA 候选验收（真机状态 NOT_RUN）')
    parser.add_argument('--home', type=Path, default=Path('.runtime/notepad'))
    commands = parser.add_subparsers(dest='command')
    commands.add_parser('check', help='只检查平台/元数据，不启动程序或初始化UIA')
    prepare = commands.add_parser('prepare-launch', help='准备仅启动提案，不启动记事本')
    prepare.add_argument('--path', required=True)
    prepare.add_argument('--text', required=True)
    prepare.add_argument('--file-menu', default='File', help='实际UIA树中精确File菜单名')
    prepare.add_argument('--save-as-menu', default='Save As...', help='实际UIA树中精确Save As菜单名')
    prepare.add_argument('--save-dialog-title', choices=['Save As', '另存为'], default='Save As')
    commands.add_parser('prepare-input', help='从启动收据准备绑定PID/HWND的第二阶段').add_argument('task_id')
    for name in ('show', 'cancel', 'reconcile', 'abandon'):
        commands.add_parser(name).add_argument('task_id')
    run = commands.add_parser('run', help='首次产生审批；批准后才执行一个动作')
    run.add_argument('task_id')
    run.add_argument('--enable-uia', action='store_true', required=True,
                     help='明确启用本次已批准动作；不授予其他桌面权限')
    recover = commands.add_parser('recover')
    recover.add_argument('task_id')
    recover.add_argument('--worker-stopped', action='store_true', required=True)
    for name in ('approve', 'deny'):
        commands.add_parser(name).add_argument('approval_id')
    args = parser.parse_args()
    service = None
    try:
        if args.command in {None, 'check'}:
            result = {'status': 'DEPENDENCIES_PRESENT_NOT_GUI_VALIDATED', **PywinautoNotepad().inspect()}
        else:
            service = NotepadTasks(args.home / 'state.sqlite3', args.home / 'workspace')
            kernel = service.kernel
            if args.command == 'prepare-launch':
                result = {'launch_task_id': service.prepare_launch(args.path, args.text, args.file_menu, args.save_as_menu, args.save_dialog_title), 'started': False}
            elif args.command == 'prepare-input':
                result = {'input_task_id': service.prepare_input(args.task_id), 'input_started': False}
            elif args.command in {'approve', 'deny'}:
                kernel.decide(args.approval_id, args.command == 'approve')
                result = {'decision': args.command}
            elif args.command == 'show':
                result = {'task': kernel.task(args.task_id), 'approvals': kernel.pending(args.task_id),
                          'operations': [dict(row) for row in kernel.db.execute(
                              'SELECT * FROM operations WHERE task_id=? ORDER BY step', (args.task_id,))]}
            elif args.command == 'run':
                result = {'state': kernel.tick(args.task_id)}
            else:
                result = {'state': getattr(kernel, args.command)(args.task_id)}
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except DesktopUnavailable as error:
        print(json.dumps({'status': 'BLOCKED', 'reason': str(error), 'windows_acceptance': 'NOT_RUN'}, ensure_ascii=False))
        return 2
    finally:
        if service is not None:
            service.kernel.close()


if __name__ == '__main__':
    raise SystemExit(main())
