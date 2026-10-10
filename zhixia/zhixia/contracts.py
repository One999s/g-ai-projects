"""Small serializable contracts; credentials and hidden reasoning are never state."""
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol
import hashlib
import json


class State(StrEnum):
    READY = "ready"
    WAITING_APPROVAL = "waiting_approval"
    RUNNING = "running"
    NEEDS_RECONCILIATION = "needs_reconciliation"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELLED = "cancelled"


class MemoryKind(StrEnum):
    WORKING = "working"
    EPISODIC = "episodic"
    SEMANTIC = "semantic"
    PROCEDURAL = "procedural"
    PROSPECTIVE = "prospective"
    EXPERIENCE = "experience"


def canonical(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value: object) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


@dataclass(frozen=True)
class Action:
    tool: str
    arguments: dict

    def to_dict(self) -> dict:
        return {"tool": self.tool, "arguments": self.arguments}


@dataclass(frozen=True)
class TaskSummary:
    """Only explicitly exported task state crosses a provider boundary."""
    task_id: str
    goal: str
    state: str
    completed_steps: int
    remaining_steps: int


@dataclass(frozen=True)
class ExecutionContext:
    task_id: str
    operation_id: str


@dataclass(frozen=True)
class Capabilities:
    structured_actions: bool = True
    vision: bool = False
    network: bool = False


class Provider(Protocol):
    name: str
    capabilities: Capabilities
    cost_microusd: int

    def plan(self, summary: TaskSummary, target: str, text: str) -> list[Action]: ...


class ExecutionAdapter(Protocol):
    """Adapters execute ONE policy-approved action, never a free-running loop."""
    def execute(self, action: Action) -> dict: ...
    def reconcile(self, action: Action) -> dict | None: ...


class MemoryRepository(Protocol):
    """Future implementation must isolate scopes and support correction/deletion."""
    def put(self, scope: str, kind: MemoryKind, key: str, value: str) -> None: ...
    def get(self, scope: str, kind: MemoryKind, key: str) -> str | None: ...
    def delete(self, scope: str, kind: MemoryKind, key: str) -> None: ...
