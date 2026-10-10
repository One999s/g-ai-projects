"""Real loopback HTTP fixtures only; no vendor endpoint or billable model call."""
from dataclasses import asdict, replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from zhixia.chat_http import ChatConfig, ChatTransport, ProviderError
from zhixia.contracts import State, TaskSummary
from zhixia.kernel import Kernel
from zhixia.model_planner import BudgetError, ModelPlanner
from zhixia.tools import PolicyError

TEST_KEY = 'fixture-not-a-real-user-secret'


class Fixture:
    def __init__(self):
        self.requests = []
        self.status = 200
        self.delay = 0
        self.drip_headers = False
        self.mutate = lambda payload: payload
        self.raw = None
        self.content_type = 'application/json'
        self.after_request = lambda: None
        fixture = self
        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def log_message(self, *args):
                pass
            def do_POST(self):
                self.close_connection = True
                data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                fixture.requests.append({'path': self.path, 'body': data, 'authorization': self.headers.get('Authorization')})
                fixture.after_request()
                if fixture.drip_headers:
                    try:
                        for byte in b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n':
                            self.connection.sendall(bytes([byte]))
                            time.sleep(0.05)
                    except OSError:
                        pass
                    return
                time.sleep(fixture.delay)
                actions = json.loads(data['messages'][1]['content'])['required_actions']
                payload = {'model': data['model'], 'choices': [{'finish_reason': 'stop', 'message': {
                    'role': 'assistant', 'content': json.dumps({'actions': actions}),
                    'reasoning_content': 'hidden-fixture-marker-must-not-be-persisted'}}],
                    'usage': {'prompt_tokens': 100, 'completion_tokens': 30, 'total_tokens': 130}}
                raw = fixture.raw if fixture.raw is not None else json.dumps(fixture.mutate(payload)).encode()
                try:
                    self.send_response(fixture.status)
                    self.send_header('Connection', 'close')
                    self.send_header('Content-Type', fixture.content_type)
                    self.send_header('Content-Length', str(len(raw)))
                    if 300 <= fixture.status < 400:
                        self.send_header('Location', 'http://127.0.0.1:1/never-follow')
                    self.end_headers()
                    self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError):
                    pass
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': 0.01}, daemon=True)
        self.thread.start()

    @property
    def endpoint(self):
        return f'http://127.0.0.1:{self.server.server_port}/v1'

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class ChatTests(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'ZX_FIXTURE_KEY': TEST_KEY})
        self.env.start()
        self.config = ChatConfig(self.fixture.endpoint, 'ZX_FIXTURE_KEY', {
            'fixture-a': {'input_microusd_per_token': 1, 'output_microusd_per_token': 2},
            'fixture-b': {'input_microusd_per_token': 2, 'output_microusd_per_token': 3}},
            'fixture-a', 100000, allow_loopback_http=True, timeout_seconds=1, max_calls=50)
        self.kernel = Kernel(self.home / 'state.db', self.home / 'workspace')
        self.planner = ModelPlanner(self.kernel, self.config)
        self.summary = TaskSummary('visible-task', 'explicit public goal', 'ready', 0, 0)

    def tearDown(self):
        self.kernel.close()
        self.fixture.close()
        self.env.stop()
        self.temp.cleanup()

    def direct(self, config=None, cancelled=lambda: False):
        return ChatTransport(config or self.config).complete('fixture-a', self.summary, 'note.txt', '你好', cancelled)

    def test_concurrent_reservations_cannot_overspend(self):
        config = replace(self.config, total_budget_microusd=7000)
        first_kernel = Kernel(self.home / 'shared.db', self.home / 'shared')
        other_kernel = Kernel(self.home / 'shared.db', self.home / 'shared')
        try:
            first = ModelPlanner(first_kernel, config)
            other = ModelPlanner(other_kernel, config)
            def overlap(call):
                with self.assertRaisesRegex(BudgetError, 'budget_exhausted'):
                    other.plan_task('other.txt', 'other')
            first.plan_task('first.txt', 'first', on_started=overlap)
            self.assertEqual(len(self.fixture.requests), 1)
            self.assertEqual(first.status()['accounted_or_reserved_microusd'], 160)
        finally:
            first_kernel.close()
            other_kernel.close()

    def test_proposal_publish_and_usage_settlement_are_atomic(self):
        original = self.kernel.create_actions
        def fail_after_insert(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError('simulated persistence failure')
        with patch.object(self.kernel, 'create_actions', fail_after_insert):
            with self.assertRaisesRegex(ProviderError, 'planning_interrupted'):
                self.planner.plan_task('note.txt', 'text')
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM tasks').fetchone()[0], 0)
        self.assertEqual(self.planner.status()['calls'][0]['state'], 'unknown')
        self.assertEqual(self.planner.status()['accounted_or_reserved_microusd'], 6144)
        self.assertEqual(len(self.fixture.requests), 1)

    def test_cancel_before_atomic_publish_retains_known_usage_without_plan(self):
        original = self.planner.transport.complete
        call_ids = []
        def complete_then_cancel(*args, **kwargs):
            result = original(*args, **kwargs)
            self.planner.cancel(call_ids[0])
            return result
        with patch.object(self.planner.transport, 'complete', complete_then_cancel):
            with self.assertRaisesRegex(ProviderError, 'cancelled_after_response'):
                self.planner.plan_task('note.txt', 'text', on_started=call_ids.append)
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM tasks').fetchone()[0], 0)
        self.assertEqual(self.planner.status()['accounted_or_reserved_microusd'], 160)

    def test_json_escaped_secret_is_rejected_before_persistence(self):
        secret = 'synthetic-key-with-"-quote'
        with patch.dict(os.environ, {'ZX_FIXTURE_KEY': secret}):
            with self.assertRaisesRegex(ProviderError, 'credential_in_exported_state'):
                self.planner.plan_task('note.txt', secret)
        self.assertEqual(self.fixture.requests, [])
        self.assertNotIn(secret, '\n'.join(self.kernel.db.iterdump()))

    def test_cli_requires_explicit_network_flag_and_runs_fixture_only(self):
        config_path = self.home / 'public-config.json'
        config_path.write_text(json.dumps(asdict(self.config)))
        cli_home = self.home / 'cli-home'
        base = [sys.executable, '-m', 'zhixia.model_cli', '--config', str(config_path), '--home', str(cli_home)]
        env = {**os.environ, 'PYTHONIOENCODING': 'utf-8'}
        missing_flag = subprocess.run([*base, 'plan', '--path', 'cli.txt', '--text', 'text'],
                                      capture_output=True, text=True, encoding='utf-8', env=env, check=False)
        self.assertNotEqual(missing_flag.returncode, 0)
        self.assertEqual(self.fixture.requests, [])
        check = subprocess.run([*base, 'check'], capture_output=True, text=True, encoding='utf-8', env=env, check=True)
        self.assertEqual(json.loads(check.stdout)['status'], 'CONFIGURATION_VALID_NO_REQUEST_SENT')
        self.assertEqual(self.fixture.requests, [])
        plan = subprocess.run([*base, 'plan', '--path', 'cli.txt', '--text', 'text', '--enable-model-http'],
                              capture_output=True, text=True, encoding='utf-8', env=env, check=True)
        progress, final = plan.stdout.split('\n', 1)
        self.assertEqual(json.loads(progress)['state'], 'request_reserved')
        self.assertTrue(json.loads(final)['proposal_only'])
        self.assertFalse(json.loads(final)['automatically_approved'])
        self.assertNotIn(TEST_KEY, plan.stdout + plan.stderr)
        self.assertEqual(len(self.fixture.requests), 1)
        self.assertFalse((cli_home / 'workspace/cli.txt').exists())

    def test_real_http_contract_proposal_still_requires_file_approval(self):
        call, task = self.planner.plan_task('note.txt', '你好')
        request = self.fixture.requests[0]
        self.assertEqual(request['path'], '/v1/chat/completions')
        self.assertEqual(request['authorization'], 'Bearer ' + TEST_KEY)
        self.assertFalse(request['body']['stream'])
        self.assertEqual(request['body']['response_format'], {'type': 'json_object'})
        self.assertIn('max_completion_tokens', request['body'])
        self.assertNotIn('max_tokens', request['body'])
        self.assertFalse((self.home / 'workspace/note.txt').exists())
        self.assertEqual(self.kernel.tick(task), State.WAITING_APPROVAL)
        self.assertEqual(self.planner.status()['accounted_or_reserved_microusd'], 160)
        self.kernel.decide(self.kernel.pending(task)[-1]['id'], True)
        self.kernel.tick(task)
        self.assertEqual(self.kernel.tick(task), State.SUCCEEDED)
        dump = '\n'.join(self.kernel.db.iterdump())
        self.assertNotIn(TEST_KEY, dump)
        self.assertNotIn('hidden-fixture-marker', dump)
        self.assertNotIn('Bearer', dump)

    def test_legacy_token_parameter_is_explicit_and_never_fallback(self):
        self.direct(replace(self.config, completion_limit_field='max_tokens'))
        body = self.fixture.requests[-1]['body']
        self.assertIn('max_tokens', body)
        self.assertNotIn('max_completion_tokens', body)

    def test_model_switch_persists_and_exports_only_explicit_summary(self):
        _, first = self.planner.plan_task('first.txt', 'first')
        self.kernel.tick(first)
        self.planner.select_model('fixture-b')
        self.kernel.close()
        self.kernel = Kernel(self.home / 'state.db', self.home / 'workspace')
        self.planner = ModelPlanner(self.kernel, self.config)
        _, second = self.planner.plan_task('second.txt', 'second', context_task_id=first)
        self.assertEqual(self.planner.selected_model(), 'fixture-b')
        self.assertEqual(self.kernel.task(first)['provider'], 'compatible-chat:fixture-a')
        self.assertEqual(self.kernel.task(first)['state'], State.WAITING_APPROVAL)
        self.assertEqual(self.kernel.task(second)['provider'], 'compatible-chat:fixture-b')
        exported = json.loads(self.fixture.requests[-1]['body']['messages'][1]['content'])
        summary = exported['visible_task_summary']
        self.assertEqual(set(summary), {'task_id', 'goal', 'state', 'completed_steps', 'remaining_steps'})
        self.assertEqual(summary['task_id'], first)
        self.assertEqual(summary['state'], 'waiting_approval')
        self.assertNotIn('first', json.dumps(exported['required_actions']))

    def test_budget_blocks_before_network(self):
        tiny = replace(self.config, total_budget_microusd=1)
        other = Kernel(self.home / 'tiny.db', self.home / 'tiny')
        try:
            planner = ModelPlanner(other, tiny)
            with self.assertRaises(BudgetError):
                planner.plan_task('note.txt', 'text')
            self.assertEqual(self.fixture.requests, [])
        finally:
            other.close()

    def test_cumulative_usage_and_call_cap_are_persisted(self):
        limited = replace(self.config, max_calls=1)
        other = Kernel(self.home / 'limited.db', self.home / 'limited')
        try:
            planner = ModelPlanner(other, limited)
            planner.plan_task('one.txt', 'one')
            with self.assertRaisesRegex(BudgetError, 'call_count'):
                planner.plan_task('two.txt', 'two')
            self.assertEqual(len(self.fixture.requests), 1)
        finally:
            other.close()

    def test_missing_usage_is_unknown_holds_reservation_and_blocks_new_call(self):
        self.fixture.mutate = lambda payload: {key: value for key, value in payload.items() if key != 'usage'}
        _, task = self.planner.plan_task('note.txt', 'text')
        status = self.planner.status()
        self.assertTrue(status['financial_uncertainty'])
        self.assertIsNone(status['calls'][0]['prompt_tokens'])
        self.assertIsNone(status['calls'][0]['charged_microusd'])
        self.assertEqual(status['accounted_or_reserved_microusd'], 6144)
        self.assertEqual(self.kernel.task(task)['state'], State.READY)
        with self.assertRaisesRegex(BudgetError, 'unresolved_cost'):
            self.planner.plan_task('again.txt', 'again')
        self.assertEqual(len(self.fixture.requests), 1)

    def test_model_mismatch_keeps_unverified_usage_and_full_reservation(self):
        self.fixture.mutate = lambda payload: {**payload, 'model': 'unquoted-different-model'}
        with self.assertRaisesRegex(ProviderError, 'response_model_mismatch'):
            self.planner.plan_task('first.txt', 'first')
        status = self.planner.status()
        self.assertTrue(status['financial_uncertainty'])
        self.assertEqual(status['accounted_or_reserved_microusd'], 6144)
        call = status['calls'][0]
        self.assertEqual(call['prompt_tokens'], 100)
        self.assertEqual(call['completion_tokens'], 30)
        self.assertIsNone(call['charged_microusd'])
        self.assertEqual(call['financial_state'], 'unknown')
        with self.assertRaisesRegex(BudgetError, 'unresolved_cost'):
            self.planner.plan_task('second.txt', 'second')
        self.assertEqual(len(self.fixture.requests), 1)
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM tasks').fetchone()[0], 0)

    def test_token_ceiling_violation_blocks_even_below_total_reservation(self):
        def mutate(payload):
            payload['usage'] = {'prompt_tokens': 0, 'completion_tokens': 1025, 'total_tokens': 1025}
            return payload
        self.fixture.mutate = mutate
        self.planner.plan_task('note.txt', 'text')
        status = self.planner.status()
        self.assertEqual(status['accounted_or_reserved_microusd'], 2050)
        self.assertTrue(status['reservation_policy_violation'])
        with self.assertRaisesRegex(BudgetError, 'reservation_policy_violation'):
            self.planner.plan_task('again.txt', 'again')
        self.assertEqual(len(self.fixture.requests), 1)

    def test_usage_exceeding_reservation_is_charged_and_blocks(self):
        def mutate(payload):
            payload['usage'] = {'prompt_tokens': 8000, 'completion_tokens': 2000, 'total_tokens': 10000}
            return payload
        self.fixture.mutate = mutate
        self.planner.plan_task('note.txt', 'text')
        status = self.planner.status()
        self.assertEqual(status['accounted_or_reserved_microusd'], 12000)
        self.assertTrue(status['reservation_policy_violation'])
        with self.assertRaisesRegex(BudgetError, 'reservation_policy_violation'):
            self.planner.plan_task('again.txt', 'again')

    def test_invalid_proposal_has_usage_charge_but_no_task_or_approval(self):
        def mutate(payload):
            payload['choices'][0]['message']['content'] = '{"actions":[{"tool":"shell","arguments":{"command":"echo unsafe"}}],"approve":true}'
            return payload
        self.fixture.mutate = mutate
        with self.assertRaisesRegex(ProviderError, 'outside_requested_scope'):
            self.planner.plan_task('note.txt', 'text')
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM tasks').fetchone()[0], 0)
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM approvals').fetchone()[0], 0)
        self.assertEqual(self.planner.status()['accounted_or_reserved_microusd'], 160)

    def test_error_codes_redirect_and_no_retry(self):
        for status in [400, 401, 403, 429, 500, 302]:
            self.fixture.status = status
            before = len(self.fixture.requests)
            with self.subTest(status=status), self.assertRaises(ProviderError) as caught:
                self.direct()
            self.assertTrue(caught.exception.sent)
            self.assertEqual(len(self.fixture.requests), before + 1)
        self.assertEqual(caught.exception.code, 'redirect_rejected')

    def test_slow_drip_headers_are_interrupted_by_deadline(self):
        self.fixture.drip_headers = True
        started = time.monotonic()
        with self.assertRaises(ProviderError):
            self.planner.plan_task('note.txt', 'text')
        self.assertLess(time.monotonic() - started, 1.8)
        self.assertEqual(self.planner.status()['calls'][0]['state'], 'unknown')
        self.assertEqual(self.planner.status()['accounted_or_reserved_microusd'], 6144)

    def test_timeout_after_send_retains_reservation_no_retry(self):
        self.fixture.delay = 1.5
        with self.assertRaises(ProviderError):
            self.planner.plan_task('note.txt', 'text')
        status = self.planner.status()
        self.assertEqual(status['calls'][0]['state'], 'unknown')
        self.assertEqual(status['accounted_or_reserved_microusd'], 6144)
        self.assertEqual(len(self.fixture.requests), 1)

    def test_cancel_before_send_costs_zero_and_sends_nothing(self):
        with self.assertRaises(ProviderError):
            self.planner.plan_task('note.txt', 'text', on_started=self.planner.cancel)
        self.assertEqual(self.fixture.requests, [])
        self.assertEqual(self.planner.status()['accounted_or_reserved_microusd'], 0)

    def test_cancel_after_send_is_unknown_and_publishes_no_plan(self):
        started = []
        cancelled = threading.Event()
        self.fixture.after_request = cancelled.set
        # Read-only cooperative callback changed by fixture once request reached the server.
        with patch.object(self.planner, '_cancelled', side_effect=lambda call: cancelled.is_set()):
            with self.assertRaisesRegex(ProviderError, 'cancelled_after_send'):
                self.planner.plan_task('note.txt', 'text', on_started=started.append)
        self.assertTrue(self.planner.status()['financial_uncertainty'])
        self.assertEqual(self.kernel.db.execute('SELECT count(*) FROM tasks').fetchone()[0], 0)

    def test_secret_in_export_or_response_never_persisted(self):
        with self.assertRaisesRegex(ProviderError, 'credential_in_exported_state'):
            self.planner.plan_task('note.txt', TEST_KEY)
        self.assertEqual(self.fixture.requests, [])
        self.fixture.raw = json.dumps({'error': TEST_KEY}).encode()
        with self.assertRaisesRegex(ProviderError, 'credential_in_response'):
            self.planner.plan_task('note.txt', 'safe')
        self.assertNotIn(TEST_KEY, '\n'.join(self.kernel.db.iterdump()))

    def test_http_error_body_secret_is_not_logged(self):
        self.fixture.status = 401
        self.fixture.raw = (TEST_KEY + ' server error').encode()
        with self.assertRaises(ProviderError) as caught:
            self.planner.plan_task('note.txt', 'text')
        self.assertEqual(str(caught.exception), 'http_401')
        self.assertNotIn(TEST_KEY, '\n'.join(self.kernel.db.iterdump()))

    def test_malformed_and_oversized_body_rejected(self):
        for raw in [b'{', b'[]', b'{"choices":[],"choices":[]}']:
            self.fixture.raw = raw
            with self.subTest(raw=raw), self.assertRaises(ProviderError):
                self.direct()
        self.fixture.raw = b'x' * 300
        with self.assertRaisesRegex(ProviderError, 'response_too_large'):
            self.direct(replace(self.config, max_response_bytes=256))

    def test_refusal_truncation_tool_calls_and_model_mismatch_keep_usage(self):
        def run(mutate):
            self.fixture.mutate = mutate
            with self.assertRaises(ProviderError) as caught:
                self.direct()
            self.assertEqual(caught.exception.usage.prompt_tokens, 100)
        for reason in ['length', 'content_filter', 'tool_calls', None]:
            def mutate(payload, reason=reason):
                payload['choices'][0]['finish_reason'] = reason
                return payload
            run(mutate)
        for field, value in [('refusal', 'no'), ('tool_calls', [{}]), ('content', None)]:
            def mutate(payload, field=field, value=value):
                payload['choices'][0]['message'][field] = value
                return payload
            run(mutate)
        run(lambda payload: {**payload, 'model': 'unrequested-model'})

    def test_invalid_usage_never_becomes_zero(self):
        for value in [-1, True, '100', 1.5]:
            self.fixture.mutate = lambda payload, value=value: {**payload, 'usage': {'prompt_tokens': value, 'completion_tokens': 30}}
            with self.subTest(value=value), self.assertRaises(ProviderError) as caught:
                self.direct()
            self.assertIsNone(caught.exception.usage)

    def test_unsafe_endpoint_or_missing_key_rejected(self):
        for endpoint in ['http://example.com/v1', 'https://user:pass@example.com/v1',
                         'https://example.com/v1?key=secret', 'https://example.com/v1#frag']:
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                replace(self.config, base_url=endpoint).validate()
        with self.assertRaises(ValueError):
            replace(self.config, allow_loopback_http=False).validate()
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ProviderError, 'credential_unavailable'):
                self.direct()
        self.assertEqual(self.fixture.requests, [])

    def test_unsafe_path_rejected_before_model_network(self):
        with self.assertRaises(PolicyError):
            self.planner.plan_task('../escape', 'text')
        self.assertEqual(self.fixture.requests, [])

    def test_unknown_recovery_keeps_reservation_no_automatic_replay(self):
        class WorkerCrash(BaseException):
            pass
        calls = []
        with self.assertRaises(WorkerCrash):
            self.planner.plan_task('note.txt', 'text', on_started=lambda call: (calls.append(call), (_ for _ in ()).throw(WorkerCrash())))
        self.assertEqual(self.planner.status()['calls'][0]['state'], 'running')
        self.planner.recover(calls[0])
        self.assertEqual(self.planner.status()['calls'][0]['state'], 'unknown')
        self.assertEqual(self.planner.status()['accounted_or_reserved_microusd'], 6144)
        self.assertEqual(self.fixture.requests, [])

    def test_configuration_change_does_not_reset_budget(self):
        with self.assertRaises(BudgetError):
            ModelPlanner(self.kernel, replace(self.config, total_budget_microusd=200000))

    def test_read_status_and_cancel_do_not_require_key(self):
        with patch.dict(os.environ, {}, clear=True):
            planner = ModelPlanner(self.kernel, self.config)
            self.assertEqual(planner.status()['selected_model'], 'fixture-a')
            planner.select_model('fixture-b')
            self.assertEqual(planner.selected_model(), 'fixture-b')


if __name__ == '__main__':
    unittest.main()
