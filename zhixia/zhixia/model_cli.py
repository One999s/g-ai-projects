"""Explicit single HTTP planning attempt. API keys are never CLI arguments."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path
from .chat_http import ChatConfig, ProviderError, strict_json
from .kernel import Kernel
from .model_planner import BudgetError, ModelPlanner


def main() -> int:
    parser = argparse.ArgumentParser(description='非流式 Chat Completions JSON-object 候选适配，真实厂商未验')
    parser.add_argument('--config', type=Path, required=True, help='公开配置JSON；只允许API key环境变量名称，不允许密钥值')
    parser.add_argument('--home', type=Path, default=Path('.runtime'))
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('check')
    commands.add_parser('status')
    select = commands.add_parser('select')
    select.add_argument('model', help='显式选择配置allowlist中的模型；不改变已生成计划')
    plan = commands.add_parser('plan')
    plan.add_argument('--path', required=True)
    plan.add_argument('--text', required=True)
    plan.add_argument('--context-task')
    plan.add_argument('--enable-model-http', action='store_true', required=True,
                      help='明确发送本次任务摘要/路径/正文至配置端点；可能产生费用')
    commands.add_parser('cancel').add_argument('call_id')
    recover = commands.add_parser('recover')
    recover.add_argument('call_id')
    recover.add_argument('--worker-stopped', action='store_true', required=True)
    args = parser.parse_args()
    kernel = None
    try:
        if args.config.stat().st_size > 65536:
            raise ValueError('configuration too large')
        config = ChatConfig(**strict_json(args.config.read_text(encoding='utf-8')))
        config.validate()
        if args.command == 'check':
            config.credential()
            result = {'status': 'CONFIGURATION_VALID_NO_REQUEST_SENT', 'endpoint': config.base_url,
                      'models': list(config.allowed_models), 'streaming': False, 'automatic_retries': 0,
                      'real_vendor_validation': 'NOT_RUN'}
        else:
            kernel = Kernel(args.home / 'state.sqlite3', args.home / 'workspace')
            planner = ModelPlanner(kernel, config)
            if args.command == 'status':
                result = planner.status()
            elif args.command == 'select':
                planner.select_model(args.model)
                result = {'selected_model': planner.selected_model(), 'existing_plans_changed': False}
            elif args.command == 'plan':
                def started(call_id):
                    print(json.dumps({'call_id': call_id, 'state': 'request_reserved'}, ensure_ascii=False), flush=True)
                call, task = planner.plan_task(args.path, args.text, context_task_id=args.context_task, on_started=started)
                result = {'call_id': call, 'task_id': task, 'proposal_only': True, 'automatically_approved': False}
            else:
                getattr(planner, args.command)(args.call_id)
                result = planner.status()
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ProviderError, BudgetError) as error:
        print(json.dumps({'status': 'BLOCKED_OR_UNCERTAIN', 'code': str(error), 'automatic_retry': False}, ensure_ascii=False))
        return 2
    except Exception:
        # Never expose raw response, URL/header-bearing exceptions or secret config values.
        print(json.dumps({'status': 'BLOCKED', 'code': 'configuration_or_local_state_error'}, ensure_ascii=False))
        return 2
    finally:
        if kernel is not None:
            kernel.close()


if __name__ == '__main__':
    raise SystemExit(main())
