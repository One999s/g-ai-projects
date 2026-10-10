"""Local operator CLI. Approval is an OS-user trust boundary, not remote authentication."""
import argparse
import json
from pathlib import Path
from .kernel import Kernel
from .providers import FakeProvider, Router


def main():
    parser = argparse.ArgumentParser(description="知夏 Phase0（仅 fake provider + 本地受限文件工具）")
    parser.add_argument("--home", type=Path, default=Path(".runtime"))
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("--path", required=True)
    create.add_argument("--text", required=True)
    create.add_argument("--provider", choices=["fake-a", "fake-b"], default="fake-a")
    for name in ["show", "tick", "cancel", "reconcile", "abandon"]:
        commands.add_parser(name).add_argument("task_id")
    recover = commands.add_parser("recover")
    recover.add_argument("task_id")
    recover.add_argument("--worker-stopped", action="store_true", required=True,
                         help="确认旧执行进程已停止后才可恢复")
    approve = commands.add_parser("approve")
    approve.add_argument("approval_id")
    deny = commands.add_parser("deny")
    deny.add_argument("approval_id")
    args = parser.parse_args()
    kernel = Kernel(args.home / "state.sqlite3", args.home / "workspace")
    try:
        if args.command == "create":
            result = {"task_id": kernel.create(args.path, args.text, Router([FakeProvider(args.provider)])),
                      "provider_mode": "deterministic_fake_no_model_call"}
        elif args.command in {"approve", "deny"}:
            kernel.decide(args.approval_id, args.command == "approve")
            result = {"decision": args.command}
        elif args.command == "show":
            result = {"task": kernel.task(args.task_id), "approvals": kernel.pending(args.task_id)}
        else:
            result = {"state": getattr(kernel, args.command)(args.task_id)}
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        kernel.close()


if __name__ == "__main__":
    main()
