"""Deterministic test providers and fail-closed live-adapter configuration."""
from dataclasses import dataclass
import os
from urllib.parse import urlparse
from .contracts import Action, Capabilities, Provider, TaskSummary


class ProviderUnavailable(RuntimeError):
    pass


class FakeProvider:
    capabilities = Capabilities()
    cost_microusd = 0

    def __init__(self, name: str = "fake-a", unavailable: bool = False):
        if name not in {"fake-a", "fake-b"}:
            raise ValueError("unknown deterministic provider")
        self.name = name
        self.unavailable = unavailable
        self.last_summary: TaskSummary | None = None

    def plan(self, summary: TaskSummary, target: str, text: str) -> list[Action]:
        if self.unavailable:
            raise ProviderUnavailable(self.name)
        self.last_summary = summary
        return [Action("file.write", {"path": target, "text": text}),
                Action("file.verify", {"path": target, "text": text})]


class Router:
    def __init__(self, providers: list[Provider], budget_microusd: int = 0):
        if budget_microusd < 0:
            raise ValueError("negative budget")
        self.providers = providers
        self.budget = budget_microusd

    def plan(self, summary: TaskSummary, target: str, text: str) -> tuple[str, list[Action]]:
        for provider in self.providers:
            if not provider.capabilities.structured_actions:
                continue
            if provider.cost_microusd < 0 or provider.cost_microusd > self.budget:
                continue
            try:
                return provider.name, provider.plan(summary, target, text)
            except ProviderUnavailable:
                continue  # Only explicit unavailability permits fallback.
        raise ProviderUnavailable("no eligible provider within capability/budget policy")


@dataclass(frozen=True)
class LiveProviderConfig:
    """Configuration validation only. No network call or credential persistence."""
    kind: str
    model: str
    endpoint: str
    api_key_env: str | None = None

    def validate(self) -> None:
        supported = {"openai", "claude", "grok", "deepseek", "ollama", "vllm", "compatible"}
        if self.kind not in supported or not self.model.strip():
            raise ValueError("unsupported provider or missing model")
        url = urlparse(self.endpoint)
        if url.username or url.password or url.query or url.fragment or not url.hostname:
            raise ValueError("endpoint must not contain credentials, query or fragment")
        local = url.hostname in {"localhost", "127.0.0.1", "::1"}
        if url.scheme != "https" and not (url.scheme == "http" and local):
            raise ValueError("HTTPS required except explicit loopback endpoints")
        if self.kind not in {"ollama", "vllm"} or not local:
            if not self.api_key_env or not os.environ.get(self.api_key_env):
                raise ValueError("API key environment reference is missing or unset")

    def connect(self) -> None:
        self.validate()
        raise NotImplementedError("live network adapters are not implemented in Phase0")
