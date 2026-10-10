"""Cooperative workspace confinement. Not protection against hostile local processes."""
import hashlib
import os
from pathlib import Path, PureWindowsPath
import tempfile
from .contracts import Action


class PolicyError(ValueError):
    pass


class WorkspaceFiles:
    MAX_BYTES = 1024 * 1024

    def __init__(self, workspace: Path):
        workspace.mkdir(parents=True, exist_ok=True)
        self.workspace = workspace.resolve()

    def path(self, raw: str) -> Path:
        if not isinstance(raw, str) or not raw or "\x00" in raw:
            raise PolicyError("invalid path")
        relative = Path(raw)
        windows = PureWindowsPath(raw)
        if relative.is_absolute() or windows.is_absolute() or windows.drive or "\\" in raw:
            raise PolicyError("absolute or platform-ambiguous path denied")
        if any(PureWindowsPath(part).is_reserved() or part.endswith((".", " "))
               for part in relative.parts):
            raise PolicyError("Windows reserved or normalization-ambiguous path denied")
        if any(part in {"..", ".runtime"} for part in relative.parts) or ":" in raw:
            raise PolicyError("traversal, runtime or alternate stream denied")
        current = self.workspace
        for part in relative.parts:
            current = current / part
            if current.is_symlink():
                raise PolicyError("symlink paths denied")
            if hasattr(current, "is_junction") and current.is_junction():
                raise PolicyError("Windows junction paths denied")
        resolved = current.resolve()
        if not resolved.is_relative_to(self.workspace) or resolved == self.workspace:
            raise PolicyError("outside workspace")
        return current

    def validate(self, action: Action) -> Path:
        if action.tool not in {"file.write", "file.verify"}:
            raise PolicyError("tool denied: only workspace file write/verify available")
        if set(action.arguments) != {"path", "text"}:
            raise PolicyError("invalid arguments")
        text = action.arguments["text"]
        if not isinstance(text, str) or len(text.encode()) > self.MAX_BYTES:
            raise PolicyError("text missing or exceeds 1 MiB")
        path = self.path(action.arguments["path"])
        if path.exists() and (not path.is_file() or path.stat().st_nlink > 1):
            raise PolicyError("non-regular or hard-linked target denied")
        return path

    def reconcile(self, action: Action) -> dict | None:
        path = self.validate(action)
        if not path.is_file() or path.stat().st_size > self.MAX_BYTES:
            return None
        expected = action.arguments["text"].encode()
        actual = path.read_bytes()
        if actual != expected:
            return None
        return {"path": action.arguments["path"], "bytes": len(actual),
                "sha256": hashlib.sha256(actual).hexdigest(), "verified": True}

    def execute(self, action: Action) -> dict:
        path = self.validate(action)
        if action.tool == "file.write":
            # Preserve user data: no implicit overwrite; identical desired state is idempotent.
            if path.exists():
                evidence = self.reconcile(action)
                if evidence:
                    return evidence
                raise PolicyError("existing file differs; overwrite is not supported")
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
                    temporary = Path(stream.name)
                    stream.write(action.arguments["text"].encode())
                    stream.flush()
                    os.fsync(stream.fileno())
                # Hard-link publication is atomic and fails rather than overwriting a racing writer.
                os.link(temporary, path)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        evidence = self.reconcile(action)
        if evidence is None:
            raise PolicyError("content verification failed")
        return evidence
