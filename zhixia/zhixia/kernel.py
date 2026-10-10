"""SQLite task/approval/operation ledger. One action claim is atomic across workers."""
from contextlib import contextmanager
from pathlib import Path
import sqlite3
import time
import uuid
from .contracts import Action, ExecutionContext, State, TaskSummary, canonical, digest
from .providers import Router
from .tools import WorkspaceFiles

POLICY_VERSION = "phase0-files-v1"
TERMINAL = {State.SUCCEEDED, State.FAILED, State.CANCELLED}


class Kernel:
    def __init__(self, database: Path, workspace: Path, clock=time.time, adapter=None):
        self.files = adapter if adapter is not None else WorkspaceFiles(workspace)
        self.policy_version = getattr(self.files, "policy_version", POLICY_VERSION)
        database.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(database, timeout=10, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.clock = clock
        self.db.executescript('''
            PRAGMA foreign_keys=ON;
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=FULL;
            CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS tasks(
                id TEXT PRIMARY KEY, goal TEXT NOT NULL, provider TEXT NOT NULL,
                state TEXT NOT NULL, step INTEGER NOT NULL, plan TEXT NOT NULL,
                cancel_requested INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS approvals(
                id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id),
                step INTEGER NOT NULL, binding TEXT NOT NULL, expires REAL NOT NULL,
                decision TEXT NOT NULL, consumed INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS operations(
                id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id),
                step INTEGER NOT NULL, arguments_hash TEXT NOT NULL,
                policy_version TEXT NOT NULL, approval_id TEXT,
                idempotency_key TEXT NOT NULL UNIQUE, status TEXT NOT NULL,
                receipt TEXT, UNIQUE(task_id,step));
            CREATE TABLE IF NOT EXISTS task_children(
                parent_task_id TEXT PRIMARY KEY REFERENCES tasks(id),
                child_task_id TEXT NOT NULL UNIQUE REFERENCES tasks(id));
            CREATE TABLE IF NOT EXISTS events(
                seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL,
                at REAL NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL);
        ''')
        with self.transaction():
            scope = str(self.files.workspace)
            row = self.db.execute("SELECT value FROM meta WHERE key='workspace'").fetchone()
            if row and row[0] != scope:
                raise ValueError("database belongs to another workspace")
            self.db.execute("INSERT OR IGNORE INTO meta VALUES('workspace',?)", (scope,))
            adapter_id = getattr(self.files, "adapter_id", "workspace-files")
            existing = self.db.execute("SELECT value FROM meta WHERE key='adapter_id'").fetchone()
            if existing and existing[0] != adapter_id:
                raise ValueError("database belongs to another execution adapter")
            self.db.execute("INSERT OR IGNORE INTO meta VALUES('adapter_id',?)", (adapter_id,))
            self.db.execute("INSERT OR IGNORE INTO meta VALUES('schema_version','1')")
            if self.db.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()[0] != "1":
                raise ValueError("unsupported schema version")

    def close(self):
        self.db.close()

    @contextmanager
    def transaction(self):
        self.db.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def event(self, task: str, kind: str, detail: dict):
        # Never persist exception messages, API keys, provider payloads or hidden reasoning.
        self.db.execute("INSERT INTO events(task_id,at,kind,detail) VALUES(?,?,?,?)",
                        (task, self.clock(), kind, canonical(detail)))

    def task(self, task_id: str) -> dict:
        import json
        row = self.db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if row is None:
            raise KeyError("task not found")
        result = dict(row)
        result["plan"] = json.loads(result["plan"])
        return result

    def summary(self, task_id: str) -> TaskSummary:
        task = self.task(task_id)
        return TaskSummary(task_id, task["goal"], task["state"], task["step"],
                           len(task["plan"]) - task["step"])

    def create(self, target: str, text: str, router: Router) -> str:
        task_id = uuid.uuid4().hex
        # The executable demo deliberately limits user goals to this bounded workflow.
        goal = "在工作区创建文本并逐字节验证"
        summary = TaskSummary(task_id, goal, State.READY, 0, 0)
        provider, plan = router.plan(summary, target, text)
        return self.create_actions(goal, provider, plan, task_id)

    def create_actions(self, goal: str, provider: str, plan: list[Action], task_id: str | None = None,
                       exclusive_parent: str | None = None) -> str:
        """Trusted adapter entry: policy validation is still mandatory for every action."""
        task_id = task_id or uuid.uuid4().hex
        if not plan or len(plan) > 16:
            raise ValueError("plan must contain 1–16 bounded actions")
        for action in plan:
            self.files.validate(action)
        with self.transaction():
            self.db.execute("INSERT INTO tasks(id,goal,provider,state,step,plan) VALUES(?,?,?,?,0,?)",
                            (task_id, goal, provider, State.READY,
                             canonical([action.to_dict() for action in plan])))
            if exclusive_parent is not None:
                self.db.execute("INSERT INTO task_children VALUES(?,?)", (exclusive_parent, task_id))
            self.event(task_id, "created", {"provider": provider, "plan_hash": digest([a.to_dict() for a in plan])})
        return task_id

    def binding(self, task: dict, action: Action) -> str:
        return digest({"task_id": task["id"], "step": task["step"],
                       "action": action.to_dict(), "workspace": str(self.files.workspace),
                       "policy_version": self.policy_version})

    def pending(self, task_id: str) -> list[dict]:
        return [dict(row) for row in self.db.execute(
            "SELECT * FROM approvals WHERE task_id=? AND consumed=0 ORDER BY rowid", (task_id,))]

    def decide(self, approval_id: str, allow: bool):
        with self.transaction():
            row = self.db.execute("SELECT * FROM approvals WHERE id=?", (approval_id,)).fetchone()
            if not row or row["consumed"] or row["decision"] != "pending":
                raise ValueError("approval is missing or already decided")
            task = self.task(row["task_id"])
            if task["state"] != State.WAITING_APPROVAL:
                raise ValueError("task is not waiting for approval")
            action = Action(**task["plan"][task["step"]])
            if (task["state"] != State.WAITING_APPROVAL or row["step"] != task["step"]
                    or row["expires"] <= self.clock() or row["binding"] != self.binding(task, action)):
                raise ValueError("approval expired or does not match current action")
            self.db.execute("UPDATE approvals SET decision=? WHERE id=?",
                            ("approved" if allow else "denied", approval_id))
            self.db.execute("UPDATE tasks SET state=? WHERE id=?",
                            (State.READY if allow else State.CANCELLED, task["id"]))
            self.event(task["id"], "approval_decided", {"approval_id": approval_id, "allow": allow})

    def cancel(self, task_id: str):
        with self.transaction():
            task = self.task(task_id)
            if task["state"] in TERMINAL:
                return
            # In-flight execution may already have side effects: cancellation is cooperative.
            state = task["state"] if task["state"] in {State.RUNNING, State.NEEDS_RECONCILIATION} else State.CANCELLED
            self.db.execute("UPDATE tasks SET cancel_requested=1,state=? WHERE id=?", (state, task_id))
            self.event(task_id, "cancel_requested", {"in_flight": state != State.CANCELLED})

    def tick(self, task_id: str, *, approval_ttl: int = 300) -> str:
        if approval_ttl <= 0 or approval_ttl > 3600:
            raise ValueError("approval TTL must be 1–3600 seconds")
        with self.transaction():
            task = self.task(task_id)
            if task["state"] in TERMINAL or task["state"] in {State.RUNNING, State.NEEDS_RECONCILIATION}:
                return task["state"]
            action = Action(**task["plan"][task["step"]])
            self.files.validate(action)
            approval_id = None
            requires_approval = getattr(self.files, "requires_approval", lambda a: a.tool == "file.write")
            if requires_approval(action):
                binding = self.binding(task, action)
                row = self.db.execute('''SELECT * FROM approvals WHERE task_id=? AND step=?
                    AND binding=? AND consumed=0 AND expires>? AND decision IN ('approved','pending')
                    ORDER BY rowid DESC LIMIT 1''',
                    (task_id, task["step"], binding, self.clock())).fetchone()
                if row is None or row["decision"] != "approved":
                    if row is None:
                        approval_id = uuid.uuid4().hex
                        self.db.execute("INSERT INTO approvals VALUES(?,?,?,?,?,'pending',0)",
                                        (approval_id, task_id, task["step"], binding, self.clock() + approval_ttl))
                        self.event(task_id, "approval_requested", {"approval_id": approval_id, "binding": binding})
                    self.db.execute("UPDATE tasks SET state=? WHERE id=?", (State.WAITING_APPROVAL, task_id))
                    return State.WAITING_APPROVAL
                approval_id = row["id"]
                self.db.execute("UPDATE approvals SET consumed=1 WHERE id=?", (approval_id,))
            operation_id = uuid.uuid4().hex
            self.db.execute("INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,NULL)",
                            (operation_id, task_id, task["step"], digest(action.to_dict()), self.policy_version,
                             approval_id, f"{task_id}:{task['step']}", "running"))
            self.db.execute("UPDATE tasks SET state=? WHERE id=?", (State.RUNNING, task_id))
            self.event(task_id, "execution_intent", {"operation_id": operation_id, "tool": action.tool})
        # Durable intent precedes side effect. A hard crash here must not cause automatic replay.
        try:
            claimed_executor = getattr(self.files, "execute_claimed", None)
            receipt = (claimed_executor(action, ExecutionContext(task_id, operation_id))
                       if claimed_executor else self.files.execute(action))
        except Exception as error:
            with self.transaction():
                self.db.execute("UPDATE operations SET status='unknown' WHERE id=?", (operation_id,))
                self.db.execute("UPDATE tasks SET state=? WHERE id=?", (State.NEEDS_RECONCILIATION, task_id))
                self.event(task_id, "execution_uncertain", {"error_type": type(error).__name__})
            return State.NEEDS_RECONCILIATION
        return self._complete(task_id, operation_id, receipt)

    def _complete(self, task_id: str, operation_id: str, receipt: dict) -> str:
        with self.transaction():
            task = self.task(task_id)
            if task["state"] not in {State.RUNNING, State.NEEDS_RECONCILIATION}:
                raise ValueError("task is no longer completable")
            operation = self.db.execute("SELECT * FROM operations WHERE id=? AND task_id=?", (operation_id, task_id)).fetchone()
            if not operation or operation["step"] != task["step"] or operation["status"] not in {"running", "unknown"}:
                raise ValueError("operation is no longer completable")
            next_step = task["step"] + 1
            state = State.CANCELLED if task["cancel_requested"] else (
                State.SUCCEEDED if next_step == len(task["plan"]) else State.READY)
            self.db.execute("UPDATE operations SET status='succeeded',receipt=? WHERE id=?", (canonical(receipt), operation_id))
            self.db.execute("UPDATE tasks SET step=?,state=? WHERE id=?", (next_step, state, task_id))
            self.event(task_id, "verified" if receipt.get("verified") else "executed",
                       {"operation_id": operation_id, "receipt": receipt})
            return state

    def recover(self, task_id: str) -> str:
        """Call only after the old worker has stopped. Never silently steals a live lease."""
        with self.transaction():
            task = self.task(task_id)
            if task["state"] == State.RUNNING:
                self.db.execute("UPDATE operations SET status='unknown' WHERE task_id=? AND status='running'", (task_id,))
                self.db.execute("UPDATE tasks SET state=? WHERE id=?", (State.NEEDS_RECONCILIATION, task_id))
                self.event(task_id, "interrupted", {"requires_read_only_reconciliation": True})
        return self.task(task_id)["state"]

    def reconcile(self, task_id: str) -> str:
        task = self.task(task_id)
        if task["state"] != State.NEEDS_RECONCILIATION:
            raise ValueError("task is not awaiting reconciliation")
        operation = self.db.execute("SELECT * FROM operations WHERE task_id=? AND step=?", (task_id, task["step"])).fetchone()
        action = Action(**task["plan"][task["step"]])
        if operation["arguments_hash"] != digest(action.to_dict()) or operation["policy_version"] != self.policy_version:
            raise ValueError("checkpoint no longer matches action/policy")
        receipt = self.files.reconcile(action)  # Read only; no repeat of the write.
        if receipt is None:
            return State.NEEDS_RECONCILIATION
        return self._complete(task_id, operation["id"], receipt)

    def abandon(self, task_id: str):
        """Operator acknowledges unresolved side effects; never claims rollback."""
        with self.transaction():
            task = self.task(task_id)
            if task["state"] != State.NEEDS_RECONCILIATION:
                raise ValueError("only unresolved tasks may be abandoned")
            self.db.execute("UPDATE tasks SET state=? WHERE id=?", (State.FAILED, task_id))
            self.db.execute("UPDATE operations SET status='abandoned' WHERE task_id=? AND status='unknown'", (task_id,))
            self.event(task_id, "abandoned", {"side_effects_may_remain": True})
