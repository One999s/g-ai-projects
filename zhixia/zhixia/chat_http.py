"""Bounded Chat Completions JSON dialect. No tools, retries, redirects or SDK imports."""
from dataclasses import asdict, dataclass
import http.client
import json
import os
import re
import socket
import ssl
import time
import threading
from urllib.parse import urlsplit
from .contracts import Action, TaskSummary, canonical


@dataclass(frozen=True)
class Usage:
    prompt_tokens: int
    completion_tokens: int


class ProviderError(RuntimeError):
    """Only safe machine codes cross this boundary; no response bodies or secrets."""
    def __init__(self, code: str, *, sent: bool = False, usage: Usage | None = None):
        super().__init__(code)
        self.code, self.sent, self.usage = code, sent, usage


def integer(value, label: str, minimum=0, maximum=10**12) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError('invalid ' + label)
    return value


def contains_secret(value, secret: str | None) -> bool:
    if not secret:
        return False
    if isinstance(value, str):
        return secret in value
    if isinstance(value, dict):
        return any(contains_secret(key, secret) or contains_secret(item, secret) for key, item in value.items())
    if isinstance(value, (list, tuple)):
        return any(contains_secret(item, secret) for item in value)
    return False


@dataclass(frozen=True)
class ChatConfig:
    base_url: str
    api_key_env: str | None
    allowed_models: dict
    default_model: str
    total_budget_microusd: int
    input_token_reservation: int = 4096
    max_completion_tokens: int = 1024
    completion_limit_field: str = 'max_completion_tokens'
    allow_loopback_http: bool = False
    timeout_seconds: int = 10
    max_response_bytes: int = 262144
    max_calls: int = 10

    def validate(self):
        url = urlsplit(self.base_url)
        try:
            port = url.port
        except ValueError:
            raise ValueError('invalid endpoint port') from None
        if (not url.hostname or url.username is not None or url.password is not None
                or url.query or url.fragment or any(ord(c) < 33 for c in self.base_url)):
            raise ValueError('endpoint must be an explicit URL without credentials/query/fragment')
        local = url.hostname in {'localhost', '127.0.0.1', '::1'}
        if url.scheme != 'https' and not (url.scheme == 'http' and local and self.allow_loopback_http is True):
            raise ValueError('HTTPS required; loopback HTTP needs explicit test/local configuration')
        if url.path.rstrip('/') != '/v1':
            raise ValueError('this dialect requires an explicit /v1 base URL')
        if port is not None and not 1 <= port <= 65535:
            raise ValueError('invalid endpoint port')
        if self.api_key_env is not None and not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', self.api_key_env):
            raise ValueError('api_key_env must be an environment variable name')
        if not local and not self.api_key_env:
            raise ValueError('remote endpoints require a runtime credential environment reference')
        if not isinstance(self.allowed_models, dict) or self.default_model not in self.allowed_models:
            raise ValueError('explicit allowed model list and default model required')
        for model, price in self.allowed_models.items():
            if not isinstance(model, str) or not model or len(model) > 200 or any(ord(c) < 32 for c in model):
                raise ValueError('invalid public model identifier')
            if not isinstance(price, dict) or set(price) != {'input_microusd_per_token', 'output_microusd_per_token'}:
                raise ValueError('explicit input/output price quote required for every model')
            for value in price.values():
                integer(value, 'price', maximum=1_000_000)
        integer(self.total_budget_microusd, 'total budget')
        integer(self.input_token_reservation, 'input reservation', 1, 1_000_000)
        integer(self.max_completion_tokens, 'completion limit', 1, 100_000)
        integer(self.timeout_seconds, 'timeout', 1, 60)
        integer(self.max_response_bytes, 'response bound', 256, 1_048_576)
        integer(self.max_calls, 'call count limit', 1, 1000)
        if self.completion_limit_field not in {'max_completion_tokens', 'max_tokens'}:
            raise ValueError('unsupported completion limit dialect')
        if type(self.allow_loopback_http) is not bool:
            raise ValueError('loopback permission must be explicit boolean')

    def credential(self) -> str | None:
        self.validate()
        key = os.environ.get(self.api_key_env) if self.api_key_env else None
        if self.api_key_env and not key:
            raise ProviderError('credential_unavailable')
        if key is not None and (not key.isascii() or any(ord(c) < 33 for c in key)):
            raise ProviderError('invalid_credential_format')
        # Configuration is public metadata; never let an accidental credential become its value.
        if contains_secret(asdict(self), key):
            raise ProviderError('credential_in_public_configuration')
        return key


