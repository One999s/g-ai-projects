"""Persistent, fail-closed billing ledger around a single HTTP planning attempt."""
from dataclasses import asdict
from contextlib import nullcontext
import os
import json
import uuid
from .chat_http import ChatConfig, ChatTransport, ProviderError, Usage, contains_secret
from .contracts import Action, State, TaskSummary, canonical, digest


class BudgetError(RuntimeError):
    pass


class ModelPlanner:
    def __init__(self, kernel, config: ChatConfig):
        config.validate()
        # Validate credential references before storing public configuration fingerprints.
        if config.api_key_env and os.environ.get(config.api_key_env):
            config.credential()
        self.kernel, self.config = kernel, config
        self.transport = ChatTransport(config)
        kernel.db.executescript('''
            CREATE TABLE IF NOT EXISTS model_settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS model_calls(
                id TEXT PRIMARY KEY, model TEXT NOT NULL, task_id TEXT NOT NULL UNIQUE,
                summary_json TEXT NOT NULL, state TEXT NOT NULL, financial_state TEXT NOT NULL,
                reserved_microusd INTEGER NOT NULL, charged_microusd INTEGER,
                prompt_tokens INTEGER, completion_tokens INTEGER,
                cancel_requested INTEGER NOT NULL DEFAULT 0, error_code TEXT,
                input_rate INTEGER NOT NULL, output_rate INTEGER NOT NULL);
        ''')
        with kernel.transaction():
            fingerprint = digest(asdict(config))
            row = kernel.db.execute("SELECT value FROM model_settings WHERE key='config_fingerprint'").fetchone()
            if row and row[0] != fingerprint:
                raise BudgetError('configuration_changed_requires_explicit_migration')
            kernel.db.execute("INSERT OR IGNORE INTO model_settings VALUES('config_fingerprint',?)", (fingerprint,))
            kernel.db.execute("INSERT OR IGNORE INTO model_settings VALUES('selected_model',?)", (config.default_model,))

    def selected_model(self) -> str:
        return self.kernel.db.execute("SELECT value FROM model_settings WHERE key='selected_model'").fetchone()[0]

    def select_model(self, model: str):
        if model not in self.config.allowed_models:
            raise ProviderError('model_not_allowed')
        key = os.environ.get(self.config.api_key_env) if self.config.api_key_env else None
        if key:
            self.config.credential()
        if key and key in model:
            raise ProviderError('credential_in_model_identifier')
        with self.kernel.transaction():
            previous = self.selected_model()
            self.kernel.db.execute("UPDATE model_settings SET value=? WHERE key='selected_model'", (model,))
            self.kernel.event('__model__', 'model_selected', {'previous': previous, 'selected': model,
                'affects': 'future_planning_only', 'existing_approved_plans_changed': False})

    def status(self) -> dict:
        calls = [dict(row) for row in self.kernel.db.execute('SELECT * FROM model_calls ORDER BY rowid')]
        committed = sum(row['charged_microusd'] if row['charged_microusd'] is not None
                        else row['reserved_microusd'] for row in calls)
        return {'selected_model': self.selected_model(), 'budget_microusd': self.config.total_budget_microusd,
                'accounted_or_reserved_microusd': committed,
                'remaining_microusd': self.config.total_budget_microusd - committed,
                'financial_uncertainty': any(row['financial_state'] == 'unknown' for row in calls),
                'reservation_policy_violation': any(row['financial_state'] == 'usage_exceeds_reservation' for row in calls),
                'calls': calls}

    def cancel(self, call_id: str):
        with self.kernel.transaction():
            row = self.kernel.db.execute('SELECT state FROM model_calls WHERE id=?', (call_id,)).fetchone()
            if not row:
                raise KeyError('model call not found')
            if row['state'] == 'running':
                self.kernel.db.execute('UPDATE model_calls SET cancel_requested=1 WHERE id=?', (call_id,))
                self.kernel.event('__model__', 'model_cancel_requested', {'call_id': call_id})

    def recover(self, call_id: str):
        """Only after the old worker stopped. No retry or fabricated zero-cost settlement."""
        with self.kernel.transaction():
            row = self.kernel.db.execute('SELECT * FROM model_calls WHERE id=?', (call_id,)).fetchone()
            if not row:
                raise KeyError('model call not found')
            if row['state'] == 'running':
                task_exists = self.kernel.db.execute('SELECT 1 FROM tasks WHERE id=?', (row['task_id'],)).fetchone() is not None
                self.kernel.db.execute("UPDATE model_calls SET state=?,financial_state='unknown',error_code='worker_interrupted' WHERE id=?",
                                       ('plan_persisted_cost_unknown' if task_exists else 'unknown', call_id))
                self.kernel.event('__model__', 'model_interrupted', {'call_id': call_id, 'plan_exists': task_exists,
                                                                  'automatic_retry': False})

    def _cancelled(self, call_id: str) -> bool:
        row = self.kernel.db.execute('SELECT cancel_requested,state FROM model_calls WHERE id=?', (call_id,)).fetchone()
        return bool(row['cancel_requested'] or row['state'] != 'running')

    def plan_task(self, target: str, text: str, *, context_task_id: str | None = None,
                  on_started=lambda call_id: None) -> tuple[str, str]:
        # Fail before network/cost for paths or actions outside the existing kernel policy.
        for tool in ['file.write', 'file.verify']:
            self.kernel.files.validate(Action(tool, {'path': target, 'text': text}))
        planned_task_id = uuid.uuid4().hex
        summary = self.kernel.summary(context_task_id) if context_task_id else TaskSummary(
            planned_task_id, '在工作区创建文本并逐字节验证', State.READY, 0, 0)
        key = self.config.credential()
        if contains_secret({'summary': asdict(summary), 'path': target, 'text': text}, key):
            raise ProviderError('credential_in_exported_state')
        call_id = uuid.uuid4().hex
        with self.kernel.transaction():
            fingerprint = self.kernel.db.execute("SELECT value FROM model_settings WHERE key='config_fingerprint'").fetchone()[0]
            if fingerprint != digest(asdict(self.config)):
                raise BudgetError('configuration_changed_requires_explicit_migration')
            status = self.status()
            if status['financial_uncertainty']:
                raise BudgetError('unresolved_cost_blocks_new_model_calls')
            if status['reservation_policy_violation']:
                raise BudgetError('reservation_policy_violation_blocks_calls')
            if len(status['calls']) >= self.config.max_calls:
                raise BudgetError('call_count_limit_exhausted')
            model = self.selected_model()
            price = self.config.allowed_models[model]
            reservation = (self.config.input_token_reservation * price['input_microusd_per_token']
                           + self.config.max_completion_tokens * price['output_microusd_per_token'])
            if reservation > status['remaining_microusd']:
                raise BudgetError('budget_exhausted')
            self.kernel.db.execute('''INSERT INTO model_calls(id,model,task_id,summary_json,state,financial_state,
                reserved_microusd,input_rate,output_rate) VALUES(?,?,?,?,'running','reserved',?,?,?)''',
                (call_id, model, planned_task_id, canonical(asdict(summary)), reservation,
                 price['input_microusd_per_token'], price['output_microusd_per_token']))
            self.kernel.event('__model__', 'model_call_intent', {'call_id': call_id, 'model': model,
                'reservation_microusd': reservation, 'summary_hash': digest(asdict(summary))})
        try:
            on_started(call_id)
            result = self.transport.complete(model, summary, target, text, lambda: self._cancelled(call_id))
            with self.kernel.transaction():
                if self._cancelled(call_id):
                    raise ProviderError('cancelled_after_response', sent=True, usage=result.usage)
                # Publish proposal and settle usage atomically. Approval is never granted here.
                self.kernel.create_actions('在工作区创建文本并逐字节验证', 'compatible-chat:' + model,
                                           result.actions, planned_task_id, within_transaction=True)
                self._settle(call_id, 'succeeded' if result.usage else 'succeeded_usage_unknown', result.usage,
                             sent=True, within_transaction=True)
            return call_id, planned_task_id
        except ProviderError as error:
            self._settle(call_id, 'rejected' if error.usage else ('unknown' if error.sent else 'failed_before_send'),
                         error.usage, sent=error.sent, error_code=error.code)
            raise
        except Exception:
            # The request may have completed; no automatic retry, even if persistence later failed.
            self._settle(call_id, 'unknown', None, sent=True, error_code='planning_interrupted')
            raise ProviderError('planning_interrupted', sent=True) from None

    def _settle(self, call_id: str, state: str, usage: Usage | None, *, sent: bool, error_code: str | None = None, within_transaction: bool = False):
        if within_transaction and not self.kernel.db.in_transaction:
            raise BudgetError("caller_transaction_required")
        with (nullcontext() if within_transaction else self.kernel.transaction()):
            row = self.kernel.db.execute('SELECT * FROM model_calls WHERE id=?', (call_id,)).fetchone()
            if row['state'] != 'running':
                raise BudgetError('model_call_no_longer_settleable')
            charged = (usage.prompt_tokens * row['input_rate'] + usage.completion_tokens * row['output_rate']) if usage else (None if sent else 0)
            financial_state = 'usage_reported' if usage else ('unknown' if sent else 'not_sent')
            if usage and charged > row['reserved_microusd']:
                financial_state = 'usage_exceeds_reservation'
            self.kernel.db.execute('''UPDATE model_calls SET state=?,financial_state=?,charged_microusd=?,
                prompt_tokens=?,completion_tokens=?,error_code=? WHERE id=?''',
                (state, financial_state, charged, usage.prompt_tokens if usage else None,
                 usage.completion_tokens if usage else None, error_code, call_id))
            self.kernel.event('__model__', 'model_call_result', {'call_id': call_id, 'state': state,
                'financial_state': financial_state, 'charged_at_configured_quote_microusd': charged,
                'error_code': error_code})