@dataclass(frozen=True)
class Completion:
    actions: list[Action]
    usage: Usage | None


def strict_json(value: str):
    def object_pairs(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ValueError('duplicate JSON key')
            result[key] = item
        return result
    def constant(_):
        raise ValueError('non-finite JSON number')
    return json.loads(value, object_pairs_hook=object_pairs, parse_constant=constant)


class ChatTransport:
    """Non-streaming, single attempt, user-configured endpoint only."""
    def __init__(self, config: ChatConfig):
        config.validate()
        self.config = config

    def complete(self, model: str, summary: TaskSummary, target: str, text: str,
                 cancelled=lambda: False) -> Completion:
        config = self.config
        key = config.credential()
        if model not in config.allowed_models:
            raise ProviderError('model_not_allowed')
        expected = [Action('file.write', {'path': target, 'text': text}),
                    Action('file.verify', {'path': target, 'text': text})]
        request = {'model': model, 'stream': False, 'n': 1,
            config.completion_limit_field: config.max_completion_tokens,
            'response_format': {'type': 'json_object'},
            'messages': [
                {'role': 'system', 'content': 'Return a JSON object with only actions. Copy the supplied two file actions exactly. Do not execute tools, approve actions, include reasoning, or add fields.'},
                {'role': 'user', 'content': canonical({'visible_task_summary': asdict(summary),
                    'required_actions': [action.to_dict() for action in expected]})}]}
        body = canonical(request).encode('utf-8')
        if contains_secret({'summary': asdict(summary), 'path': target, 'text': text}, key):
            raise ProviderError('credential_in_exported_state')
        # A byte cap is only a conservative application bound, not a verified vendor tokenizer.
        if len(body) > config.input_token_reservation:
            raise ProviderError('prompt_exceeds_reservation_policy')
        if cancelled():
            raise ProviderError('cancelled_before_send')
        parsed = urlsplit(config.base_url)
        connection_class = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
        kwargs = {'timeout': config.timeout_seconds}
        if parsed.scheme == 'https':
            kwargs['context'] = ssl.create_default_context()
        connection = connection_class(parsed.hostname, parsed.port, **kwargs)
        sent = False
        response = None
        deadline_timer = None
        expired = threading.Event()
        deadline = time.monotonic() + config.timeout_seconds
        try:
            connection.connect()
            if cancelled():
                raise ProviderError('cancelled_before_send')
            if time.monotonic() >= deadline:
                raise ProviderError('timeout_before_send')
            active_socket = connection.sock
            def interrupt_deadline():
                expired.set()
                try:
                    active_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            deadline_timer = threading.Timer(max(0, deadline - time.monotonic()), interrupt_deadline)
            deadline_timer.daemon = True
            deadline_timer.start()
            headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
            if key:
                headers['Authorization'] = 'Bearer ' + key
            sent = True  # From here on, transport failure may still have incurred cost.
            connection.request('POST', '/v1/chat/completions', body=body, headers=headers)
            response = connection.getresponse()
            if expired.is_set():
                raise ProviderError('response_deadline_exceeded', sent=True)
            if cancelled():
                raise ProviderError('cancelled_after_send', sent=True)
            if response.status != 200:
                code = 'redirect_rejected' if 300 <= response.status < 400 else 'http_' + str(response.status)
                raise ProviderError(code, sent=True)
            if response.getheader('Content-Encoding', 'identity').lower() != 'identity':
                raise ProviderError('unsupported_content_encoding', sent=True)
            if response.getheader('Content-Type', '').split(';')[0].strip().lower() != 'application/json':
                raise ProviderError('unexpected_content_type', sent=True)
            length = response.getheader('Content-Length')
            if length is not None:
                try:
                    if int(length) < 0 or int(length) > config.max_response_bytes:
                        raise ProviderError('response_too_large', sent=True)
                except ValueError:
                    raise ProviderError('invalid_content_length', sent=True) from None
            chunks = bytearray()
            while True:
                if cancelled():
                    raise ProviderError('cancelled_after_send', sent=True)
                if time.monotonic() >= deadline:
                    raise ProviderError('response_deadline_exceeded', sent=True)
                chunk = response.read1(min(16384, config.max_response_bytes + 1 - len(chunks)))
                if not chunk:
                    break
                chunks.extend(chunk)
                if len(chunks) > config.max_response_bytes:
                    raise ProviderError('response_too_large', sent=True)
            if cancelled():
                raise ProviderError('cancelled_after_send', sent=True)
            if expired.is_set():
                raise ProviderError('response_deadline_exceeded', sent=True)
            if length is not None and len(chunks) != int(length):
                raise ProviderError('incomplete_response_body', sent=True)
            if key and key.encode() in chunks:
                raise ProviderError('credential_in_response', sent=True)
            return self._parse(bytes(chunks), model, expected, key)
        except ProviderError:
            raise
        except (TimeoutError, socket.timeout):
            raise ProviderError('transport_timeout', sent=sent) from None
        except Exception:
            # Do not persist or print URL/header/body-bearing library exception strings.
            raise ProviderError('response_deadline_exceeded' if expired.is_set() else 'transport_error', sent=sent) from None
        finally:
            if deadline_timer is not None:
                deadline_timer.cancel()
            if response is not None:
                response.close()
            connection.close()

    @staticmethod
    def _parse(raw: bytes, model: str, expected: list[Action], key: str | None = None) -> Completion:
        usage = None
        try:
            payload = strict_json(raw.decode('utf-8'))
            if contains_secret(payload, key):
                raise ProviderError('credential_in_response', sent=True)
            if not isinstance(payload, dict):
                raise ValueError()
            raw_usage = payload.get('usage')
            if raw_usage is not None:
                if not isinstance(raw_usage, dict):
                    raise ValueError()
                prompt = integer(raw_usage.get('prompt_tokens'), 'prompt tokens', maximum=1_000_000_000)
                completion = integer(raw_usage.get('completion_tokens'), 'completion tokens', maximum=1_000_000_000)
                if 'total_tokens' in raw_usage and integer(raw_usage['total_tokens'], 'total tokens') != prompt + completion:
                    raise ValueError()
                usage = Usage(prompt, completion)
            if payload.get('model') != model:
                raise ProviderError('response_model_mismatch', sent=True, usage=usage)
            choices = payload.get('choices')
            if not isinstance(choices, list) or len(choices) != 1:
                raise ValueError()
            choice = choices[0]
            if choice.get('finish_reason') != 'stop':
                raise ProviderError('incomplete_or_refused_response', sent=True, usage=usage)
            message = choice.get('message')
            if (not isinstance(message, dict) or message.get('role') != 'assistant'
                    or message.get('refusal') or message.get('tool_calls') or message.get('function_call')):
                raise ProviderError('unsupported_response_action', sent=True, usage=usage)
            if not isinstance(message.get('content'), str):
                raise ValueError()
            proposal = strict_json(message['content'])
            if contains_secret(proposal, key):
                raise ProviderError("credential_in_response", sent=True, usage=usage)
            required = {'actions': [action.to_dict() for action in expected]}
            if proposal != required:
                raise ProviderError('proposal_outside_requested_scope', sent=True, usage=usage)
            return Completion(expected, usage)
        except ProviderError:
            raise
        except (ValueError, TypeError, KeyError, AttributeError, UnicodeError):
            raise ProviderError('invalid_response_schema', sent=True, usage=usage) from None
