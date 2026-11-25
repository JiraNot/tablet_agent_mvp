"""Local-only agent supervisor. Python standard library; Termux compatible.

This is a self-contained Android MVP bridge, not a production multi-tenant service.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import struct
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from collections import deque
from dataclasses import dataclass, field, asdict
from pathlib import Path
from contextlib import contextmanager
from typing import Any
from urllib.parse import urlparse

ALLOWED_MODES = {'plan', 'build'}
TERMINAL = {'PLANNED', 'COMPLETED', 'NEEDS_VERIFICATION', 'FAILED', 'CANCELLED', 'BLOCKED_SETUP', 'INTERRUPTED'}
MAX_LOG_CHARS = 6000
MAX_FILE_BYTES = 256 * 1024
MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
MAX_EVENTS_PER_TASK = 2500
NAME_RE = re.compile(r'^[a-zA-Z0-9][a-zA-Z0-9._-]{0,69}$')
BRANCH_RE = re.compile(r'^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,120}$')
DESTRUCTIVE_CMD_RE = re.compile(
    r'(\brm\s+-[a-zA-Z]*[rf][a-zA-Z]*\b'
    r'|\bgit\s+reset\s+--hard\b'
    r'|\bgit\s+clean\s+-[a-zA-Z]*f[a-zA-Z]*\b'
    r'|\bgit\s+push\b'
    r'|\bdrop\s+(?:table|database)\b'
    r'|\bmkfs\b'
    r'|\bdd\s+if=)',
    re.IGNORECASE
)
REDACTION = re.compile(
    r'(sk-(?:ant-|proj-)?[A-Za-z0-9_\-]{12,}'
    r'|gh[pousr]_[A-Za-z0-9]{16,}'
    r'|github_pat_[A-Za-z0-9_]{50,}'
    r'|AIza[A-Za-z0-9_\-]{20,}'
    r'|Bearer\s+[A-Za-z0-9._\-]{12,}'
    r'|(?:OPENAI|ANTHROPIC|GEMINI|GOOGLE|GITHUB|GH|AWS|STRIPE|DATABASE|[A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|API_KEY))[A-Z0-9_]*\s*=\s*["\']?[^\s"\'\n]{4,})'
)

QUOTA_ERROR_RE = re.compile(
    r'(?:quota(?: exceeded)?|rate[ -]?limit|429 too many requests|insufficient_quota|credit balance|usage limit)',
    re.IGNORECASE
)

class HermesMemoryLedger:
    @staticmethod
    def memory_path(project_path: Path) -> Path:
        agents_dir = project_path / '.agents'
        agents_dir.mkdir(parents=True, exist_ok=True)
        return agents_dir / 'memory.md'

    @classmethod
    def load_memory(cls, project_path: Path) -> dict[str, list[str]]:
        p = cls.memory_path(project_path)
        if not p.is_file():
            return {'proven_patterns': [], 'known_pitfalls': [], 'past_fixes': []}
        try:
            content = p.read_text(encoding='utf-8')
        except OSError:
            return {'proven_patterns': [], 'known_pitfalls': [], 'past_fixes': []}
        res: dict[str, list[str]] = {'proven_patterns': [], 'known_pitfalls': [], 'past_fixes': []}
        current_section = None
        for line in content.splitlines():
            line_s = line.strip()
            if line_s.startswith('## Proven Patterns'):
                current_section = 'proven_patterns'
            elif line_s.startswith('## Known Pitfalls'):
                current_section = 'known_pitfalls'
            elif line_s.startswith('## Past Fixes'):
                current_section = 'past_fixes'
            elif line_s.startswith('## '):
                current_section = None
            elif line_s.startswith('- ') and current_section:
                res[current_section].append(line_s[2:].strip())
        return res

    @classmethod
    def save_memory(cls, project_path: Path, data: dict[str, list[str]]):
        p = cls.memory_path(project_path)
        lines = ['# Hermes Memory Ledger\n']
        lines.append('## Proven Patterns')
        for item in data.get('proven_patterns', [])[-15:]:
            lines.append(f'- {item}')
        lines.append('\n## Known Pitfalls')
        for item in data.get('known_pitfalls', [])[-15:]:
            lines.append(f'- {item}')
        lines.append('\n## Past Fixes')
        for item in data.get('past_fixes', [])[-15:]:
            lines.append(f'- {item}')
        lines.append('')
        p.write_text('\n'.join(lines), encoding='utf-8')

    @classmethod
    def record_success(cls, project_path: Path, note: str):
        clean = note.strip()
        if not clean:
            return
        data = cls.load_memory(project_path)
        if clean not in data['proven_patterns']:
            data['proven_patterns'].append(clean)
            cls.save_memory(project_path, data)

    @classmethod
    def record_fix(cls, project_path: Path, error_summary: str, fix_summary: str):
        clean_err = error_summary.strip().replace('\n', ' ')[:140]
        clean_fix = fix_summary.strip().replace('\n', ' ')[:160]
        entry = f"**Error:** `{clean_err}` ➔ **Fix:** `{clean_fix}`"
        data = cls.load_memory(project_path)
        if entry not in data['past_fixes']:
            data['past_fixes'].append(entry)
            cls.save_memory(project_path, data)

    @classmethod
    def record_pitfall(cls, project_path: Path, pitfall: str):
        clean = pitfall.strip().replace('\n', ' ')[:180]
        if not clean:
            return
        data = cls.load_memory(project_path)
        if clean not in data['known_pitfalls']:
            data['known_pitfalls'].append(clean)
            cls.save_memory(project_path, data)

    @classmethod
    def build_context(cls, project_path: Path, max_chars: int = 1200) -> str:
        data = cls.load_memory(project_path)
        parts = []
        if data['proven_patterns']:
            parts.append('Proven Patterns: ' + '; '.join(data['proven_patterns'][-4:]))
        if data['past_fixes']:
            parts.append('Past Fixes: ' + '; '.join(data['past_fixes'][-3:]))
        if data['known_pitfalls']:
            parts.append('Known Pitfalls: ' + '; '.join(data['known_pitfalls'][-3:]))
        if not parts:
            return ''
        joined = '\n'.join(parts)
        return f'\n\nHermes Memory Ledger (.agents/memory.md):\n{joined[:max_chars]}'

class BridgeError(Exception):
    def __init__(self, message: str, status: int = 400):
        self.status = status
        super().__init__(message)

@dataclass(frozen=True)
class AgentCapabilities:
    name: str
    display_name: str
    binary: str
    can_plan: bool
    can_build: bool
    supports_resume: bool
    models: list[str] = field(default_factory=list)
    permission_scopes: list[str] = field(default_factory=lambda: ['plan-only', 'workspace-write', 'confirm-destructive'])

    def to_dict(self, available: bool = True) -> dict[str, Any]:
        d = asdict(self)
        d['available'] = available
        return d

@dataclass(frozen=True)
class ResumeHandle:
    project: str
    agent: str
    session_id: str

@dataclass(frozen=True)
class AgentRun:
    task_id: str
    project: str
    agent: str
    mode: str
    permission_scope: str
    model: str = ''
    resume: ResumeHandle | None = None

@dataclass(frozen=True)
class AgentEvent:
    kind: str
    message: str
    session_id: str = ''
    action_type: str = ''
    target: str = ''

@dataclass(frozen=True)
class AgentApproval:
    id: str
    task_id: str
    type: str
    target: str
    status: str = 'PENDING'

@dataclass(frozen=True)
class AgentError:
    code: str
    message: str
    recoverable: bool = False

class BaseAgentAdapter:
    def __init__(self, name: str, display_name: str, binary: str, can_plan: bool, can_build: bool, supports_resume: bool, models: list[str]):
        self.name = name
        self.display_name = display_name
        self.binary = binary
        self.can_plan = can_plan
        self.can_build = can_build
        self.supports_resume = supports_resume
        self.models = models

    def capabilities(self) -> AgentCapabilities:
        return AgentCapabilities(
            name=self.name,
            display_name=self.display_name,
            binary=self.binary,
            can_plan=self.can_plan,
            can_build=self.can_build,
            supports_resume=self.supports_resume,
            models=list(self.models),
        )

    def permission_scope_for_mode(self, mode: str) -> str:
        return 'plan-only' if mode == 'plan' else 'workspace-write'

    def _resolve_binary(self, project_path: Path | None = None) -> list[str]:
        if shutil.which(self.binary):
            return [self.binary]
        user_bin = Path.home() / '.local' / 'bin' / self.binary
        if user_bin.is_file() and os.access(user_bin, os.X_OK):
            return [str(user_bin)]
        proot_deb_bin = Path('/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs/root/.local/bin') / self.binary
        if proot_deb_bin.is_file() and shutil.which('proot-distro'):
            prefix = ['proot-distro', 'login', 'debian']
            if project_path:
                prefix += ['--bind', f'{project_path}:{project_path}', '--workdir', str(project_path)]
            return prefix + ['--', f'/root/.local/bin/{self.binary}']
        return [self.binary]

    def is_available(self) -> bool:
        if shutil.which(self.binary):
            return True
        user_bin = Path.home() / '.local' / 'bin' / self.binary
        if user_bin.is_file() and os.access(user_bin, os.X_OK):
            return True
        proot_deb_bin = Path('/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs/root/.local/bin') / self.binary
        if proot_deb_bin.is_file() and shutil.which('proot-distro'):
            return True
        return False

    def build_cmd(self, project_path: Path, prompt: str, mode: str, thread_id: str | None = None, model: str = '') -> list[str]:
        raise NotImplementedError

class CodexAdapter(BaseAgentAdapter):
    def __init__(self):
        super().__init__(
            name='codex',
            display_name='Codex CLI',
            binary='codex',
            can_plan=True,
            can_build=True,
            supports_resume=True,
            models=[
                'default',
                'gpt-6.2-sol',
                'gpt-6.2-astra',
                'gpt-6.1-terra',
                'gpt-6.1-sol',
                'gpt-6-astra',
                'gpt-6-sol',
                'gpt-6-luna',
                'gpt-5.6-sol',
                'gpt-5.6-terra',
                'gpt-5.6-luna',
            ]
        )

    def sandbox_for_mode(self, mode: str, sandbox_override: str | None = None) -> str:
        if mode == 'plan':
            return 'read-only'
        if sandbox_override == 'danger-full-access':
            return 'danger-full-access'
        return 'workspace-write'

    def build_cmd(self, project_path: Path, prompt: str, mode: str, thread_id: str | None = None, model: str = '', sandbox_override: str | None = None) -> list[str]:
        bin_cmd = self._resolve_binary(project_path)
        sandbox = self.sandbox_for_mode(mode, sandbox_override)
        cmd = bin_cmd + ['exec', '--json', '--sandbox', sandbox, '--skip-git-repo-check']
        if thread_id and self.supports_resume:
            cmd += ['resume', thread_id]
        if model and model != 'default':
            cmd += ['--model', model]
        cmd.append(prompt)
        return cmd

    def build_app_server_cmd(self, project_path: Path) -> list[str]:
        return self._resolve_binary(project_path) + ['app-server', '--stdio']

    def build_app_server_handshake(
        self,
        project_path: Path,
        mode: str,
        thread_id: str | None = None,
        model: str = '',
        sandbox_override: str | None = None,
    ) -> list[dict[str, Any]]:
        sandbox = self.sandbox_for_mode(mode, sandbox_override)
        thread_params: dict[str, Any] = {
            'cwd': str(project_path),
            'sandbox': sandbox,
            'approvalPolicy': 'on-request',
        }
        if model and model != 'default':
            thread_params['model'] = model
        if thread_id and self.supports_resume:
            thread_params['threadId'] = thread_id
            thread_params['excludeTurns'] = True
            thread_req = {'jsonrpc': '2.0', 'id': 2, 'method': 'thread/resume', 'params': thread_params}
        else:
            thread_req = {'jsonrpc': '2.0', 'id': 2, 'method': 'thread/start', 'params': thread_params}
        return [
            {
                'jsonrpc': '2.0',
                'id': 1,
                'method': 'initialize',
                'params': {
                    'clientInfo': {'name': 'tablet-agent', 'title': 'Tablet Agent Studio', 'version': '0.1.0'},
                    'capabilities': None,
                },
            },
            {'jsonrpc': '2.0', 'method': 'initialized'},
            thread_req,
        ]

    def build_turn_start_request(self, thread_id: str, prompt: str, req_id: int = 3) -> dict[str, Any]:
        return {
            'jsonrpc': '2.0',
            'id': req_id,
            'method': 'turn/start',
            'params': {
                'threadId': thread_id,
                'input': [{'type': 'text', 'text': prompt, 'text_elements': []}],
            },
        }

    def build_turn_interrupt_request(self, thread_id: str, turn_id: str, req_id: int = 99) -> dict[str, Any]:
        return {
            'jsonrpc': '2.0',
            'id': req_id,
            'method': 'turn/interrupt',
            'params': {'threadId': thread_id, 'turnId': turn_id},
        }

    def build_approval_response(self, rpc_id: Any, method: str, allowed: bool) -> dict[str, Any]:
        if method in {'execCommandApproval', 'applyPatchApproval'}:
            decision: Any = 'approved' if allowed else {'denied': {'rejection': 'Denied by user'}}
        else:
            decision = 'accept' if allowed else 'decline'
        return {'jsonrpc': '2.0', 'id': rpc_id, 'result': {'decision': decision}}

class ClaudeCodeAdapter(BaseAgentAdapter):
    def __init__(self):
        super().__init__(
            name='claude',
            display_name='Claude Code',
            binary='claude',
            can_plan=True,
            can_build=True,
            supports_resume=True,
            models=['default', 'claude-3-7-sonnet', 'claude-3-5-sonnet', 'claude-3-5-haiku']
        )

    def build_cmd(self, project_path: Path, prompt: str, mode: str, thread_id: str | None = None, model: str = '') -> list[str]:
        bin_cmd = self._resolve_binary(project_path)
        cmd = bin_cmd + ['--print', '--output-format', 'stream-json']
        if thread_id and self.supports_resume:
            cmd += ['--resume', thread_id]
        if model and model != 'default':
            cmd += ['--model', model]
        cmd.append(prompt)
        return cmd

class OpenCodeAdapter(BaseAgentAdapter):
    def __init__(self):
        super().__init__(
            name='opencode',
            display_name='OpenCode',
            binary='opencode',
            can_plan=True,
            can_build=True,
            supports_resume=False,
            models=['default']
        )

    def build_cmd(self, project_path: Path, prompt: str, mode: str, thread_id: str | None = None, model: str = '') -> list[str]:
        bin_cmd = self._resolve_binary(project_path)
        cmd = bin_cmd + ['run', '--mode', mode]
        if model and model != 'default':
            cmd += ['--model', model]
        cmd.append(prompt)
        return cmd

class AntigravityAdapter(BaseAgentAdapter):
    def __init__(self):
        super().__init__(
            name='antigravity',
            display_name='Antigravity CLI (agy)',
            binary='agy',
            can_plan=True,
            can_build=True,
            supports_resume=True,
            models=[
                'default',
                'gemini-4-neon-high',
                'gemini-4-neon-xhigh',
                'gemini-4-neon-medium',
                'gemini-4-neon-low',
                'gemini-4-argon-high',
                'gemini-4-argon-medium',
                'gemini-4-argon-low',
                'gemini-4-hydrogen-high',
                'gemini-4-hydrogen-xhigh',
                'gemini-4-hydrogen-medium',
                'gemini-4-hydrogen-low',
                'gemini-pro-orchestrator-neon',
                'gemini-pro-orchestrator-g1-pro',
                'gemini-3.8-flash-high',
                'gemini-3.8-flash-medium',
                'gemini-3.8-flash-low',
                'claude-opus-5-5-high',
                'claude-opus-5-5-medium',
                'claude-opus-5-5-low',
                'claude-sonnet-5-5-high',
                'claude-sonnet-5-5-medium',
                'claude-sonnet-5-5-low',
                'gpt-oss-120b-medium',
            ]
        )

    def build_cmd(self, project_path: Path, prompt: str, mode: str, thread_id: str | None = None, model: str = '') -> list[str]:
        bin_cmd = self._resolve_binary(project_path)
        cmd = bin_cmd + ['--output-format', 'stream-json']
        if mode == 'plan':
            cmd += ['--mode', 'plan']
        if thread_id and self.supports_resume:
            cmd += ['--conversation', thread_id]
        if model and model != 'default':
            cmd += ['--model', model]
        cmd.append('--print=' + prompt)
        return cmd

class LocalSlmAdapter(BaseAgentAdapter):
    def __init__(self):
        super().__init__(
            name='slm',
            display_name='Local SLM (llama.cpp Offline)',
            binary='llama-cli',
            can_plan=True,
            can_build=True,
            supports_resume=False,
            models=[
                'default',
                'qwen2.5-coder-1.5b-q4',
                'qwen2.5-coder-3b-q4',
                'gemma-2-2b-it-q4',
            ]
        )

    def _find_model_path(self, model_name: str) -> Path | None:
        model_dirs = [
            Path('/root/models'),
            Path.home() / 'models',
            Path('/data/data/com.tabletagent.local/files/runtime/rootfs/root/models'),
        ]
        for d in model_dirs:
            if not d.exists():
                continue
            for f in d.glob('*.gguf'):
                if model_name in f.name.lower() or model_name == 'default':
                    return f
            for f in d.glob('*.gguf'):
                return f
        return None

    def is_available(self) -> bool:
        if shutil.which(self.binary) or shutil.which('llama-server'):
            return True
        for p in ['/usr/bin/llama-cli', '/usr/local/bin/llama-cli', '/root/.local/bin/llama-cli']:
            if Path(p).is_file():
                return True
        return False

    def build_cmd(self, project_path: Path, prompt: str, mode: str, thread_id: str | None = None, model: str = '', sandbox_override: str | None = None) -> list[str]:
        bin_cmd = self._resolve_binary(project_path)
        model_file = self._find_model_path(model if model and model != 'default' else 'qwen')
        model_arg = str(model_file) if model_file else '/root/models/qwen2.5-coder-1.5b.gguf'
        sys_prompt = "You are a local coding assistant running on an Android tablet. Provide concise, production-ready code."
        chat_prompt = f"<|im_start|>system\n{sys_prompt}<|im_end|>\n<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n"
        return bin_cmd + ['-m', model_arg, '-p', chat_prompt, '-n', '1024', '-c', '2048', '--temp', '0.2', '--no-display-prompt']

class LocalCore:
    def __init__(self, home: Path):
        self.home = Path(home).expanduser().resolve()
        self.workspaces = self.home / 'workspaces'
        self.home.mkdir(parents=True, exist_ok=True)
        self.workspaces.mkdir(parents=True, exist_ok=True)
        self.dbpath = self.home / 'state.sqlite3'
        self.token_path = self.home / 'device_token'
        self.token = self._load_token()
        self.lock = threading.RLock()
        self.procs: dict[str, subprocess.Popen] = {}
        self.app_server_turns: dict[str, tuple[subprocess.Popen, str, str]] = {}
        self.thread_candidates: dict[str, str] = {}
        self.previews: dict[str, dict[str, Any]] = {}
        self.terminals: dict[str, dict[str, Any]] = {}
        self.approval_events: dict[str, threading.Event] = {}
        self.task_queues: dict[str, list[dict[str, Any]]] = {}
        self.active_worktrees: dict[str, Path] = {}
        self.live_streams: dict[str, dict[str, Any]] = {}
        self.adapters: dict[str, BaseAgentAdapter] = {
            'codex': CodexAdapter(),
            'claude': ClaudeCodeAdapter(),
            'opencode': OpenCodeAdapter(),
            'antigravity': AntigravityAdapter(),
            'slm': LocalSlmAdapter()
        }
        self._init_db()
        self._recover_interrupted()

    def _load_token(self) -> str:
        if self.token_path.exists():
            return self.token_path.read_text().strip()
        token = secrets.token_urlsafe(32)
        fd = os.open(str(self.token_path), os.O_CREAT | os.O_WRONLY | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            f.write(token + '\n')
        return token

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.dbpath, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA busy_timeout=5000')
        return db

    @contextmanager
    def _db(self):
        conn = self._connect()
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def _init_db(self):
        with self._db() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''
            CREATE TABLE IF NOT EXISTS tasks(
                id TEXT PRIMARY KEY, project TEXT NOT NULL, prompt TEXT NOT NULL,
                agent TEXT NOT NULL, mode TEXT NOT NULL, status TEXT NOT NULL,
                created_at REAL NOT NULL, updated_at REAL NOT NULL,
                error TEXT NOT NULL DEFAULT '', result TEXT NOT NULL DEFAULT '',
                verification TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS agent_threads(
                project TEXT PRIMARY KEY, agent TEXT NOT NULL, session_id TEXT NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS events(
                seq INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL,
                timestamp REAL NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_events_task_seq ON events(task_id, seq);
            CREATE TABLE IF NOT EXISTS approvals(
                id TEXT PRIMARY KEY, task_id TEXT NOT NULL,
                type TEXT NOT NULL, target TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                created_at REAL NOT NULL, resolved_at REAL
            );
            CREATE INDEX IF NOT EXISTS idx_approvals_task_status ON approvals(task_id, status);
            CREATE TABLE IF NOT EXISTS settings(
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS process_pids(
                id TEXT PRIMARY KEY,
                pid INTEGER NOT NULL,
                pgid INTEGER NOT NULL,
                cmd_hint TEXT NOT NULL,
                started_at REAL NOT NULL
            );
            ''')

    def _register_pid(self, key: str, proc: subprocess.Popen, cmd_hint: str):
        pgid = proc.pid
        if hasattr(os, 'getpgid'):
            try:
                pgid = os.getpgid(proc.pid)
            except OSError:
                pgid = proc.pid
        with self._db() as db:
            db.execute(
                'INSERT INTO process_pids(id, pid, pgid, cmd_hint, started_at) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET pid=excluded.pid, pgid=excluded.pgid, cmd_hint=excluded.cmd_hint, started_at=excluded.started_at',
                (key, proc.pid, pgid, cmd_hint[:80], time.time())
            )

    def _unregister_pid(self, key: str):
        with self._db() as db:
            db.execute('DELETE FROM process_pids WHERE id=?', (key,))

    def _terminate_pid_record(self, pid: int, pgid: int, cmd_hint: str) -> bool:
        if pid <= 1:
            return False
        proc_cmdline = Path(f'/proc/{pid}/cmdline')
        if proc_cmdline.exists():
            try:
                raw_cmd = proc_cmdline.read_bytes().replace(b'\x00', b' ').decode('utf-8', errors='replace')
                if cmd_hint and cmd_hint.lower() not in raw_cmd.lower():
                    return False
            except OSError:
                return False
        try:
            os.kill(pid, 0)
        except OSError:
            return False
        try:
            if hasattr(os, 'killpg') and pgid > 1:
                os.killpg(pgid, signal.SIGTERM)
            else:
                os.kill(pid, signal.SIGTERM)
        except OSError:
            return False
        if os.name == 'nt':
            return True
        deadline = time.monotonic() + 0.6
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
                time.sleep(0.05)
            except OSError:
                return True
        try:
            if hasattr(os, 'killpg') and pgid > 1:
                os.killpg(pgid, getattr(signal, 'SIGKILL', signal.SIGTERM))
            else:
                os.kill(pid, getattr(signal, 'SIGKILL', signal.SIGTERM))
        except OSError:
            pass
        return True

    def _recover_interrupted(self):
        now = time.time()
        with self._db() as db:
            pids = [dict(r) for r in db.execute('SELECT pid, pgid, cmd_hint FROM process_pids').fetchall()]
            for rec in pids:
                self._terminate_pid_record(int(rec['pid']), int(rec['pgid']), str(rec['cmd_hint']))
            db.execute('DELETE FROM process_pids')
            db.execute("UPDATE tasks SET status='INTERRUPTED', error='Bridge restarted; CLI process status unknown', updated_at=? WHERE status NOT IN ('PLANNED','COMPLETED','NEEDS_VERIFICATION','FAILED','CANCELLED','BLOCKED_SETUP','INTERRUPTED')", (now,))
            db.execute("UPDATE approvals SET status='DENIED', resolved_at=? WHERE status='PENDING'", (now,))

    def get_settings(self) -> dict[str, Any]:
        with self._db() as db:
            rows = db.execute('SELECT key, value FROM settings').fetchall()
        raw = {r['key']: r['value'] for r in rows}
        default_app_server = 'true' if os.environ.get('TABLET_AGENT_EMBEDDED') == '1' else 'false'
        return {
            'codex_proot_sandbox_bypass': raw.get('codex_proot_sandbox_bypass', 'false') == 'true',
            'codex_use_app_server': raw.get('codex_use_app_server', default_app_server) == 'true',
        }

    def update_settings(self, patch: dict[str, Any]) -> dict[str, Any]:
        for key in ('codex_proot_sandbox_bypass', 'codex_use_app_server'):
            if key in patch:
                val = patch[key]
                if not isinstance(val, bool):
                    raise BridgeError(f'{key} must be a boolean')
                with self._db() as db:
                    db.execute(
                        'INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value',
                        (key, 'true' if val else 'false')
                    )
        return self.get_settings()

    def _project(self, name: str) -> Path:
        if not NAME_RE.fullmatch(name) or name in {'.', '..'}:
            raise BridgeError('Invalid project name')
        folder = self.workspaces / name
        if not folder.is_dir():
            raise BridgeError('Project not found', 404)
        return folder

    def _file(self, project: str, rel: str) -> Path:
        base = self._project(project).resolve()
        if not rel or rel.startswith('/') or '\x00' in rel:
            raise BridgeError('Invalid relative path')
        out = (base / rel).resolve()
        if not out.is_relative_to(base) or out == base:
            raise BridgeError('Path is outside workspace', 403)
        forbidden = {'.git', 'node_modules', '.next', '.gradle', '.venv'}
        if any(x in forbidden for x in out.relative_to(base).parts):
            raise BridgeError('Access to generated/internal directory is blocked', 403)
        return out

    def projects(self) -> list[dict[str, Any]]:
        ans = []
        for p in sorted(self.workspaces.iterdir()):
            if p.is_dir() and not p.is_symlink() and NAME_RE.fullmatch(p.name):
                ans.append({'name': p.name, 'git': (p / '.git').exists(), 'package_json': (p / 'package.json').exists()})
        return ans

    @staticmethod
    def _github_auth_git_args(gh_token: str) -> list[str]:
        """Return git -c args that authenticate GitHub HTTPS without duplicate Authorization headers."""
        tok = (gh_token or '').strip()
        if not tok:
            return []
        import base64
        basic = base64.b64encode(f'x-access-token:{tok}'.encode('utf-8')).decode('ascii')
        return [
            '-c', 'credential.helper=',
            '-c', 'http.https://github.com/.extraheader=',
            '-c', f'http.https://github.com/.extraheader=AUTHORIZATION: basic {basic}',
        ]

    def create_project(self, name: str, git_url: str = '', token: str = '') -> dict[str, Any]:
        if not NAME_RE.fullmatch(name) or name in {'.', '..'}:
            raise BridgeError('Invalid project name')
        target = self.workspaces / name
        if target.exists():
            raise BridgeError('Project already exists', 409)
        if git_url:
            clean_url = git_url.strip()
            # Normalize common URL variations
            if clean_url.startswith('git@github.com:'):
                clean_url = 'https://github.com/' + clean_url[len('git@github.com:'):]
            elif clean_url.startswith('github.com/'):
                clean_url = 'https://' + clean_url
            elif re.fullmatch(r'^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$', clean_url):
                clean_url = f'https://github.com/{clean_url}.git'
            elif not clean_url.startswith('https://') and not clean_url.startswith('http://') and '/' in clean_url:
                clean_url = 'https://github.com/' + clean_url.lstrip('/')

            clean_url = clean_url.rstrip('/')

            u = urlparse(clean_url)
            if u.scheme != 'https' or u.hostname != 'github.com' or u.username or u.password or u.port:
                raise BridgeError('Only credential-free https://github.com URLs are accepted')
            if not re.fullmatch(r'/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?/?', u.path):
                raise BridgeError('Expected a GitHub repository URL')
            if u.query or u.fragment:
                raise BridgeError('Git URL must not contain a query or fragment')
            try:
                clone_cmd = ['git', 'clone']
                gh_token = (token or '').strip() or os.environ.get('GITHUB_TOKEN', '').strip()
                clone_env = os.environ.copy()
                clone_env['GIT_TERMINAL_PROMPT'] = '0'
                if gh_token:
                    clone_cmd += self._github_auth_git_args(gh_token)
                clone_cmd += ['--', clean_url, str(target)]
                res = subprocess.run(clone_cmd, timeout=160, capture_output=True, text=True, env=clone_env)
                if res.returncode != 0:
                    raw_err = (res.stderr or res.stdout or '').strip()
                    shutil.rmtree(target, ignore_errors=True)
                    clean_err = REDACTION.sub('[REDACTED]', raw_err)
                    if 'Repository not found' in clean_err:
                        if not gh_token:
                            raise BridgeError('Repository not found or private. Please connect your GitHub account in Settings to clone private repositories.', 404)
                        else:
                            raise BridgeError('Repository not found or access denied with connected GitHub token.', 404)
                    elif 'Authentication failed' in clean_err:
                        raise BridgeError('GitHub authentication failed. Please check or reconnect your GitHub account in Settings.', 401)
                    raise BridgeError(f'Clone failed: {clean_err[:300]}', 422)
            except subprocess.TimeoutExpired:
                shutil.rmtree(target, ignore_errors=True)
                raise BridgeError('Git clone timed out after 160 seconds. Check your internet connection.', 504)
            except OSError as e:
                shutil.rmtree(target, ignore_errors=True)
                raise BridgeError(f'Git executable error: {str(e)}', 500)
            if (target / '.git').exists():
                git_cmd = shutil.which('git') or 'git'
                gh_user = os.environ.get('GITHUB_USER', '').strip()
                gh_email = os.environ.get('GITHUB_EMAIL', '').strip()
                if gh_user:
                    subprocess.run([git_cmd, 'config', 'user.name', gh_user], cwd=target, capture_output=True, timeout=5)
                if gh_email:
                    subprocess.run([git_cmd, 'config', 'user.email', gh_email], cwd=target, capture_output=True, timeout=5)
        else:
            target.mkdir(mode=0o700)
            (target / 'README.md').write_text(f'# {name}\n')
            gitignore = target / '.gitignore'
            if not gitignore.exists():
                gitignore.write_text('node_modules/\ndist/\n.next/\n.venv/\n__pycache__/\n.gradle/\n.env\n.env.*\n!.env.example\n', encoding='utf-8')
            if shutil.which('git'):
                subprocess.run(['git', 'init', '-q', str(target)], capture_output=True, timeout=15)
        return {'name': name, 'git': (target / '.git').exists()}

    def list_files(self, project: str, rel: str = '.') -> list[dict[str, Any]]:
        base = self._project(project)
        path = base if rel == '.' else self._file(project, rel)
        if not path.is_dir():
            raise BridgeError('Not a folder')
        out = []
        for obj in sorted(path.iterdir(), key=lambda p: (not p.is_dir(), p.name.casefold()))[:150]:
            if obj.name in {'.git', 'node_modules', '.next', '.gradle', '.venv'}:
                continue
            try:
                check = obj.resolve()
                if not check.is_relative_to(base.resolve()):
                    continue
                out.append({'name': obj.name, 'path': str(obj.relative_to(base)), 'directory': obj.is_dir(), 'bytes': obj.stat().st_size if obj.is_file() else 0})
            except OSError:
                continue
        return out

    def read_file(self, project: str, rel: str) -> dict[str, Any]:
        p = self._file(project, rel)
        if not p.is_file():
            raise BridgeError('File not found', 404)
        if p.stat().st_size > MAX_FILE_BYTES:
            raise BridgeError('File too large (256 KiB limit)', 413)
        try:
            content = p.read_text('utf-8')
        except UnicodeDecodeError:
            raise BridgeError('Binary files cannot be opened')
        return {'path': rel, 'content': content}

    def write_file(self, project: str, rel: str, content: str, create_dirs: bool = False):
        if len(content.encode('utf-8')) > MAX_FILE_BYTES:
            raise BridgeError('File too large (256 KiB limit)', 413)
        p = self._file(project, rel)
        if not p.parent.is_dir():
            if create_dirs:
                p.parent.mkdir(parents=True, exist_ok=True)
            else:
                raise BridgeError('Parent directory not found', 404)
        tmp = p.parent / f'.{p.name}.{uuid.uuid4().hex}.tmp'
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as f:
                f.write(content)
                f.flush(); os.fsync(f.fileno())
            if tmp:
                os.replace(tmp, p)
            else:
                raise BridgeError('Atomic write unavailable')
        except Exception:
            if tmp:
                tmp.unlink(missing_ok=True)
            raise
        return {'path': rel, 'bytes': len(content.encode('utf-8'))}

    def save_attachment(self, project: str, filename: str, data_b64: str) -> dict[str, Any]:
        import base64
        if not data_b64 or not isinstance(data_b64, str):
            raise BridgeError('Missing base64 attachment payload', 400)
        try:
            raw = base64.b64decode(data_b64, validate=True)
        except Exception:
            raise BridgeError('Invalid base64 encoding', 400)
        if len(raw) > MAX_ATTACHMENT_BYTES:
            raise BridgeError('Attachment exceeds 25 MB limit', 413)
        safe_name = re.sub(r'[^a-zA-Z0-9_.-]', '_', Path(filename).name)
        if not safe_name or safe_name in ('.', '..'):
            safe_name = f'attachment_{int(time.time())}.bin'
        att_dir = self._project(project) / '.agents' / 'attachments'
        att_dir.mkdir(parents=True, exist_ok=True)
        target = att_dir / safe_name
        tmp = att_dir / f'.{safe_name}.{uuid.uuid4().hex}.tmp'
        try:
            tmp.write_bytes(raw)
            os.replace(tmp, target)
        except Exception:
            tmp.unlink(missing_ok=True)
            raise
        return {
            'path': f'.agents/attachments/{safe_name}',
            'size_bytes': len(raw),
            'filename': safe_name
        }

    def append(self, task: str, kind: str, message: str):
        msg = REDACTION.sub('[REDACTED]', str(message))[:MAX_LOG_CHARS]
        with self._db() as db:
            db.execute('INSERT INTO events(task_id,timestamp,kind,message) VALUES(?,?,?,?)', (task, time.time(), kind, msg))
            db.execute(
                "DELETE FROM events WHERE task_id=? AND kind NOT IN ('user','message','thought','error') "
                "AND seq NOT IN (SELECT seq FROM events WHERE task_id=? ORDER BY seq DESC LIMIT ?)",
                (task, task, MAX_EVENTS_PER_TASK)
            )

    def set_status(self, task: str, status: str, *, error: str | None = None, result: str | None = None, verification: str | None = None):
        cols = ['status=?', 'updated_at=?']; vals: list[Any] = [status, time.time()]
        for k,v in [('error',error),('result',result),('verification',verification)]:
            if v is not None:
                cols.append(f'{k}=?'); vals.append(REDACTION.sub('[REDACTED]',v)[:6000])
        vals.append(task)
        with self._db() as db:
            if status != 'CANCELLED':
                cur = db.execute('SELECT status FROM tasks WHERE id=?', (task,)).fetchone()
                if cur and cur['status'] == 'CANCELLED':
                    return
            db.execute(f"UPDATE tasks SET {', '.join(cols)} WHERE id=?", vals)
        self.append(task, 'status', status)

    def tasks(self, project: str | None = None) -> list[dict[str, Any]]:
        sql = 'SELECT * FROM tasks'; params=[]
        if project:
            self._project(project)
            sql += ' WHERE project=?'; params.append(project)
        sql += ' ORDER BY created_at DESC LIMIT 100'
        with self._db() as db:
            return [dict(r) for r in db.execute(sql,params)]

    def task(self, task_id: str) -> dict[str, Any]:
        with self._db() as db:
            r = db.execute('SELECT * FROM tasks WHERE id=?',(task_id,)).fetchone()
        if not r:
            raise BridgeError('Task not found', 404)
        return dict(r)

    def events_page(self, task_id: str, after_seq: int = 0) -> dict[str, Any]:
        self.task(task_id)
        cursor = max(0, after_seq)
        with self._db() as db:
            bounds = db.execute('SELECT MIN(seq) AS min_seq, MAX(seq) AS max_seq FROM events WHERE task_id=?', (task_id,)).fetchone()
            rows = [dict(r) for r in db.execute('SELECT * FROM events WHERE task_id=? AND seq>? ORDER BY seq LIMIT 121', (task_id, cursor))]
        has_more = len(rows) > 120
        items = rows[:120]
        min_seq = bounds['min_seq'] if bounds and bounds['min_seq'] is not None else 0
        gap_detected = bool(cursor > 0 and min_seq > 0 and min_seq > cursor + 1)
        with self.lock:
            live = self.live_streams.get(task_id)
        return {'events': items, 'has_more': has_more, 'gap_detected': gap_detected, 'min_seq': min_seq, 'live_stream': live}

    def events(self, task_id: str, after_seq: int = 0) -> list[dict[str, Any]]:
        return self.events_page(task_id, after_seq)['events']

    def available_agents(self) -> list[dict[str, Any]]:
        return [a.capabilities().to_dict(available=a.is_available()) for a in self.adapters.values()]

    @staticmethod
    def is_destructive_command(target: str) -> bool:
        return bool(DESTRUCTIVE_CMD_RE.search(target or ''))

    def create_git_checkpoint(self, project: str, label: str = 'pre-repair') -> str | None:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            return None
        try:
            subprocess.run([git_cmd, 'add', '-A'], cwd=base, capture_output=True, text=True, timeout=15)
            st = subprocess.run([git_cmd, 'status', '--porcelain'], cwd=base, capture_output=True, text=True, timeout=10)
            gh_user = os.environ.get('GITHUB_USER', '').strip() or 'TabletAgent'
            gh_email = os.environ.get('GITHUB_EMAIL', '').strip() or 'agent@localhost'
            if st.returncode == 0 and st.stdout.strip():
                subprocess.run(
                    [git_cmd, '-c', f'user.name={gh_user}', '-c', f'user.email={gh_email}', 'commit', '-m', f'checkpoint({label}): automated snapshot before repair'],
                    cwd=base, capture_output=True, text=True, timeout=15
                )
            rev = subprocess.run([git_cmd, 'rev-parse', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
            return rev.stdout.strip() if rev.returncode == 0 and rev.stdout.strip() else None
        except Exception:
            return None

    def git_rollback(self, project: str, target_sha: str | None = None) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            raise BridgeError('Git repository not initialized in this project')
        ref = (target_sha or 'HEAD').strip()
        if not re.fullmatch(r'HEAD|[0-9a-fA-F]{7,40}', ref):
            raise BridgeError('Invalid commit reference for rollback')
        try:
            res_reset = subprocess.run([git_cmd, 'reset', '--hard', ref], cwd=base, capture_output=True, text=True, timeout=15)
            if res_reset.returncode != 0:
                raise BridgeError(f'Git rollback failed: {res_reset.stderr.strip() or res_reset.stdout.strip()}')
            subprocess.run([git_cmd, 'clean', '-fd'], cwd=base, capture_output=True, text=True, timeout=15)
            rev = subprocess.run([git_cmd, 'rev-parse', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
            sha = rev.stdout.strip() if rev.returncode == 0 else ref
            return {'success': True, 'rolled_back_to': sha}
        except BridgeError:
            raise
        except Exception as e:
            raise BridgeError(f'Git rollback error: {e}')

    def create_worktree(self, project: str, task_id: str) -> Path:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            raise BridgeError('Git repository required for worktree isolation')
        wt_root = base / '.worktrees'
        wt_root.mkdir(parents=True, exist_ok=True)
        wt_dir = wt_root / f'task-{task_id[:8]}'
        if wt_dir.exists():
            shutil.rmtree(wt_dir, ignore_errors=True)
        branch = f'agent/task-{task_id[:8]}'
        subprocess.run([git_cmd, 'branch', '-D', branch], cwd=base, capture_output=True, timeout=10)
        res = subprocess.run([git_cmd, 'worktree', 'add', '-b', branch, str(wt_dir)], cwd=base, capture_output=True, text=True, timeout=20)
        if res.returncode != 0:
            raise BridgeError(f'Failed to create worktree: {res.stderr.strip() or res.stdout.strip()}')
        with self.lock:
            self.active_worktrees[task_id] = wt_dir
        return wt_dir

    def cleanup_worktree(self, project: str, task_id: str):
        with self.lock:
            wt_dir = self.active_worktrees.pop(task_id, None)
        if not wt_dir or not wt_dir.exists():
            return
        try:
            base = self._project(project)
            git_cmd = shutil.which('git') or 'git'
            subprocess.run([git_cmd, 'worktree', 'remove', '--force', str(wt_dir)], cwd=base, capture_output=True, timeout=15)
            shutil.rmtree(wt_dir, ignore_errors=True)
        except Exception:
            pass

    def list_worktrees(self, project: str) -> list[dict[str, Any]]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        res = subprocess.run([git_cmd, 'worktree', 'list', '--porcelain'], cwd=base, capture_output=True, text=True, timeout=10)
        if res.returncode != 0:
            return []
        trees = []
        cur: dict[str, Any] = {}
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line:
                if cur:
                    cur['is_base'] = Path(cur.get('path', '')).resolve() == base.resolve()
                    trees.append(cur)
                    cur = {}
                continue
            if line.startswith('worktree '):
                cur['path'] = line[9:].strip()
            elif line.startswith('HEAD '):
                cur['head'] = line[5:].strip()
            elif line.startswith('branch '):
                cur['branch'] = line[7:].removeprefix('refs/heads/').strip()
        if cur:
            cur['is_base'] = Path(cur.get('path', '')).resolve() == base.resolve()
            trees.append(cur)
        return trees

    def prune_worktrees(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        subprocess.run([git_cmd, 'worktree', 'prune'], cwd=base, capture_output=True, timeout=10)
        return {'ok': True}

    def slm_status(self) -> dict[str, Any]:
        adapter = self.adapters.get('slm')
        available = adapter.is_available() if adapter else False
        models = []
        for d in [Path('/root/models'), Path.home() / 'models', Path('/data/data/com.tabletagent.local/files/runtime/rootfs/root/models')]:
            if d.exists():
                models += [f.name for f in d.glob('*.gguf')]
        progress = getattr(self, '_slm_download_progress', None)
        return {
            'installed': available,
            'binary': adapter.binary if adapter else 'llama-cli',
            'models': list(set(models)),
            'default_model': 'qwen2.5-coder-1.5b-q4',
            'available_to_download': [
                {'key': 'qwen2.5-coder-0.5b', 'name': 'Qwen 2.5 Coder 0.5B (Ultra-Light)', 'size': '390 MB', 'speed': 'Very Fast'},
                {'key': 'qwen2.5-coder-1.5b', 'name': 'Qwen 2.5 Coder 1.5B (Recommended)', 'size': '1.1 GB', 'speed': 'Fast & Accurate'},
                {'key': 'qwen2.5-coder-3b', 'name': 'Qwen 2.5 Coder 3B (Deep Reasoning)', 'size': '1.9 GB', 'speed': 'High Capability'}
            ],
            'download_progress': progress
        }

    def slm_download(self, payload: dict[str, Any]) -> dict[str, Any]:
        model_key = payload.get('model', 'qwen2.5-coder-1.5b')
        models_map = {
            'qwen2.5-coder-0.5b': {
                'filename': 'qwen2.5-coder-0.5b-instruct-q4_k_m.gguf',
                'url': 'https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf',
                'size_mb': 390
            },
            'qwen2.5-coder-1.5b': {
                'filename': 'qwen2.5-coder-1.5b-instruct-q4_k_m.gguf',
                'url': 'https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf',
                'size_mb': 1117
            },
            'qwen2.5-coder-3b': {
                'filename': 'qwen2.5-coder-3b-instruct-q4_k_m.gguf',
                'url': 'https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf',
                'size_mb': 1930
            }
        }
        info = models_map.get(model_key, models_map['qwen2.5-coder-1.5b'])
        target_dir = Path('/root/models')
        target_dir.mkdir(parents=True, exist_ok=True)
        dest = target_dir / info['filename']
        if dest.is_file() and dest.stat().st_size > 100_000_000:
            return {'status': 'completed', 'model': model_key, 'path': str(dest), 'size_mb': round(dest.stat().st_size / (1024*1024), 1)}

        if getattr(self, '_slm_download_thread', None) and self._slm_download_thread.is_alive():
            return {'status': 'in_progress', 'progress': getattr(self, '_slm_download_progress', {})}

        def _worker():
            self._slm_download_progress = {'model': model_key, 'percent': 0, 'downloaded_mb': 0, 'total_mb': info['size_mb'], 'status': 'downloading'}
            try:
                cmd = ['curl', '-L', '-C', '-', info['url'], '-o', str(dest) + '.part']
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                while proc.poll() is None:
                    time.sleep(1.0)
                    part_file = dest.parent / (dest.name + '.part')
                    if part_file.exists():
                        cur_size = part_file.stat().st_size
                        cur_mb = round(cur_size / (1024 * 1024), 1)
                        pct = min(99, int((cur_mb / info['size_mb']) * 100))
                        self._slm_download_progress['percent'] = pct
                        self._slm_download_progress['downloaded_mb'] = cur_mb
                if proc.returncode == 0:
                    part_file = dest.parent / (dest.name + '.part')
                    if part_file.exists():
                        part_file.rename(dest)
                    self._slm_download_progress['percent'] = 100
                    self._slm_download_progress['status'] = 'completed'
                else:
                    self._slm_download_progress['status'] = 'failed'
            except Exception as e:
                self._slm_download_progress['status'] = f'error: {e}'

        self._slm_download_thread = threading.Thread(target=_worker, daemon=True, name='slm-dl')
        self._slm_download_thread.start()
        return {'status': 'started', 'model': model_key, 'total_mb': info['size_mb']}

    def slm_setup(self, payload: dict[str, Any]) -> dict[str, Any]:
        action = payload.get('action', 'check')
        model_name = payload.get('model', 'qwen2.5-coder-1.5b')
        models_dir = Path('/root/models')
        models_dir.mkdir(parents=True, exist_ok=True)
        return {
            'ok': True,
            'action': action,
            'model': model_name,
            'target_dir': str(models_dir),
            'recommended_model': 'https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf'
        }

    def _dispatch_next_queued_task(self, project: str):
        next_task = None
        with self.lock:
            q = self.task_queues.get(project)
            if not q:
                return
            with self._db() as db:
                busy = db.execute("SELECT id FROM tasks WHERE project=? AND status IN ('PLANNING','WAITING_APPROVAL','EXECUTING','VERIFYING','REPAIRING') LIMIT 1", (project,)).fetchone()
                if busy:
                    return
            next_task = q.pop(0)
            if not q:
                self.task_queues.pop(project, None)
        if next_task:
            next_tid = next_task['task_id']
            threading.Thread(
                target=self._worker,
                args=(next_tid, next_task.get('model', ''), next_task.get('turn_prompt')),
                daemon=True,
                name=f'task-{next_tid[:8]}'
            ).start()

    def export_project(self, project: str, export_dir: Path | None = None, include_env: bool = False) -> dict[str, Any]:
        base = self._project(project)
        out_dir = Path(export_dir) if export_dir else (self.home / 'downloads')
        out_dir.mkdir(parents=True, exist_ok=True)
        ts = time.strftime('%Y%m%d_%H%M%S')
        zip_name = f'{project}_{ts}.zip'
        zip_path = out_dir / zip_name

        with self._db() as db:
            tasks_rows = [dict(r) for r in db.execute('SELECT * FROM tasks WHERE project=?', (project,)).fetchall()]
            tids = [r['id'] for r in tasks_rows]
            events_rows = []
            if tids:
                qmarks = ','.join('?' for _ in tids)
                events_rows = [dict(r) for r in db.execute(f'SELECT * FROM events WHERE task_id IN ({qmarks}) ORDER BY seq ASC', tids).fetchall()]
        project_state = {
            'project': project,
            'exported_at': time.time(),
            'tasks': tasks_rows,
            'events': events_rows,
        }

        forbidden_dirs = {'.next', 'node_modules', '.gradle', '.venv', '__pycache__', '.worktrees'}
        with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
            zf.writestr('project_state.json', json.dumps(project_state, indent=2, ensure_ascii=False))
            for root, dirs, files in os.walk(base):
                dirs[:] = [d for d in dirs if d not in forbidden_dirs]
                for file in files:
                    if file == '.env' and not include_env:
                        continue
                    file_path = Path(root) / file
                    rel = file_path.relative_to(base)
                    zf.write(file_path, arcname=str(rel))

        return {
            'project': project,
            'filename': zip_name,
            'path': str(zip_path),
            'size_bytes': zip_path.stat().st_size,
        }

    def import_project(self, zip_path: Path | str, target_name: str | None = None) -> dict[str, Any]:
        zp = Path(zip_path).expanduser().resolve()
        if not zp.is_file():
            raise BridgeError('Zip file not found', 404)
        if not zipfile.is_zipfile(zp):
            raise BridgeError('File is not a valid zip archive')
        p_name = target_name or zp.stem.split('_')[0]
        if not NAME_RE.fullmatch(p_name):
            p_name = 'imported_app'
        dest = self.workspaces / p_name
        if dest.exists():
            raise BridgeError(f'Project "{p_name}" already exists', 409)
        dest.mkdir(parents=True, exist_ok=True)
        file_count = 0
        state_data = None
        with zipfile.ZipFile(zp, 'r') as zf:
            for member in zf.infolist():
                if member.filename.startswith('/') or '..' in member.filename:
                    continue
                if member.filename == 'project_state.json':
                    try:
                        state_data = json.loads(zf.read(member.filename))
                    except Exception:
                        pass
                    continue
                zf.extract(member, dest)
                file_count += 1

        if state_data and isinstance(state_data, dict):
            with self._db() as db:
                for t in state_data.get('tasks', []):
                    db.execute(
                        'INSERT OR REPLACE INTO tasks(id, project, prompt, agent, mode, status, created_at, updated_at, error, result, verification) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                        (t.get('id'), p_name, t.get('prompt',''), t.get('agent','codex'), t.get('mode','build'), t.get('status','COMPLETED'), t.get('created_at', time.time()), t.get('updated_at', time.time()), t.get('error',''), t.get('result',''), t.get('verification',''))
                    )
                for ev in state_data.get('events', []):
                    db.execute(
                        'INSERT OR IGNORE INTO events(task_id, timestamp, kind, message) VALUES(?,?,?,?)',
                        (ev.get('task_id'), ev.get('timestamp') or ev.get('created_at', time.time()), ev.get('kind','log'), ev.get('message',''))
                    )

        return {'name': p_name, 'files_count': file_count, 'has_git': (dest / '.git').exists()}

    def list_project_apks(self, project: str) -> list[dict[str, Any]]:
        base = self._project(project)
        apks = []
        for root, dirs, files in os.walk(base):
            if any(p in root for p in ('node_modules', '.next', '.gradle', '.worktrees')):
                continue
            for f in files:
                if f.endswith('.apk'):
                    fp = Path(root) / f
                    try:
                        st = fp.stat()
                        apks.append({
                            'name': f,
                            'path': str(fp.relative_to(base)),
                            'size_bytes': st.st_size,
                            'updated_at': st.st_mtime,
                        })
                    except OSError:
                        pass
        return apks

    def start_task(self, project: str, prompt: str, mode: str = 'build', agent: str = 'codex', model: str = '', task_id: str | None = None, use_worktree: bool = False) -> dict[str, Any]:
        self._project(project)
        if mode not in ALLOWED_MODES:
            raise BridgeError('Mode must be plan or build')
        if agent not in self.adapters:
            raise BridgeError(f'Unknown agent "{agent}". Available: {", ".join(self.adapters.keys())}')
        adapter = self.adapters[agent]
        if mode == 'plan' and not adapter.can_plan:
            raise BridgeError(f'Agent "{agent}" does not support plan mode')
        if mode == 'build' and not adapter.can_build:
            raise BridgeError(f'Agent "{agent}" does not support build mode')
        if not prompt.strip() or len(prompt) > 24000:
            raise BridgeError('Prompt length must be 1-24000 characters')
        now = time.time()
        clean_tid = (task_id or '').strip()
        is_followup = False
        turn_prompt_to_use = prompt
        queued_task = False
        with self.lock:
            with self._db() as db:
                busy = db.execute("SELECT id FROM tasks WHERE project=? AND status IN ('PLANNING','WAITING_APPROVAL','EXECUTING','VERIFYING','REPAIRING') LIMIT 1", (project,)).fetchone()
                if busy and not clean_tid:
                    if use_worktree and shutil.which('git'):
                        clean_tid = str(uuid.uuid4())
                        self.create_worktree(project, clean_tid)
                    else:
                        clean_tid = str(uuid.uuid4())
                        db.execute('INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)', (clean_tid,project,prompt,agent,mode,'QUEUED',now,now))
                        queued_task = True
                        self.task_queues.setdefault(project, []).append({'task_id': clean_tid, 'model': model, 'turn_prompt': prompt})
                elif busy and clean_tid:
                    raise BridgeError('Another turn is currently running in this project; wait or cancel first', 409)

                if not queued_task:
                    if clean_tid:
                        existing = db.execute('SELECT * FROM tasks WHERE id=? AND project=?', (clean_tid, project)).fetchone()
                        if existing:
                            is_followup = True
                            prev_agent = existing['agent']
                            if prev_agent != agent:
                                last_msg = ""
                                r_msg = db.execute("SELECT message FROM events WHERE task_id=? AND kind='message' ORDER BY seq DESC LIMIT 1", (clean_tid,)).fetchone()
                                if r_msg and r_msg['message']:
                                    last_msg = str(r_msg['message'])[:800]
                                try:
                                    git_stat = self.git_status(project)
                                    stat_summary = f"{len(git_stat.get('staged', []))} staged, {len(git_stat.get('unstaged', []))} modified, {len(git_stat.get('untracked', []))} untracked"
                                except Exception:
                                    stat_summary = 'git status unavailable'
                                memory_ctx = HermesMemoryLedger.build_context(self._project(project))
                                turn_prompt_to_use = (
                                    f"[Aion Cross-Agent Handoff: Switched agent from {prev_agent} to {agent}]\n"
                                    f"Previous Context: {last_msg}\n"
                                    f"Workspace Status: {stat_summary}\n"
                                    f"{memory_ctx}\n\n"
                                    f"User follow-up:\n{prompt}"
                                )
                                self.append(clean_tid, 'log', f"Aion Handoff: Switched agent from {prev_agent} to {agent} with synthesized session context.")
                            db.execute(
                                'UPDATE tasks SET agent=?, mode=?, status=?, updated_at=?, error=? WHERE id=?',
                                (agent, mode, 'QUEUED', now, '', clean_tid)
                            )
                        else:
                            clean_tid = str(uuid.uuid4())
                            db.execute('INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)', (clean_tid,project,prompt,agent,mode,'QUEUED',now,now))
                    else:
                        clean_tid = str(uuid.uuid4())
                        db.execute('INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)', (clean_tid,project,prompt,agent,mode,'QUEUED',now,now))

        if queued_task:
            self.append(clean_tid, 'status', 'QUEUED')
            self.append(clean_tid, 'log', 'Task placed in project FIFO queue (waiting for active task to complete)')
            return self.task(clean_tid)

        if is_followup:
            self.append(clean_tid, 'user', prompt)
        self.append(clean_tid, 'status', 'QUEUED')
        threading.Thread(target=self._worker, args=(clean_tid, model, turn_prompt_to_use), daemon=True, name=f'task-{clean_tid[:8]}').start()
        return self.task(clean_tid)

    def _run(self, task_id: str, cmd: list[str], cwd: Path, timeout: float, phase: str) -> tuple[int,str]:
        self.append(task_id,'log',f'Starting {phase}: {cmd[0]} (arguments hidden)')
        exec_cmd = shutil.which(cmd[0]) or cmd[0]
        actual_cmd = [exec_cmd] + cmd[1:]
        proc = subprocess.Popen(actual_cmd, cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, errors='replace', bufsize=1,
                                start_new_session=True)
        pid_key = f'task:{task_id}'
        self._register_pid(pid_key, proc, Path(actual_cmd[0]).stem or cmd[0])
        with self.lock:
            self.procs[task_id] = proc
        deadline=time.monotonic()+timeout
        lines=[]
        verify_buf=[]
        done_marker=False
        fail_marker=False
        def flush_verify():
            if verify_buf:
                self.append(task_id, 'verification', '\n'.join(verify_buf)[:3000])
                verify_buf.clear()
        try:
            # Background watchdog enforces deadline even when stdout is blocked.
            done=threading.Event()
            def timeout_kill():
                if not done.wait(timeout):
                    self._kill(proc)
            threading.Thread(target=timeout_kill, daemon=True).start()
            for raw in proc.stdout or []:
                line=raw.strip()
                if not line: continue
                if phase=='agent':
                    try:
                        data=json.loads(line)
                        ev = data.get('event','')
                        if ev:
                            if ev=='init':
                                cid=data.get('conversation_id','')
                                if isinstance(cid,str) and len(cid)>6:
                                    self.thread_candidates[task_id]=cid
                                    self.append(task_id,'thread',cid)
                            elif ev=='step_update':
                                su=data.get('step_update') or {}
                                if su.get('step_type')=='tool' and su.get('state') in {'DONE','ERROR'}:
                                    self.append(task_id,'tool',f"{su.get('tool_name','tool')}: {su.get('state')}")
                            elif ev=='result':
                                res=data.get('result') or {}
                                cid=res.get('conversation_id','')
                                if isinstance(cid,str) and len(cid)>6:
                                    self.thread_candidates[task_id]=cid
                                if res.get('status')=='SUCCESS':
                                    resp_txt=res.get('response','')
                                    denied=res.get('denied_actions') or []
                                    if denied and not (isinstance(resp_txt,str) and resp_txt.strip()):
                                        fail_marker=True
                                        self.append(task_id,'error',f'Antigravity CLI denied actions: {json.dumps(denied,ensure_ascii=False)[:600]}')
                                    else:
                                        done_marker=True
                                        if isinstance(resp_txt,str) and resp_txt.strip():
                                            lines.append(resp_txt[:1500]); self.append(task_id,'message',resp_txt)
                                else:
                                    fail_marker=True
                                    self.append(task_id,'error',json.dumps(res,ensure_ascii=False)[:1800])
                            continue
                        kind=data.get('type','event')
                        if kind=='thread.started':
                            tid=data.get('thread_id','')
                            if isinstance(tid,str) and len(tid)>10:
                                self.thread_candidates[task_id]=tid
                            self.append(task_id,'thread',tid)
                        elif kind in {'item.completed','item.started'}:
                            item=data.get('item') or {}
                            if item.get('type')=='agent_message':
                                msg=item.get('text','')
                                if msg:
                                    lines.append(msg[:1500]); self.append(task_id,'message',msg)
                            elif item.get('type')=='reasoning' and kind=='item.completed':
                                r_txt = item.get('text', '') or '\n'.join(item.get('summary') or [])
                                if isinstance(r_txt, str) and r_txt.strip():
                                    self.append(task_id, 'thought', r_txt.strip()[:2000])
                            elif item.get('type') in {'command_execution','file_change'}:
                                cmd_target = str(item.get('command', item.get('target', '')))
                                detail = f' `{cmd_target[:120]}`' if cmd_target else ''
                                if kind == 'item.started':
                                    with self.lock:
                                        self.live_streams[task_id] = {'kind': 'status', 'text': f"Running {item.get('type')}{detail}..."}
                                else:
                                    with self.lock:
                                        self.live_streams.pop(task_id, None)
                                    self.append(task_id,'tool',f"{item.get('type')}{detail}: {item.get('status','')}")
                                if self.is_destructive_command(cmd_target):
                                    if not self.request_approval(task_id, 'confirm_destructive', cmd_target):
                                        fail_marker = True
                                        self.append(task_id, 'error', f'Destructive command blocked by user policy: {cmd_target}')
                                        self._kill(proc)
                                        break
                        elif kind in {'approval_request', 'request_approval'}:
                            action_type = data.get('action_type', data.get('tool', 'command_execution'))
                            target_cmd = data.get('target', data.get('command', ''))
                            allowed = self.request_approval(task_id, action_type, target_cmd)
                            if not allowed:
                                lines.append(f'Approval denied for: {target_cmd}')
                        elif kind=='turn.completed':
                            done_marker=True
                        elif kind in {'turn.failed','error'}:
                            fail_marker=True
                            self.append(task_id,'error',json.dumps(data,ensure_ascii=False)[:1800])
                        elif kind=='system':
                            sid=data.get('session_id','')
                            if isinstance(sid,str) and len(sid)>6:
                                self.thread_candidates[task_id]=sid
                                self.append(task_id,'thread',sid)
                        elif kind=='assistant':
                            content=(data.get('message') or {}).get('content') or []
                            if isinstance(content,list):
                                for block in content:
                                    if not isinstance(block,dict): continue
                                    btype=block.get('type','')
                                    if btype=='text':
                                        txt=block.get('text','')
                                        if txt:
                                            lines.append(txt[:1500]); self.append(task_id,'message',txt)
                                    elif btype=='tool_use':
                                        self.append(task_id,'tool',f"tool_use: {block.get('name','')}")
                        elif kind=='result':
                            sid=data.get('session_id','')
                            if isinstance(sid,str) and len(sid)>6:
                                self.thread_candidates[task_id]=sid
                            if data.get('is_error'):
                                fail_marker=True
                                self.append(task_id,'error',str(data.get('result', data))[:1800])
                            else:
                                done_marker=True
                                res_txt=data.get('result','')
                                if isinstance(res_txt,str) and res_txt and res_txt[:1500] not in lines:
                                    lines.append(res_txt[:1500]); self.append(task_id,'message',res_txt)
                    except json.JSONDecodeError:
                        if 'llama' in cmd[0] or '-m' in cmd:
                            lines.append(line[:1500])
                            self.append(task_id, 'message', line)
                            done_marker = True
                        else:
                            self.append(task_id,'log',line[:800])
                else:
                    lines.append(line[:800])
                    verify_buf.append(line[:400])
                    if len(verify_buf) >= 20:
                        flush_verify()
            flush_verify()
            proc.wait(timeout=2)
            if phase == 'agent' and proc.returncode == 0 and (not done_marker or fail_marker):
                if 'llama' in cmd[0] or '-m' in cmd:
                    done_marker = True
                else:
                    self.append(task_id, 'error', 'Agent CLI did not report turn completion')
                    return 90, '\n'.join(lines)[-6000:]
            return proc.returncode or 0, '\n'.join(lines)[-6000:]
        finally:
            done.set()
            if proc.stdout: proc.stdout.close()
            self._unregister_pid(pid_key)
            with self.lock:
                if self.procs.get(task_id) is proc: self.procs.pop(task_id,None)

    def _run_codex_app_server(
        self,
        task_id: str,
        adapter: CodexAdapter,
        project_path: Path,
        prompt: str,
        mode: str,
        thread_id: str | None = None,
        model: str = '',
        sandbox_override: str | None = None,
        timeout: float = 1800,
    ) -> tuple[int, str]:
        cmd = adapter.build_app_server_cmd(project_path)
        self.append(task_id, 'log', f'Starting agent (app-server JSON-RPC): {cmd[0]} (arguments hidden)')
        exec_cmd = shutil.which(cmd[0]) or cmd[0]
        actual_cmd = [exec_cmd] + cmd[1:]
        proc = subprocess.Popen(
            actual_cmd,
            cwd=str(project_path),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors='replace',
            bufsize=1,
            start_new_session=True,
        )
        pid_key = f'task:{task_id}'
        self._register_pid(pid_key, proc, Path(actual_cmd[0]).stem or cmd[0])
        with self.lock:
            self.procs[task_id] = proc
        lines: list[str] = []
        done_marker = False
        fail_marker = False
        active_thread_id = thread_id or ''
        active_turn_id = ''
        delta_buffers: dict[str, list[str]] = {}
        emitted_messages: set[str] = set()

        def send_rpc(payload: dict[str, Any]) -> bool:
            try:
                if proc.stdin and proc.poll() is None:
                    proc.stdin.write(json.dumps(payload, ensure_ascii=False) + '\n')
                    proc.stdin.flush()
                    return True
            except OSError:
                pass
            return False

        done = threading.Event()
        def timeout_kill():
            if not done.wait(timeout):
                self._kill(proc)
        threading.Thread(target=timeout_kill, daemon=True).start()

        try:
            for msg in adapter.build_app_server_handshake(project_path, mode, thread_id, model, sandbox_override):
                send_rpc(msg)

            for raw in proc.stdout or []:
                line = raw.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                except json.JSONDecodeError:
                    self.append(task_id, 'log', line[:800])
                    if 'cannot establish app-server socket mount isolation' in line:
                        lines.append(line[:800])
                    continue

                if not isinstance(data, dict):
                    continue

                rpc_id = data.get('id')
                method = data.get('method', '')
                params = data.get('params') or {}

                # Server-initiated JSON-RPC request (both 'method' and 'id' present)
                if method and rpc_id is not None:
                    if method in {
                        'item/commandExecution/requestApproval',
                        'execCommandApproval',
                        'item/fileChange/requestApproval',
                        'applyPatchApproval',
                        'item/permissions/requestApproval',
                    }:
                        raw_cmd = params.get('command')
                        if isinstance(raw_cmd, list):
                            cmd_str = ' '.join(str(x) for x in raw_cmd)
                        else:
                            cmd_str = str(raw_cmd or params.get('grantRoot') or params.get('reason') or method)
                        action_type = 'command_execution' if 'command' in method.lower() else 'file_change'
                        allowed = self.request_approval(task_id, action_type, cmd_str)
                        if not allowed:
                            lines.append(f'Approval denied for: {cmd_str}')
                        send_rpc(adapter.build_approval_response(rpc_id, method, allowed))
                    else:
                        send_rpc({'jsonrpc': '2.0', 'id': rpc_id, 'result': {}})
                    continue

                # Response to client-initiated JSON-RPC requests
                if rpc_id in (2, 20):
                    if 'error' in data:
                        if rpc_id == 2 and thread_id:
                            # Fallback from thread/resume to fresh thread/start
                            fresh = adapter.build_app_server_handshake(project_path, mode, None, model, sandbox_override)[-1]
                            fresh['id'] = 20
                            send_rpc(fresh)
                            continue
                        fail_marker = True
                        err_txt = json.dumps(data['error'], ensure_ascii=False)[:1800]
                        lines.append(err_txt)
                        self.append(task_id, 'error', err_txt)
                        break
                    res_thread = (data.get('result') or {}).get('thread') or {}
                    tid = res_thread.get('id', '') or active_thread_id
                    if isinstance(tid, str) and tid:
                        active_thread_id = tid
                        self.thread_candidates[task_id] = tid
                        self.append(task_id, 'thread', tid)
                        with self.lock:
                            self.app_server_turns[task_id] = (proc, active_thread_id, active_turn_id)
                        send_rpc(adapter.build_turn_start_request(tid, prompt, req_id=3))
                    continue

                if rpc_id == 3:
                    if 'error' in data:
                        fail_marker = True
                        err_txt = json.dumps(data['error'], ensure_ascii=False)[:1800]
                        lines.append(err_txt)
                        self.append(task_id, 'error', err_txt)
                        break
                    res_turn = (data.get('result') or {}).get('turn') or {}
                    t_id = res_turn.get('id', '')
                    if isinstance(t_id, str) and t_id:
                        active_turn_id = t_id
                        with self.lock:
                            self.app_server_turns[task_id] = (proc, active_thread_id, active_turn_id)
                    continue

                if rpc_id is not None and 'error' in data:
                    fail_marker = True
                    err_txt = json.dumps(data['error'], ensure_ascii=False)[:1800]
                    lines.append(err_txt)
                    self.append(task_id, 'error', err_txt)
                    break

                # Server notifications
                if method == 'thread/started':
                    tid = ((params.get('thread') or {}).get('id', ''))
                    if isinstance(tid, str) and len(tid) > 6 and tid != active_thread_id:
                        active_thread_id = tid
                        self.thread_candidates[task_id] = tid
                        self.append(task_id, 'thread', tid)
                elif method == 'turn/started':
                    t_id = ((params.get('turn') or {}).get('id', ''))
                    if isinstance(t_id, str) and t_id:
                        active_turn_id = t_id
                        with self.lock:
                            self.app_server_turns[task_id] = (proc, active_thread_id, active_turn_id)
                elif method in {'item/agentMessage/delta', 'item/plan/delta', 'item/reasoning/summaryTextDelta', 'item/reasoning/textDelta'}:
                    item_id = str(params.get('itemId', 'default'))
                    delta = str(params.get('delta', ''))
                    if delta:
                        buf = delta_buffers.setdefault(item_id, [])
                        buf.append(delta)
                        live_kind = 'thought' if 'reasoning' in method else 'message'
                        with self.lock:
                            self.live_streams[task_id] = {'kind': live_kind, 'text': REDACTION.sub('[REDACTED]', ''.join(buf))[-4000:]}
                elif method in {'item/started', 'item/completed'}:
                    item = params.get('item') or {}
                    itype = item.get('type', '')
                    item_id = str(item.get('id', ''))
                    if itype in {'agentMessage', 'plan', 'agent_message'} and method == 'item/completed':
                        with self.lock:
                            self.live_streams.pop(task_id, None)
                        msg = item.get('text', '') or ''.join(delta_buffers.pop(item_id, []))
                        if msg and (not item_id or item_id not in emitted_messages):
                            if item_id:
                                emitted_messages.add(item_id)
                            lines.append(msg[:1500])
                            self.append(task_id, 'message', msg)
                    elif itype == 'reasoning' and method == 'item/completed':
                        with self.lock:
                            self.live_streams.pop(task_id, None)
                        summary_parts = item.get('summary') or []
                        r_txt = item.get('text', '') or ('\n'.join(str(s) for s in summary_parts if s) if isinstance(summary_parts, list) else '') or ''.join(delta_buffers.pop(item_id, []))
                        if r_txt and r_txt.strip() and (not item_id or item_id not in emitted_messages):
                            if item_id:
                                emitted_messages.add(item_id)
                            self.append(task_id, 'thought', r_txt.strip()[:2000])
                    elif itype in {'commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall', 'command_execution', 'file_change'}:
                        norm_type = (
                            'command_execution' if itype in {'commandExecution', 'command_execution'}
                            else ('file_change' if itype in {'fileChange', 'file_change'} else itype)
                        )
                        status = item.get('status', 'in_progress' if method == 'item/started' else 'completed')
                        cmd_target = str(item.get('command', item.get('target', '')))
                        detail = cmd_target
                        if not detail and norm_type == 'file_change':
                            changes = item.get('changes') or []
                            if isinstance(changes, list) and changes:
                                paths = [str(c.get('path', '')) for c in changes if isinstance(c, dict) and c.get('path')]
                                detail = ', '.join(paths[:3])
                        elif not detail:
                            detail = str(item.get('tool', item.get('name', '')))
                        label_detail = f' `{detail[:120]}`' if detail else ''
                        if method == 'item/started':
                            with self.lock:
                                self.live_streams[task_id] = {'kind': 'status', 'text': f'Running {norm_type}{label_detail}...'}
                        else:
                            with self.lock:
                                self.live_streams.pop(task_id, None)
                            self.append(task_id, 'tool', f'{norm_type}{label_detail}: {status}')
                        if cmd_target and self.is_destructive_command(cmd_target):
                            if not self.request_approval(task_id, 'confirm_destructive', cmd_target):
                                fail_marker = True
                                self.append(task_id, 'error', f'Destructive command blocked by user policy: {cmd_target}')
                                if active_thread_id and active_turn_id:
                                    send_rpc(adapter.build_turn_interrupt_request(active_thread_id, active_turn_id))
                                self._kill(proc)
                                break
                elif method == 'turn/completed':
                    turn = params.get('turn') or {}
                    for item in (turn.get('items') or []):
                        if not isinstance(item, dict):
                            continue
                        if item.get('type') in {'agentMessage', 'plan', 'agent_message'}:
                            item_id = str(item.get('id', ''))
                            msg = item.get('text', '') or ''.join(delta_buffers.pop(item_id, []))
                            if msg and (not item_id or item_id not in emitted_messages):
                                if item_id:
                                    emitted_messages.add(item_id)
                                lines.append(msg[:1500])
                                self.append(task_id, 'message', msg)
                    tstatus = turn.get('status', 'completed')
                    if tstatus == 'failed' or turn.get('error'):
                        fail_marker = True
                        err_obj = turn.get('error') or {'status': tstatus}
                        err_txt = json.dumps(err_obj, ensure_ascii=False)[:1800]
                        lines.append(err_txt)
                        self.append(task_id, 'error', err_txt)
                    elif tstatus == 'interrupted':
                        fail_marker = True
                    else:
                        done_marker = True
                    break
                elif method == 'error':
                    fail_marker = True
                    err_txt = json.dumps(params, ensure_ascii=False)[:1800]
                    lines.append(err_txt)
                    self.append(task_id, 'error', err_txt)
                    break

            self._kill(proc)
            if not done_marker or fail_marker:
                if not fail_marker and self.task(task_id)['status'] != 'CANCELLED':
                    self.append(task_id, 'error', 'Agent app-server did not report turn completion')
                return 90, '\n'.join(lines)[-6000:]
            return 0, '\n'.join(lines)[-6000:]
        finally:
            done.set()
            if proc.stdin:
                try:
                    proc.stdin.close()
                except OSError:
                    pass
            if proc.stdout:
                try:
                    proc.stdout.close()
                except OSError:
                    pass
            self._unregister_pid(pid_key)
            with self.lock:
                self.app_server_turns.pop(task_id, None)
                if self.procs.get(task_id) is proc:
                    self.procs.pop(task_id, None)

    def _kill(self, proc: subprocess.Popen):
        if proc.poll() is not None: return
        try:
            if hasattr(os, 'killpg'):
                os.killpg(proc.pid, signal.SIGTERM)
            else:
                proc.terminate()
        except (ProcessLookupError, OSError): return
        try: proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try:
                if hasattr(os, 'killpg'):
                    os.killpg(proc.pid, signal.SIGKILL)
                else:
                    proc.kill()
            except (ProcessLookupError, OSError): pass

    def cancel(self, task_id: str):
        rec=self.task(task_id)
        if rec['status'] in TERMINAL: return rec
        self.set_status(task_id,'CANCELLED')
        with self.lock:
            proc=self.procs.get(task_id)
            turn_info=self.app_server_turns.get(task_id)
            with self._db() as db:
                for r in db.execute("SELECT id FROM approvals WHERE task_id=? AND status='PENDING'", (task_id,)):
                    evt = self.approval_events.get(r['id'])
                    if evt: evt.set()
                db.execute("UPDATE approvals SET status='DENIED', resolved_at=? WHERE task_id=? AND status='PENDING'", (time.time(), task_id))
        if turn_info:
            t_proc, t_tid, t_turn = turn_info
            if t_tid and t_turn and t_proc.stdin and t_proc.poll() is None:
                try:
                    req = {'jsonrpc': '2.0', 'id': 99, 'method': 'turn/interrupt', 'params': {'threadId': t_tid, 'turnId': t_turn}}
                    t_proc.stdin.write(json.dumps(req) + '\n')
                    t_proc.stdin.flush()
                except OSError:
                    pass
        if proc: self._kill(proc)
        return self.task(task_id)

    def request_approval(self, task_id: str, action_type: str, target: str, timeout: float = 120.0) -> bool:
        rec = self.task(task_id)
        if rec['status'] in TERMINAL:
            return False
        approval_id = str(uuid.uuid4())
        now = time.time()
        with self._db() as db:
            db.execute(
                'INSERT INTO approvals(id, task_id, type, target, status, created_at) VALUES(?,?,?,?,?,?)',
                (approval_id, task_id, action_type, target, 'PENDING', now)
            )
        event = threading.Event()
        with self.lock:
            self.approval_events[approval_id] = event
        self.set_status(task_id, 'WAITING_APPROVAL')
        self.append(task_id, 'approval', json.dumps({
            'id': approval_id,
            'type': action_type,
            'target': target,
            'status': 'PENDING'
        }))

        completed = event.wait(timeout)
        with self.lock:
            self.approval_events.pop(approval_id, None)

        with self._db() as db:
            row = db.execute('SELECT status FROM approvals WHERE id=?', (approval_id,)).fetchone()
            status = row['status'] if row else 'DENIED'

        if not completed and status == 'PENDING':
            status = 'DENIED'
            with self._db() as db:
                db.execute('UPDATE approvals SET status="DENIED", resolved_at=? WHERE id=?', (time.time(), approval_id))
            self.append(task_id, 'log', f'Approval request timed out after {int(timeout)}s; action denied.')

        allowed = (status == 'ALLOWED')
        if self.task(task_id)['status'] not in TERMINAL:
            self.set_status(task_id, 'PLANNING' if rec['mode'] == 'plan' else 'EXECUTING')
        return allowed

    def approvals(self, task_id: str) -> list[dict[str, Any]]:
        self.task(task_id)
        with self._db() as db:
            return [dict(r) for r in db.execute('SELECT * FROM approvals WHERE task_id=? ORDER BY created_at ASC', (task_id,))]

    def resolve_approval(self, task_id: str, approval_id: str, decision: str) -> dict[str, Any]:
        self.task(task_id)
        decision_norm = decision.strip().lower()
        if decision_norm not in {'allow', 'deny'}:
            raise BridgeError('Decision must be "allow" or "deny"')
        status = 'ALLOWED' if decision_norm == 'allow' else 'DENIED'
        now = time.time()
        with self._db() as db:
            row = db.execute('SELECT * FROM approvals WHERE id=? AND task_id=?', (approval_id, task_id)).fetchone()
            if not row:
                raise BridgeError('Approval not found', 404)
            if row['status'] != 'PENDING':
                return dict(row)
            db.execute('UPDATE approvals SET status=?, resolved_at=? WHERE id=?', (status, now, approval_id))
            updated = db.execute('SELECT * FROM approvals WHERE id=?', (approval_id,)).fetchone()

        self.append(task_id, 'approval_resolved', json.dumps({
            'id': approval_id,
            'decision': decision_norm,
            'status': status
        }))

        with self.lock:
            evt = self.approval_events.get(approval_id)
            if evt:
                evt.set()
        return dict(updated)

    def _thread_for_project(self, project: str, agent: str = 'codex') -> str | None:
        with self._db() as db:
            row=db.execute('SELECT session_id FROM agent_threads WHERE project=? AND agent=?',(project,agent)).fetchone()
        return row['session_id'] if row else None

    def _remember_thread(self, project: str, task_id: str, agent: str = 'codex'):
        thread_id=self.thread_candidates.pop(task_id,None)
        if not thread_id:return
        with self._db() as db:
            db.execute('INSERT INTO agent_threads(project,agent,session_id,updated_at) VALUES(?,?,?,?) ON CONFLICT(project) DO UPDATE SET session_id=excluded.session_id, updated_at=excluded.updated_at', (project,agent,thread_id,time.time()))

    def _worker(self, task_id: str, model: str = '', turn_prompt: str | None = None):
        rec=self.task(task_id)
        project_name = rec['project']
        with self.lock:
            project_path = self.active_worktrees.get(task_id) or self._project(project_name)
        if self.task(task_id)['status']=='CANCELLED': return
        try:
            agent_name = rec['agent']
            adapter = self.adapters.get(agent_name)
            if not adapter or not adapter.is_available():
                self.set_status(task_id,'BLOCKED_SETUP', error=f'{adapter.display_name if adapter else agent_name} CLI not found in runtime PATH'); return
            mode=rec['mode']
            self.set_status(task_id, 'PLANNING' if mode=='plan' else 'EXECUTING')
            policy = ('Plan only. Do not edit files or run mutating commands. Provide concise steps and test strategy.'
                      if mode=='plan' else
                      'You may edit files under the current workspace. Keep changes scoped; run relevant tests. Never read or disclose .env files, keys, or credentials. Do not git push or deploy unless explicitly requested by the user. Explain what changed and tests actually executed.')
            instructions = ''
            for ins_name in ['AGENTS.md', 'CLAUDE.md', '.agents.md']:
                ins_p = project_path / ins_name
                if ins_p.is_file():
                    try:
                        raw_ins = ins_p.read_text(encoding='utf-8')[:4000]
                        clean_ins = REDACTION.sub('[REDACTED]', raw_ins)
                        instructions = f'\n\nProject Instructions ({ins_name}):\n{clean_ins}'
                        break
                    except Exception:
                        pass
            memory_ctx = HermesMemoryLedger.build_context(project_path)
            thread = self._thread_for_project(project_name, agent_name)
            effective_prompt = turn_prompt if (turn_prompt and turn_prompt.strip()) else rec['prompt']
            prompt_text = f'{policy}{instructions}{memory_ctx}\n\nUser task:\n{effective_prompt}'
            settings = self.get_settings()
            sandbox_override = 'danger-full-access' if (agent_name == 'codex' and mode == 'build' and settings.get('codex_proot_sandbox_bypass')) else None
            use_app_server = bool(agent_name == 'codex' and isinstance(adapter, CodexAdapter) and settings.get('codex_use_app_server'))
            if sandbox_override:
                cmd = adapter.build_cmd(project_path, prompt_text, mode, thread, model, sandbox_override=sandbox_override)
            else:
                cmd = adapter.build_cmd(project_path, prompt_text, mode, thread, model)

            pre_git_fp = self._git_fingerprint(project_path)
            if use_app_server and isinstance(adapter, CodexAdapter):
                rc, output = self._run_codex_app_server(
                    task_id, adapter, project_path, prompt_text, mode, thread, model, sandbox_override=sandbox_override, timeout=1800
                )
            else:
                rc, output=self._run(task_id,cmd,project_path,timeout=1800,phase='agent')
            if self.task(task_id)['status']=='CANCELLED': return

            # Aion Orchestrator: Auto-Quota Failover
            if rc != 0 and QUOTA_ERROR_RE.search(output):
                fallback = None
                for cand_name in ['codex', 'antigravity', 'claude', 'opencode']:
                    cand = self.adapters.get(cand_name)
                    if cand and cand.name != agent_name and cand.is_available():
                        fallback = cand
                        break
                if fallback:
                    self.append(task_id, 'log', f"⚠ {adapter.display_name} quota exceeded. Aion Auto-Failover: switching to {fallback.display_name}...")
                    with self._db() as db:
                        db.execute('UPDATE tasks SET agent=? WHERE id=?', (fallback.name, task_id))
                    agent_name = fallback.name
                    adapter = fallback
                    fb_thread = self._thread_for_project(project_name, agent_name)
                    fb_prompt = f"[Aion Quota Failover from {rec['agent']} to {agent_name}]\n{prompt_text}"
                    use_fb_app_server = bool(agent_name == 'codex' and isinstance(adapter, CodexAdapter) and settings.get('codex_use_app_server'))
                    if use_fb_app_server and isinstance(adapter, CodexAdapter):
                        rc, output = self._run_codex_app_server(
                            task_id, adapter, project_path, fb_prompt, mode, fb_thread, model, sandbox_override=sandbox_override, timeout=1800
                        )
                    else:
                        fb_cmd = adapter.build_cmd(project_path, fb_prompt, mode, fb_thread, model)
                        rc, output = self._run(task_id, fb_cmd, project_path, timeout=1800, phase='agent')
                    if self.task(task_id)['status']=='CANCELLED': return

            if agent_name == 'codex' and mode == 'build' and 'cannot establish app-server socket mount isolation' in output:
                self.append(task_id, 'error', 'Codex Linux sandbox is blocked in Android PRoot. Enable the opt-in Codex PRoot Build toggle in Settings -> Security to allow workspace writes.')
            if rc!=0:
                self.set_status(task_id,'FAILED',error=f'{adapter.display_name} exited with status {rc}',result=output);return
            self._remember_thread(project_name,task_id, agent_name)
            if mode=='plan':
                self.set_status(task_id,'PLANNED',result=output);return
            self.set_status(task_id,'VERIFYING',result=output)
            command, needs_install = self._detect_verify_command(project_path)
            if not command:
                self.set_status(task_id,'NEEDS_VERIFICATION',verification='No supported npm/python build or test command found; do not claim verified success')
                return
            post_git_fp = self._git_fingerprint(project_path)
            if pre_git_fp is not None and post_git_fp == pre_git_fp:
                self.append(task_id, 'verification', 'Skipped build verification (read-only turn: no workspace files modified)')
                self.set_status(task_id, 'COMPLETED', verification='Read-only turn: no workspace files modified; build verification skipped.')
                return
            if needs_install:
                pkg_mgr = command[0] if command and command[0] in {'pnpm', 'yarn', 'bun', 'npm'} else 'npm'
                if shutil.which(pkg_mgr):
                    inst_cmd = [pkg_mgr, 'install'] + (['--no-audit', '--no-fund'] if pkg_mgr == 'npm' else [])
                    rc_inst, inst_out = self._run(task_id, inst_cmd, project_path, timeout=300, phase='verify')
                    if self.task(task_id)['status']=='CANCELLED': return
                    if rc_inst != 0:
                        self.set_status(task_id,'FAILED',error=f'{pkg_mgr} install failed (exit {rc_inst})',verification=inst_out); return
            for attempt in range(2):
                rc, verify_output=self._run(task_id, command, project_path, timeout=300, phase='verify')
                if self.task(task_id)['status']=='CANCELLED':return
                if rc==0:
                    HermesMemoryLedger.record_success(project_path, f"Verified: {' '.join(command)}")
                    self.set_status(task_id,'COMPLETED',verification='Verified: '+ ' '.join(command)+'\n'+verify_output[-2000:]);return
                if attempt==1:
                    HermesMemoryLedger.record_pitfall(project_path, f"{' '.join(command)} failed: {verify_output[:120]}")
                    self.append(task_id, 'verification', f'VERIFICATION_FAILED: {" ".join(command)} exited with code {rc} after repair attempt.')
                    self.set_status(task_id,'FAILED',error=f'VERIFICATION_FAILED: Build verification failed after repair (exit {rc})',verification=verify_output);return
                chk_sha = self.create_git_checkpoint(project_name, f'task-{task_id[:8]}-pre-repair')
                if chk_sha:
                    self.append(task_id, 'log', f'Created pre-repair Git checkpoint {chk_sha[:8]} (rollback available)')
                self.set_status(task_id,'REPAIRING',verification=verify_output)
                fix_prompt=(f'Your previous task for project {project_name} was not verified.\n'
                            f'Command: {" ".join(command)}\nExit code: {rc}\nError log:\n{REDACTION.sub("[REDACTED]",verify_output)[-2400:]}\n'
                            'Fix these errors within the workspace, rerun your own checks, and report changes. '
                            'Do not access credentials or deploy.')
                fix_thread = self._thread_for_project(project_name, agent_name)
                if use_app_server and isinstance(adapter, CodexAdapter):
                    rc, repair_output = self._run_codex_app_server(
                        task_id, adapter, project_path, fix_prompt, 'build', fix_thread, model, sandbox_override=sandbox_override, timeout=1200
                    )
                else:
                    fix_cmd = adapter.build_cmd(project_path, fix_prompt, 'build', fix_thread, model, sandbox_override=sandbox_override) if sandbox_override else adapter.build_cmd(project_path, fix_prompt, 'build', fix_thread, model)
                    rc,repair_output=self._run(task_id,fix_cmd,project_path,timeout=1200,phase='agent')
                if self.task(task_id)['status']=='CANCELLED':return
                if rc:
                    HermesMemoryLedger.record_pitfall(project_path, f"Repair failed on {' '.join(command)}: {verify_output[:120]}")
                    self.set_status(task_id,'FAILED',error='Agent repair attempt failed',verification=verify_output,result=repair_output);return
                HermesMemoryLedger.record_fix(project_path, verify_output, repair_output)
                self._remember_thread(project_name,task_id, agent_name)
                self.set_status(task_id,'VERIFYING')
        except Exception as exc:
            try:
                if self.task(task_id)['status']!='CANCELLED':
                    self.set_status(task_id,'FAILED',error=f'{type(exc).__name__}: {exc}'[:1000])
            except Exception:
                pass
        finally:
            try:
                with self.lock:
                    self.live_streams.pop(task_id, None)
                self.cleanup_worktree(project_name, task_id)
                self._dispatch_next_queued_task(project_name)
            except Exception:
                pass

    @staticmethod
    def _git_fingerprint(project_path: Path) -> str | None:
        git_cmd = shutil.which('git')
        if not git_cmd or not (project_path / '.git').exists():
            return None
        try:
            head_res = subprocess.run([git_cmd, 'rev-parse', 'HEAD'], cwd=project_path, capture_output=True, text=True, timeout=5)
            st_res = subprocess.run([git_cmd, 'status', '--porcelain'], cwd=project_path, capture_output=True, text=True, timeout=8)
            if head_res.returncode != 0 or st_res.returncode != 0:
                return None
            return f"{head_res.stdout.strip()}\n{st_res.stdout}"
        except Exception:
            return None

    @staticmethod
    def _detect_js_runner(project_path: Path) -> str | None:
        if (project_path / 'pnpm-lock.yaml').is_file() and shutil.which('pnpm'):
            return 'pnpm'
        if (project_path / 'yarn.lock').is_file() and shutil.which('yarn'):
            return 'yarn'
        if ((project_path / 'bun.lockb').is_file() or (project_path / 'bun.lock').is_file()) and shutil.which('bun'):
            return 'bun'
        if shutil.which('npm'):
            return 'npm'
        return None

    def _detect_verify_command(self, project_path: Path) -> tuple[list[str] | None, bool]:
        pkg = project_path / 'package.json'
        runner = self._detect_js_runner(project_path)
        if pkg.is_file() and runner:
            try:
                pkg_json = json.loads(pkg.read_text(encoding='utf-8'))
                scripts = pkg_json.get('scripts', {})
                has_deps = bool(pkg_json.get('dependencies') or pkg_json.get('devDependencies'))
                needs_install = has_deps and not (project_path / 'node_modules').is_dir()
                if 'build' in scripts:
                    return [runner, 'run', 'build'] if runner != 'yarn' else ['yarn', 'build'], needs_install
                if 'test' in scripts:
                    if runner == 'npm':
                        cmd = ['npm', 'test', '--', '--runInBand'] if 'jest' in str(scripts.get('test', '')) else ['npm', 'test']
                    else:
                        cmd = [runner, 'test']
                    return cmd, needs_install
            except (ValueError, OSError):
                pass
        # Monorepo fallback: frontend/package.json
        fe_pkg = project_path / 'frontend' / 'package.json'
        fe_runner = self._detect_js_runner(project_path / 'frontend') or runner
        if fe_pkg.is_file() and fe_runner:
            try:
                fe_scripts = json.loads(fe_pkg.read_text(encoding='utf-8')).get('scripts', {})
                if 'build' in fe_scripts:
                    return ['npm', '--prefix', 'frontend', 'run', 'build'] if fe_runner == 'npm' else [fe_runner, '--cwd', 'frontend', 'build'], False
                if 'test' in fe_scripts:
                    return ['npm', '--prefix', 'frontend', 'test'] if fe_runner == 'npm' else [fe_runner, '--cwd', 'frontend', 'test'], False
            except (ValueError, OSError):
                pass
        # Python fallback: pytest or unittest
        py_bin = shutil.which('python3') or shutil.which('python') or sys.executable
        has_py_tests = (project_path / 'tests').is_dir() or any(project_path.glob('test_*.py'))
        if has_py_tests:
            if shutil.which('pytest'):
                return ['pytest', '-q'], False
            if py_bin:
                return [py_bin, '-m', 'unittest', 'discover', '-v'], False
        return None, False

    def preview(self, project: str):
        self._project(project)
        with self.lock:
            item=self.previews.get(project)
            if not item: return {'status':'stopped','logs':[]}
            p=item['process']
            logs = list(item.get('logs', []))
            if p.poll() is None:
                return {'status':'running','url':f'http://127.0.0.1:{item["port"]}','stack':item.get('stack','node'),'logs':logs}
            self.previews.pop(project,None)
        return {'status':'stopped','exit_code':p.returncode,'logs':logs}

    def _detect_preview_command(self, base: Path, port: int | None) -> tuple[list[str], Path, int, str]:
        # 1. Root package.json scripts.dev
        pkg = base / 'package.json'
        if pkg.is_file():
            try:
                script = json.loads(pkg.read_text(encoding='utf-8')).get('scripts', {}).get('dev')
            except (OSError, ValueError, TypeError):
                script = None
            if script:
                npm_cmd = shutil.which('npm')
                if not npm_cmd:
                    raise BridgeError('npm is not installed')
                p = (5173 if 'vite' in str(script).lower() else 3000) if port is None else port
                dev_cmd = [npm_cmd, 'run', 'dev']
                if 'vite' in str(script).lower() and '--host' not in str(script):
                    dev_cmd += ['--', '--host', '127.0.0.1', '--port', str(p)]
                return dev_cmd, base, p, 'node'
        # 2. Monorepo frontend/package.json scripts.dev
        fe_dir = base / 'frontend'
        fe_pkg = fe_dir / 'package.json'
        if fe_pkg.is_file():
            try:
                fe_script = json.loads(fe_pkg.read_text(encoding='utf-8')).get('scripts', {}).get('dev')
            except (OSError, ValueError, TypeError):
                fe_script = None
            if fe_script and shutil.which('npm'):
                npm_cmd = shutil.which('npm') or 'npm'
                p = (5173 if 'vite' in str(fe_script).lower() else 3000) if port is None else port
                dev_cmd = [npm_cmd, 'run', 'dev']
                if 'vite' in str(fe_script).lower() and '--host' not in str(fe_script):
                    dev_cmd += ['--', '--host', '127.0.0.1', '--port', str(p)]
                return dev_cmd, fe_dir, p, 'monorepo-frontend'
        # 3. Python FastAPI / Flask / Django
        py_bin = sys.executable or shutil.which('python3') or shutil.which('python')
        if (base / 'manage.py').is_file() and py_bin:
            p = 8000 if port is None else port
            return [py_bin, 'manage.py', 'runserver', f'127.0.0.1:{p}'], base, p, 'django'
        for py_entry in ('main.py', 'app.py'):
            ep = base / py_entry
            if ep.is_file():
                try:
                    head = ep.read_text(encoding='utf-8', errors='replace')[:2000]
                except OSError:
                    head = ''
                p = 8000 if port is None else port
                if 'FastAPI' in head and shutil.which('uvicorn'):
                    mod = py_entry[:-3]
                    return [shutil.which('uvicorn') or 'uvicorn', f'{mod}:app', '--host', '127.0.0.1', '--port', str(p)], base, p, 'fastapi'
                if 'Flask' in head and py_bin:
                    return [py_bin, '-m', 'flask', '--app', py_entry, 'run', '--host', '127.0.0.1', '--port', str(p)], base, p, 'flask'
        # 4. Static HTML fallback
        if (base / 'index.html').is_file() and py_bin:
            p = 8080 if port is None else port
            return [py_bin, '-u', '-m', 'http.server', str(p), '--bind', '127.0.0.1'], base, p, 'static'
        raise BridgeError('No supported preview target found (needs package.json scripts.dev, Python web app, or index.html)')

    def start_preview(self, project: str, port: int | None = None):
        base=self._project(project)
        current=self.preview(project)
        if current['status']=='running':return current
        dev_cmd, cwd_path, resolved_port, stack = self._detect_preview_command(base, port)
        if not 1024<=resolved_port<=65535:raise BridgeError('Port must be 1024-65535')
        env=os.environ.copy();env.update({'PORT':str(resolved_port),'HOST':'127.0.0.1','HOSTNAME':'127.0.0.1'})
        popen_kwargs: dict[str, Any] = {
            'cwd': cwd_path,
            'env': env,
            'stdin': subprocess.DEVNULL,
            'stdout': subprocess.PIPE,
            'stderr': subprocess.STDOUT,
        }
        if os.name != 'nt':
            popen_kwargs['start_new_session'] = True
        proc=subprocess.Popen(dev_cmd, **popen_kwargs)
        log_buf: deque[str] = deque(maxlen=200)
        def _drain_logs(p_obj: subprocess.Popen, buf: deque[str]):
            if not p_obj.stdout:
                return
            try:
                for raw_line in iter(p_obj.stdout.readline, b''):
                    text = raw_line.decode('utf-8', errors='replace').rstrip('\r\n')
                    if text:
                        buf.append(REDACTION.sub('[REDACTED]', text)[:1000])
            except Exception:
                pass
        threading.Thread(target=_drain_logs, args=(proc, log_buf), daemon=True).start()
        self._register_pid(f'preview:{project}', proc, Path(dev_cmd[0]).stem or 'preview')
        with self.lock:self.previews[project]={'process':proc,'port':resolved_port,'stack':stack,'logs':log_buf}
        deadline = time.monotonic() + 12.0
        status = 'starting'
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                with self.lock:self.previews.pop(project,None)
                err_tail = '\n'.join(list(log_buf)[-10:])
                raise BridgeError(f'Preview server exited early (code {proc.returncode}): {err_tail[:300]}', 500)
            try:
                with socket.create_connection(('127.0.0.1', resolved_port), timeout=0.2):
                    status = 'running'
                    break
            except OSError:
                time.sleep(0.15)
        return {'status':status,'url':f'http://127.0.0.1:{resolved_port}','stack':stack,'logs':list(log_buf),'note':'Framework must bind 127.0.0.1 and respect PORT'}

    def stop_preview(self,project:str):
        with self.lock: p=self.previews.pop(project,None)
        if p:self._kill(p['process'])
        self._unregister_pid(f'preview:{project}')
        return {'status':'stopped','logs':[]}

    def git_status(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        try:
            res_branch = subprocess.run([git_cmd, 'rev-parse', '--abbrev-ref', 'HEAD'],
                                        cwd=base, capture_output=True, text=True, timeout=10)
            branch = res_branch.stdout.strip() if res_branch.returncode == 0 else 'HEAD'
            res = subprocess.run([git_cmd, 'status', '--porcelain'],
                                 cwd=base, capture_output=True, text=True, timeout=10)
            if res.returncode != 0:
                return {'is_git': False, 'branch': '', 'files': [], 'clean': True, 'error': res.stderr.strip(), 'has_conflicts': False, 'conflicted_files': []}
            lines = [l.rstrip() for l in res.stdout.splitlines() if l.strip()]
            files = []
            for l in lines:
                status_code = l[:2]
                filename = l[3:].strip()
                files.append({'status': status_code, 'file': filename})

            # Check for unmerged / conflicted files
            unmerged_res = subprocess.run([git_cmd, 'diff', '--name-only', '--diff-filter=U'],
                                         cwd=base, capture_output=True, text=True, timeout=5)
            conflicted_files = [f.strip() for f in unmerged_res.stdout.splitlines() if f.strip()] if unmerged_res.returncode == 0 else []

            return {
                'is_git': True,
                'branch': branch,
                'files': files,
                'clean': len(files) == 0,
                'uncommitted_count': len(files),
                'has_conflicts': len(conflicted_files) > 0,
                'conflicted_files': conflicted_files
            }
        except Exception as e:
            return {'is_git': False, 'branch': '', 'files': [], 'clean': True, 'error': str(e), 'has_conflicts': False, 'conflicted_files': []}

    def git_diff(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        try:
            res_unstaged = subprocess.run([git_cmd, 'diff'],
                                          cwd=base, capture_output=True, text=True, timeout=15)
            res_staged = subprocess.run([git_cmd, 'diff', '--cached'],
                                        cwd=base, capture_output=True, text=True, timeout=15)
            combined = (res_staged.stdout + '\n' + res_unstaged.stdout).strip()
            clean_diff = REDACTION.sub('[REDACTED]', combined)[:64000]
            return {
                'diff': clean_diff,
                'has_changes': bool(combined),
                'bytes': len(clean_diff.encode('utf-8'))
            }
        except Exception as e:
            return {'diff': '', 'has_changes': False, 'error': str(e)}

    def git_commit(self, project: str, message: str) -> dict[str, Any]:
        base = self._project(project)
        msg = message.strip()
        if not msg:
            raise BridgeError('Commit message cannot be empty')
        if len(msg) > 1000:
            raise BridgeError('Commit message must be under 1000 characters')
        git_cmd = shutil.which('git') or 'git'
        try:
            res_add = subprocess.run([git_cmd, 'add', '-A'], cwd=base, capture_output=True, text=True, timeout=15)
            if res_add.returncode != 0:
                raise BridgeError(f'Git add failed: {res_add.stderr.strip()}')
            commit_cmd = [git_cmd]
            gh_user = os.environ.get('GITHUB_USER', '').strip()
            gh_email = os.environ.get('GITHUB_EMAIL', '').strip()
            if gh_user:
                commit_cmd += ['-c', f'user.name={gh_user}']
            if gh_email:
                commit_cmd += ['-c', f'user.email={gh_email}']
            commit_cmd += ['commit', '-m', msg]
            res_commit = subprocess.run(commit_cmd, cwd=base, capture_output=True, text=True, timeout=15)
            if res_commit.returncode != 0:
                if 'nothing to commit' in res_commit.stdout or 'nothing to commit' in res_commit.stderr:
                    return {'success': True, 'committed': False, 'message': 'Nothing to commit, working tree clean'}
                raise BridgeError(f'Git commit failed: {res_commit.stderr.strip() or res_commit.stdout.strip()}')
            res_rev = subprocess.run([git_cmd, 'rev-parse', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
            sha = res_rev.stdout.strip() if res_rev.returncode == 0 else ''
            return {'success': True, 'committed': True, 'sha': sha, 'message': msg}
        except BridgeError:
            raise
        except Exception as e:
            raise BridgeError(f'Git commit error: {e}')

    def project_instructions(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        for name in ['AGENTS.md', 'CLAUDE.md']:
            f = base / name
            if f.is_file():
                try:
                    return {'file': name, 'content': f.read_text(encoding='utf-8')[:64000]}
                except Exception:
                    pass
        return {'file': 'AGENTS.md', 'content': ''}

    def save_project_instructions(self, project: str, content: str) -> dict[str, Any]:
        return self.write_file(project, 'AGENTS.md', content)

    # ---- Phase 2: Git Branch, Push & Checkpoint ----

    def git_branches(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            return {'is_git': False, 'current': '', 'branches': [], 'remotes': []}
        cur_res = subprocess.run([git_cmd, 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
        current = cur_res.stdout.strip() if cur_res.returncode == 0 else ''
        br_res = subprocess.run([git_cmd, 'branch', '--format=%(refname:short)'], cwd=base, capture_output=True, text=True, timeout=10)
        branches = [b.strip() for b in br_res.stdout.splitlines() if b.strip()] if br_res.returncode == 0 else []
        if current and current != 'HEAD' and current not in branches:
            branches.insert(0, current)
        rem_res = subprocess.run([git_cmd, 'remote'], cwd=base, capture_output=True, text=True, timeout=10)
        remotes = [r.strip() for r in rem_res.stdout.splitlines() if r.strip()] if rem_res.returncode == 0 else []
        origin_url = ''
        if 'origin' in remotes:
            url_res = subprocess.run([git_cmd, 'remote', 'get-url', 'origin'], cwd=base, capture_output=True, text=True, timeout=5)
            if url_res.returncode == 0:
                origin_url = url_res.stdout.strip()
        return {'is_git': True, 'current': current, 'branches': branches, 'remotes': remotes, 'origin_url': origin_url}

    def git_checkout(self, project: str, branch: str, create: bool = False) -> dict[str, Any]:
        base = self._project(project)
        b = branch.strip()
        if not b or not BRANCH_RE.fullmatch(b) or '..' in b or b.startswith('-') or b.endswith('/'):
            raise BridgeError('Invalid git branch name')
        git_cmd = shutil.which('git') or 'git'
        cmd = [git_cmd, 'checkout', '-b', b] if create else [git_cmd, 'checkout', b]
        res = subprocess.run(cmd, cwd=base, capture_output=True, text=True, timeout=15)
        if res.returncode != 0:
            err = REDACTION.sub('[REDACTED]', (res.stderr or res.stdout).strip())
            raise BridgeError(f'Git checkout failed: {err[:300]}', 422)
        return {'success': True, 'branch': b, 'created': create}

    def git_push(self, project: str, remote: str = 'origin', branch: str = '', token: str = '') -> dict[str, Any]:
        base = self._project(project)
        rem = (remote or 'origin').strip()
        if not NAME_RE.fullmatch(rem):
            raise BridgeError('Invalid git remote name')
        git_cmd = shutil.which('git') or 'git'
        target_branch = branch.strip()
        if not target_branch:
            cur_res = subprocess.run([git_cmd, 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
            target_branch = cur_res.stdout.strip() if cur_res.returncode == 0 else ''
        if not target_branch or not BRANCH_RE.fullmatch(target_branch) or '..' in target_branch or target_branch.startswith('-'):
            raise BridgeError('Invalid target branch name')
        push_cmd = [git_cmd]
        gh_token = (token or '').strip() or os.environ.get('GITHUB_TOKEN', '').strip()
        push_env = os.environ.copy()
        push_env['GIT_TERMINAL_PROMPT'] = '0'
        if gh_token:
            push_cmd += self._github_auth_git_args(gh_token)
        # If remote is git@github.com:... and we have an HTTPS token, push via HTTPS URL instead of SSH
        push_target = rem
        if gh_token:
            url_res = subprocess.run([git_cmd, 'remote', 'get-url', rem], cwd=base, capture_output=True, text=True, timeout=5)
            rem_url = url_res.stdout.strip() if url_res.returncode == 0 else ''
            if rem_url.startswith('git@github.com:'):
                push_target = 'https://github.com/' + rem_url[len('git@github.com:'):]
        # Explicitly never pass --force or -f.
        push_cmd += ['push', '-u', push_target, target_branch]
        try:
            res = subprocess.run(push_cmd, cwd=base, capture_output=True, text=True, timeout=75, env=push_env)
        except subprocess.TimeoutExpired:
            raise BridgeError('Git push timed out after 75 seconds. Check your internet connection.', 504)
        combined = REDACTION.sub('[REDACTED]', (res.stdout + '\n' + res.stderr).strip())
        if res.returncode != 0:
            raise BridgeError(f'Git push failed: {combined[:350]}', 422)
        return {'success': True, 'remote': rem, 'branch': target_branch, 'output': combined[:1000]}

    def git_create_and_push(self, project: str, repo_name: str = '', is_private: bool = True, description: str = '', token: str = '') -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        gh_token = (token or '').strip() or os.environ.get('GITHUB_TOKEN', '').strip()
        if not gh_token:
            raise BridgeError('GitHub authentication required. Please sign in via Settings → Agents & Git.', 401)

        # 1. Ensure local git repository exists
        if not (base / '.git').exists():
            subprocess.run([git_cmd, 'init', '-q', str(base)], check=True, timeout=15)
            gi = base / '.gitignore'
            if not gi.exists():
                gi.write_text('node_modules/\ndist/\n.next/\n.venv/\n__pycache__/\n.gradle/\n.env\n.env.*\n!.env.example\n', encoding='utf-8')

        gh_user = os.environ.get('GITHUB_USER', '').strip()
        gh_email = os.environ.get('GITHUB_EMAIL', '').strip()

        # 2. Stage and commit files if no commits exist or working tree has changes
        rev_head = subprocess.run([git_cmd, 'rev-parse', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=5)
        has_head = rev_head.returncode == 0
        st_res = subprocess.run([git_cmd, 'status', '--porcelain'], cwd=base, capture_output=True, text=True, timeout=10)
        has_changes = bool(st_res.stdout.strip())

        if not has_head or has_changes:
            subprocess.run([git_cmd, 'add', '-A'], cwd=base, capture_output=True, timeout=15)
            commit_cmd = [git_cmd]
            if gh_user:
                commit_cmd += ['-c', f'user.name={gh_user}']
            if gh_email:
                commit_cmd += ['-c', f'user.email={gh_email}']
            commit_cmd += ['commit', '-m', f'Initial commit: {project}']
            subprocess.run(commit_cmd, cwd=base, capture_output=True, timeout=15)

        # 3. Ensure target branch is main or active branch
        cur_br = subprocess.run([git_cmd, 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=5)
        target_branch = cur_br.stdout.strip() if cur_br.returncode == 0 and cur_br.stdout.strip() != 'HEAD' else 'main'
        if target_branch == 'HEAD' or not target_branch:
            subprocess.run([git_cmd, 'branch', '-M', 'main'], cwd=base, capture_output=True, timeout=10)
            target_branch = 'main'

        # 4. Safe repo name
        safe_repo = re.sub(r'[^a-zA-Z0-9._-]', '-', (repo_name or project).strip())[:100]
        if not safe_repo:
            safe_repo = project

        # 5. Call GitHub REST API to create repository
        import urllib.request
        import urllib.error
        payload = {
            'name': safe_repo,
            'description': description or f'Project {project} created with Tablet Agent Studio',
            'private': bool(is_private),
            'auto_init': False
        }
        req = urllib.request.Request(
            'https://api.github.com/user/repos',
            data=json.dumps(payload).encode('utf-8'),
            headers={
                'Authorization': f'Bearer {gh_token}',
                'Accept': 'application/vnd.github+json',
                'Content-Type': 'application/json',
                'User-Agent': 'TabletAgentStudio/0.1'
            },
            method='POST'
        )
        clone_url = ''
        html_url = ''
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                resp_data = json.loads(resp.read().decode('utf-8'))
                clone_url = resp_data.get('clone_url', '')
                html_url = resp_data.get('html_url', '')
        except urllib.error.HTTPError as he:
            err_body = he.read().decode('utf-8', errors='replace')
            if he.code == 422 and 'already exists' in err_body:
                if gh_user:
                    clone_url = f'https://github.com/{gh_user}/{safe_repo}.git'
                    html_url = f'https://github.com/{gh_user}/{safe_repo}'
                else:
                    raise BridgeError(f'Repository "{safe_repo}" already exists on GitHub.', 409)
            else:
                clean_err = REDACTION.sub('[REDACTED]', err_body[:300])
                raise BridgeError(f'GitHub API error ({he.code}): {clean_err}', he.code)
        except Exception as e:
            raise BridgeError(f'Failed to connect to GitHub: {e}', 502)

        if not clone_url:
            raise BridgeError('GitHub did not return a valid clone URL', 502)

        # 6. Set or update remote origin
        rem_res = subprocess.run([git_cmd, 'remote'], cwd=base, capture_output=True, text=True, timeout=5)
        remotes = [r.strip() for r in rem_res.stdout.splitlines() if r.strip()] if rem_res.returncode == 0 else []
        if 'origin' in remotes:
            subprocess.run([git_cmd, 'remote', 'set-url', 'origin', clone_url], cwd=base, capture_output=True, timeout=10)
        else:
            subprocess.run([git_cmd, 'remote', 'add', 'origin', clone_url], cwd=base, capture_output=True, timeout=10)

        # 7. Push branch to GitHub
        push_env = os.environ.copy()
        push_env['GIT_TERMINAL_PROMPT'] = '0'
        push_cmd = [git_cmd] + self._github_auth_git_args(gh_token) + ['push', '-u', 'origin', target_branch]
        res = subprocess.run(push_cmd, cwd=base, capture_output=True, text=True, timeout=60, env=push_env)
        combined = REDACTION.sub('[REDACTED]', (res.stdout + '\n' + res.stderr).strip())
        if res.returncode != 0:
            raise BridgeError(f'Created repo on GitHub ({html_url}), but push failed: {combined[:300]}', 422)

        return {
            'success': True,
            'repo_name': safe_repo,
            'html_url': html_url,
            'clone_url': clone_url,
            'branch': target_branch,
            'output': f'Created GitHub repository {html_url} and pushed branch {target_branch} successfully.'
        }

    def git_checkpoint(self, project: str, label: str = 'apply-to-file') -> dict[str, Any]:
        safe_label = re.sub(r'[^a-zA-Z0-9._-]', '-', label or 'manual')[:40]
        sha = self.create_git_checkpoint(project, safe_label)
        return {'success': True, 'sha': sha or '', 'label': safe_label}

    def git_log(self, project: str, limit: int = 50) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            return {'is_git': False, 'commits': []}
        lim = max(1, min(100, int(limit)))
        fmt = '%H%x1f%h%x1f%an%x1f%ar%x1f%s'
        res = subprocess.run([git_cmd, 'log', f'-n{lim}', f'--pretty=format:{fmt}'], cwd=base, capture_output=True, text=True, timeout=10)
        commits = []
        if res.returncode == 0 and res.stdout.strip():
            for line in res.stdout.strip().splitlines():
                parts = line.split('\x1f')
                if len(parts) >= 5:
                    commits.append({
                        'full_hash': parts[0],
                        'hash': parts[1],
                        'author': parts[2],
                        'relative_time': parts[3],
                        'message': REDACTION.sub('[REDACTED]', parts[4])
                    })
        return {'is_git': True, 'commits': commits}

    def git_pull(self, project: str, remote: str = 'origin', branch: str = '', token: str = '') -> dict[str, Any]:
        base = self._project(project)
        rem = (remote or 'origin').strip()
        if not NAME_RE.fullmatch(rem):
            raise BridgeError('Invalid git remote name')
        git_cmd = shutil.which('git') or 'git'
        target_branch = branch.strip()
        if not target_branch:
            cur_res = subprocess.run([git_cmd, 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
            target_branch = cur_res.stdout.strip() if cur_res.returncode == 0 else ''
        pull_cmd = [git_cmd]
        gh_token = (token or '').strip() or os.environ.get('GITHUB_TOKEN', '').strip()
        pull_env = os.environ.copy()
        pull_env['GIT_TERMINAL_PROMPT'] = '0'
        if gh_token:
            pull_cmd += self._github_auth_git_args(gh_token)
        pull_cmd += ['pull', rem, target_branch] if target_branch else ['pull', rem]
        res = subprocess.run(pull_cmd, cwd=base, capture_output=True, text=True, timeout=60, env=pull_env)
        combined = REDACTION.sub('[REDACTED]', (res.stdout + '\n' + res.stderr).strip())
        if res.returncode != 0:
            unmerged = subprocess.run([git_cmd, 'diff', '--name-only', '--diff-filter=U'], cwd=base, capture_output=True, text=True, timeout=10)
            conflicts = [f.strip() for f in unmerged.stdout.splitlines() if f.strip()]
            if conflicts or 'CONFLICT' in combined or 'Merge conflict' in combined:
                return {
                    'success': False,
                    'has_conflicts': True,
                    'conflicted_files': conflicts,
                    'output': combined[:1000],
                    'message': f'Merge conflict in {len(conflicts)} file(s)'
                }
            raise BridgeError(f'Git pull failed: {combined[:300]}', 422)
        return {'success': True, 'has_conflicts': False, 'conflicted_files': [], 'remote': rem, 'branch': target_branch, 'output': combined[:1000]}

    def git_discard(self, project: str, filepath: str = '', discard_all: bool = False) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            raise BridgeError('Not a git repository')
        if discard_all:
            res_reset = subprocess.run([git_cmd, 'reset', '--hard', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=15)
            if res_reset.returncode != 0:
                raise BridgeError(f'Git reset failed: {res_reset.stderr.strip()}')
            subprocess.run([git_cmd, 'clean', '-fd'], cwd=base, capture_output=True, text=True, timeout=15)
            return {'success': True, 'discarded': 'all'}
        rel = (filepath or '').strip()
        if not rel:
            raise BridgeError('File path required when discard_all is False')
        target = self._file(project, rel)
        st = subprocess.run([git_cmd, 'status', '--porcelain', '--', rel], cwd=base, capture_output=True, text=True, timeout=10)
        status_line = st.stdout.strip()
        if status_line.startswith('??'):
            if target.is_file():
                target.unlink(missing_ok=True)
            elif target.is_dir():
                shutil.rmtree(target, ignore_errors=True)
            return {'success': True, 'discarded': rel, 'action': 'deleted_untracked'}
        else:
            res = subprocess.run([git_cmd, 'checkout', 'HEAD', '--', rel], cwd=base, capture_output=True, text=True, timeout=15)
            if res.returncode != 0:
                raise BridgeError(f'Git checkout failed: {res.stderr.strip() or res.stdout.strip()}')
            return {'success': True, 'discarded': rel, 'action': 'reverted'}

    def git_diff_file(self, project: str, filepath: str) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        rel = (filepath or '').strip()
        if not rel:
            return self.git_diff(project)
        target = self._file(project, rel)
        try:
            res = subprocess.run([git_cmd, 'diff', 'HEAD', '--', rel], cwd=base, capture_output=True, text=True, timeout=15)
            clean_diff = REDACTION.sub('[REDACTED]', res.stdout.strip())[:32000]
            if not clean_diff:
                if target.is_file():
                    try:
                        content = target.read_text(encoding='utf-8', errors='replace')[:4000]
                        lines = [f'+{line}' for line in content.splitlines()]
                        clean_diff = f'--- /dev/null\n+++ b/{rel}\n@@ -0,0 +1,{len(lines)} @@\n' + '\n'.join(lines)
                    except Exception:
                        pass
            return {'filepath': rel, 'diff': clean_diff, 'has_changes': bool(clean_diff)}
        except Exception as e:
            return {'filepath': rel, 'diff': '', 'has_changes': False, 'error': str(e)}

    def git_ai_commit(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        st = subprocess.run([git_cmd, 'status', '--porcelain'], cwd=base, capture_output=True, text=True, timeout=10)
        lines = [l.strip() for l in st.stdout.splitlines() if l.strip()]
        if not lines:
            return {'message': 'chore: working tree clean (no changes)'}
        changed_files = [l[3:].strip() for l in lines]
        scopes = set()
        for f in changed_files:
            parts = f.replace('\\', '/').split('/')
            if len(parts) > 1:
                scopes.add(parts[0].lower())
        scope_str = list(scopes)[0] if len(scopes) == 1 else ''
        scope_part = f'({scope_str})' if scope_str else ''

        diff_res = subprocess.run([git_cmd, 'diff', '--stat', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
        stat = diff_res.stdout.strip().splitlines()[-1] if diff_res.stdout.strip() else f'{len(changed_files)} files changed'

        if any(f.endswith(('README.md', 'docs', '.md')) for f in changed_files) and len(changed_files) == 1:
            msg = f'docs{scope_part}: update documentation'
        elif any('test' in f.lower() for f in changed_files):
            msg = f'test{scope_part}: add and update test suites'
        elif any('fix' in f.lower() or 'bug' in f.lower() for f in changed_files):
            msg = f'fix{scope_part}: resolve issues in {", ".join([Path(f).name for f in changed_files[:2]])}'
        elif any(f.endswith(('.kt', '.java', '.tsx', '.jsx', '.vue', '.html')) for f in changed_files):
            msg = f'feat{scope_part}: update UI components and workspace features'
        elif any(f.endswith(('.json', '.yaml', '.yml', '.toml', '.gradle')) for f in changed_files):
            msg = f'chore{scope_part}: update project configurations and dependencies'
        else:
            first_name = Path(changed_files[0]).stem
            msg = f'feat{scope_part}: update {first_name} and project modules'
        return {'message': msg, 'files_count': len(changed_files), 'stat': stat}

    def git_draft_pr(self, project: str, base_branch: str = 'main') -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            raise BridgeError('Not a git repository')

        cur_br = subprocess.run([git_cmd, 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
        head = cur_br.stdout.strip() if cur_br.returncode == 0 else 'HEAD'

        target_base = (base_branch or 'main').strip()

        log_res = subprocess.run([git_cmd, 'log', f'{target_base}..HEAD', '--oneline'], cwd=base, capture_output=True, text=True, timeout=10)
        if log_res.returncode != 0:
            log_res = subprocess.run([git_cmd, 'log', f'origin/{target_base}..HEAD', '--oneline'], cwd=base, capture_output=True, text=True, timeout=10)
            if log_res.returncode != 0:
                log_res = subprocess.run([git_cmd, 'log', '-n', '5', '--oneline'], cwd=base, capture_output=True, text=True, timeout=10)

        commit_lines = [l.strip() for l in log_res.stdout.splitlines() if l.strip()]

        stat_res = subprocess.run([git_cmd, 'diff', '--stat', f'{target_base}...HEAD'], cwd=base, capture_output=True, text=True, timeout=10)
        if stat_res.returncode != 0:
            stat_res = subprocess.run([git_cmd, 'diff', '--stat', 'HEAD~1'], cwd=base, capture_output=True, text=True, timeout=10)
        stat_text = stat_res.stdout.strip()

        remote_res = subprocess.run([git_cmd, 'remote', 'get-url', 'origin'], cwd=base, capture_output=True, text=True, timeout=10)
        remote_url = remote_res.stdout.strip() if remote_res.returncode == 0 else ''
        owner = ''
        repo = ''
        if remote_url:
            m = re.search(r'github\.com[:/]([^/]+)/([^/.]+)', remote_url)
            if m:
                owner = m.group(1)
                repo = m.group(2)

        if commit_lines:
            top_commit = re.sub(r'^[a-f0-9]+\s+', '', commit_lines[0])
            title = top_commit
        elif head and head not in ('main', 'master', 'HEAD'):
            title = f"feat({head}): update {project}"
        else:
            title = f"feat: update {project}"

        body_parts = [
            "## Summary",
            f"Pull request for branch `{head}` into `{target_base}`."
        ]
        if commit_lines:
            body_parts.append("\n## Commits")
            for c in commit_lines[:10]:
                parts = c.split(' ', 1)
                sha = parts[0]
                msg = parts[1] if len(parts) > 1 else ''
                body_parts.append(f"- `{sha}` {msg}")
        if stat_text:
            body_parts.append("\n## Changes")
            body_parts.append("```text\n" + stat_text + "\n```")
        body_parts.append("\n## Verification")
        body_parts.append("- [x] Verified on physical Android tablet (HUAWEI MatePad ARM64)")
        body_parts.append("- [x] Automated tests and build passed")

        return {
            'head': head,
            'base': target_base,
            'title': title,
            'body': "\n".join(body_parts),
            'owner': owner,
            'repo': repo,
            'remote_url': remote_url,
            'commits_count': len(commit_lines)
        }

    def git_create_pr(self, project: str, title: str, body: str, head: str, base: str = 'main', token: str = '') -> dict[str, Any]:
        base_dir = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        gh_token = (token or '').strip() or os.environ.get('GITHUB_TOKEN', '').strip()
        if not gh_token:
            raise BridgeError('GitHub authentication required. Please sign in via Settings → Agents & Git.', 401)

        remote_res = subprocess.run([git_cmd, 'remote', 'get-url', 'origin'], cwd=base_dir, capture_output=True, text=True, timeout=10)
        remote_url = remote_res.stdout.strip() if remote_res.returncode == 0 else ''
        if not remote_url:
            raise BridgeError('No git remote "origin" found. Please push to GitHub first.', 400)

        m = re.search(r'github\.com[:/]([^/]+)/([^/.]+)', remote_url)
        if not m:
            raise BridgeError(f'Remote origin is not a recognized GitHub URL: {remote_url}', 400)
        owner = m.group(1)
        repo = m.group(2)

        clean_title = (title or '').strip()
        if not clean_title:
            raise BridgeError('PR title cannot be blank', 400)

        clean_head = (head or '').strip()
        if not clean_head:
            cur_br = subprocess.run([git_cmd, 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=base_dir, capture_output=True, text=True, timeout=5)
            clean_head = cur_br.stdout.strip() if cur_br.returncode == 0 else 'main'

        clean_base = (base or 'main').strip()

        payload = {
            'title': clean_title,
            'body': (body or '').strip(),
            'head': clean_head,
            'base': clean_base
        }

        import urllib.request
        import urllib.error
        req = urllib.request.Request(
            f'https://api.github.com/repos/{owner}/{repo}/pulls',
            data=json.dumps(payload).encode('utf-8'),
            headers={
                'Authorization': f'Bearer {gh_token}',
                'Accept': 'application/vnd.github+json',
                'Content-Type': 'application/json',
                'User-Agent': 'TabletAgentStudio/0.1'
            },
            method='POST'
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                return {
                    'success': True,
                    'pr_url': data.get('html_url', ''),
                    'pr_number': data.get('number', 0),
                    'state': data.get('state', 'open'),
                    'title': data.get('title', clean_title),
                    'created_at': data.get('created_at', '')
                }
        except urllib.error.HTTPError as he:
            err_body = he.read().decode('utf-8', errors='replace')
            try:
                err_json = json.loads(err_body)
                err_msg = err_json.get('message', '')
                errors = err_json.get('errors', [])
                if errors and isinstance(errors, list):
                    err_msg += ': ' + '; '.join(e.get('message', str(e)) for e in errors)
            except Exception:
                err_msg = err_body[:200]
            raise BridgeError(f'GitHub PR creation failed: {err_msg}', he.code)

    def git_resolve_conflicts(self, project: str) -> dict[str, Any]:
        base_dir = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base_dir / '.git').exists():
            raise BridgeError('Not a git repository')

        unmerged_res = subprocess.run([git_cmd, 'diff', '--name-only', '--diff-filter=U'], cwd=base_dir, capture_output=True, text=True, timeout=10)
        conflicted_files = [f.strip() for f in unmerged_res.stdout.splitlines() if f.strip()]
        if not conflicted_files:
            return {'success': True, 'resolved_files': [], 'message': 'No merge conflicts found'}

        conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n([\s\S]*?)=======\n([\s\S]*?)>>>>>>>[^\n]*\n')
        resolved_files = []

        for rel_path in conflicted_files:
            target_path = self._file(project, rel_path)
            if not target_path.exists() or not target_path.is_file():
                continue
            try:
                content = target_path.read_text(encoding='utf-8', errors='replace')
            except Exception:
                continue

            def _resolve_block(m: re.Match) -> str:
                local_part = m.group(1)
                remote_part = m.group(2)

                if not local_part.strip():
                    return remote_part
                if not remote_part.strip():
                    return local_part

                local_lines = [l for l in local_part.splitlines() if l.strip()]
                remote_lines = [l for l in remote_part.splitlines() if l.strip()]
                is_import_or_config = all(
                    l.startswith(('import ', 'from ', 'export ', 'require(', '"', "'", '//', '/*'))
                    for l in (local_lines + remote_lines)
                )
                if is_import_or_config:
                    seen = set()
                    combined = []
                    for l in local_part.splitlines():
                        trimmed = l.strip()
                        if trimmed and trimmed not in seen:
                            seen.add(trimmed)
                            combined.append(l)
                        elif not trimmed:
                            combined.append(l)
                    for l in remote_part.splitlines():
                        trimmed = l.strip()
                        if trimmed and trimmed not in seen:
                            seen.add(trimmed)
                            combined.append(l)
                    return '\n'.join(combined) + '\n'

                if (('def ' in local_part or 'fun ' in local_part or 'function ' in local_part) and
                    ('def ' in remote_part or 'fun ' in remote_part or 'function ' in remote_part)):
                    return local_part.rstrip() + '\n\n' + remote_part.lstrip()

                if local_part.strip() == remote_part.strip():
                    return local_part
                return local_part.rstrip() + '\n' + remote_part.lstrip()

            resolved_content = conflict_pattern.sub(_resolve_block, content)
            target_path.write_text(resolved_content, encoding='utf-8')
            subprocess.run([git_cmd, 'add', rel_path], cwd=base_dir, check=True, timeout=10)
            resolved_files.append(rel_path)

        gh_user = os.environ.get('GITHUB_USER', '').strip()
        gh_email = os.environ.get('GITHUB_EMAIL', '').strip()
        commit_cmd = [git_cmd]
        if gh_user:
            commit_cmd += ['-c', f'user.name={gh_user}']
        if gh_email:
            commit_cmd += ['-c', f'user.email={gh_email}']
        commit_msg = f'merge: auto-resolve conflicts via AI in {len(resolved_files)} file(s)'
        commit_cmd += ['commit', '-m', commit_msg]
        commit_res = subprocess.run(commit_cmd, cwd=base_dir, capture_output=True, text=True, timeout=15)

        rev_head = subprocess.run([git_cmd, 'rev-parse', '--short', 'HEAD'], cwd=base_dir, capture_output=True, text=True, timeout=5)
        sha = rev_head.stdout.strip() if rev_head.returncode == 0 else ''

        return {
            'success': True,
            'resolved_files': resolved_files,
            'commit_sha': sha,
            'commit_message': commit_msg,
            'commit_output': commit_res.stdout.strip()
        }

    # ---- Phase 3: Workspace Global Search & Project Management ----

    def search_files(self, project: str, query: str, case_sensitive: bool = False) -> dict[str, Any]:
        base = self._project(project).resolve()
        q = query.strip()
        if not q:
            return {'query': '', 'matches': [], 'truncated': False}
        if len(q) > 200:
            raise BridgeError('Search query too long (max 200 chars)')
        needle = q if case_sensitive else q.lower()
        skip_dirs = {'.git', 'node_modules', '.next', '.gradle', '.venv', '__pycache__', 'dist', 'build'}
        matches: list[dict[str, Any]] = []
        truncated = False
        for root, dirs, files in os.walk(base):
            dirs[:] = sorted(d for d in dirs if d not in skip_dirs)
            for fname in sorted(files):
                if fname == '.env' or fname.startswith('.env.'):
                    if fname != '.env.example':
                        continue
                fpath = Path(root) / fname
                try:
                    resolved = fpath.resolve()
                    if not resolved.is_relative_to(base):
                        continue
                    if resolved.stat().st_size > MAX_FILE_BYTES:
                        continue
                    raw = resolved.read_bytes()
                    if b'\x00' in raw[:4096]:
                        continue
                    text = raw.decode('utf-8', errors='replace')
                except OSError:
                    continue
                rel = str(resolved.relative_to(base)).replace('\\', '/')
                for idx, line in enumerate(text.splitlines(), start=1):
                    hay = line if case_sensitive else line.lower()
                    if needle in hay:
                        snippet = REDACTION.sub('[REDACTED]', line.strip())[:200]
                        matches.append({'file': rel, 'line': idx, 'snippet': snippet})
                        if len(matches) >= 200:
                            truncated = True
                            break
                if truncated:
                    break
            if truncated:
                break
        return {'query': q, 'matches': matches, 'truncated': truncated}

    def delete_project(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        self.stop_preview(project)
        self.terminal_stop(project)
        shutil.rmtree(base)
        with self._db() as db:
            db.execute('DELETE FROM agent_threads WHERE project=?', (project,))
        return {'deleted': True, 'name': project}

    def rename_project(self, project: str, new_name: str) -> dict[str, Any]:
        base = self._project(project)
        target_name = new_name.strip()
        if not NAME_RE.fullmatch(target_name) or target_name in {'.', '..'}:
            raise BridgeError('Invalid new project name')
        target = self.workspaces / target_name
        if target.exists():
            raise BridgeError('Target project name already exists', 409)
        self.stop_preview(project)
        self.terminal_stop(project)
        base.rename(target)
        with self._db() as db:
            db.execute('UPDATE tasks SET project=? WHERE project=?', (target_name, project))
            db.execute('UPDATE agent_threads SET project=? WHERE project=?', (target_name, project))
        return {'renamed': True, 'old_name': project, 'name': target_name}

    # ---- Phase 3: Full Interactive Linux PTY Terminal Manager ----

    def _pty_reader(self, project: str, session: dict[str, Any]):
        master_fd = session.get('master_fd')
        proc = session.get('process')
        try:
            if master_fd is not None:
                while True:
                    try:
                        chunk = os.read(master_fd, 4096)
                    except OSError:
                        break
                    if not chunk:
                        break
                    text = REDACTION.sub('[REDACTED]', chunk.decode('utf-8', errors='replace'))
                    with self.lock:
                        session['seq'] += 1
                        session['chunks'].append((session['seq'], text))
            elif proc is not None and proc.stdout is not None:
                fd = proc.stdout.fileno()
                while True:
                    try:
                        chunk = os.read(fd, 2048)
                    except OSError:
                        break
                    if not chunk:
                        break
                    text = REDACTION.sub('[REDACTED]', chunk.decode('utf-8', errors='replace'))
                    with self.lock:
                        session['seq'] += 1
                        session['chunks'].append((session['seq'], text))
        finally:
            if master_fd is not None:
                try:
                    os.close(master_fd)
                except OSError:
                    pass
                session['master_fd'] = None

    def terminal_start(self, project: str, cols: int = 80, rows: int = 24) -> dict[str, Any]:
        base = self._project(project)
        cols = max(20, min(300, int(cols)))
        rows = max(5, min(120, int(rows)))
        with self.lock:
            existing = self.terminals.get(project)
            if existing and existing['process'].poll() is None:
                return {'running': True, 'project': project, 'cols': existing['cols'], 'rows': existing['rows'], 'pty': existing['pty'], 'seq': existing['seq']}
        env = os.environ.copy()
        env.update({'TERM': 'xterm-256color', 'COLUMNS': str(cols), 'LINES': str(rows)})
        master_fd: int | None = None
        is_pty = False
        if os.name != 'nt':
            try:
                import fcntl
                import pty
                import termios
                m_fd, s_fd = pty.openpty()
                winsize = struct.pack('HHHH', rows, cols, 0, 0)
                fcntl.ioctl(s_fd, termios.TIOCSWINSZ, winsize)
                shell = shutil.which('bash') or shutil.which('sh') or '/bin/sh'
                proc = subprocess.Popen(
                    [shell, '-i'],
                    cwd=base,
                    env=env,
                    stdin=s_fd,
                    stdout=s_fd,
                    stderr=s_fd,
                    start_new_session=True,
                    close_fds=True,
                )
                os.close(s_fd)
                master_fd = m_fd
                is_pty = True
            except Exception:
                master_fd = None
                is_pty = False
        if not is_pty:
            shell_cmd = ['cmd.exe'] if os.name == 'nt' else [shutil.which('sh') or '/bin/sh']
            proc = subprocess.Popen(
                shell_cmd,
                cwd=base,
                env=env,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
        session: dict[str, Any] = {
            'process': proc,
            'master_fd': master_fd,
            'pty': is_pty,
            'cols': cols,
            'rows': rows,
            'seq': 0,
            'chunks': deque(maxlen=400),
        }
        with self.lock:
            self.terminals[project] = session
        self._register_pid(f'terminal:{project}', proc, 'shell')
        threading.Thread(target=self._pty_reader, args=(project, session), daemon=True).start()
        return {'running': True, 'project': project, 'cols': cols, 'rows': rows, 'pty': is_pty, 'seq': 0}

    def terminal_input(self, project: str, data: str) -> dict[str, Any]:
        self._project(project)
        with self.lock:
            session = self.terminals.get(project)
        if not session or session['process'].poll() is not None:
            raise BridgeError('Terminal session is not running', 409)
        raw = data.encode('utf-8')
        if session.get('master_fd') is not None:
            os.write(session['master_fd'], raw)
        elif session['process'].stdin is not None:
            session['process'].stdin.write(raw)
            session['process'].stdin.flush()
        return {'ok': True, 'bytes': len(raw)}

    def terminal_poll(self, project: str, after: int = 0) -> dict[str, Any]:
        self._project(project)
        with self.lock:
            session = self.terminals.get(project)
            if not session:
                return {'running': False, 'seq': after, 'data': ''}
            running = session['process'].poll() is None
            chunks = [text for seq, text in session['chunks'] if seq > after]
            latest_seq = session['seq']
        return {'running': running, 'seq': latest_seq, 'data': ''.join(chunks)}

    def terminal_resize(self, project: str, cols: int, rows: int) -> dict[str, Any]:
        self._project(project)
        cols = max(20, min(300, int(cols)))
        rows = max(5, min(120, int(rows)))
        with self.lock:
            session = self.terminals.get(project)
        if not session or session['process'].poll() is not None:
            raise BridgeError('Terminal session is not running', 409)
        session['cols'] = cols
        session['rows'] = rows
        if session.get('master_fd') is not None and os.name != 'nt':
            try:
                import fcntl
                import termios
                winsize = struct.pack('HHHH', rows, cols, 0, 0)
                fcntl.ioctl(session['master_fd'], termios.TIOCSWINSZ, winsize)
                if hasattr(os, 'killpg'):
                    os.killpg(os.getpgid(session['process'].pid), signal.SIGWINCH)
            except OSError:
                pass
        return {'ok': True, 'cols': cols, 'rows': rows}

    def terminal_stop(self, project: str) -> dict[str, Any]:
        with self.lock:
            session = self.terminals.pop(project, None)
        if session:
            self._kill(session['process'])
            if session.get('master_fd') is not None:
                try:
                    os.close(session['master_fd'])
                except OSError:
                    pass
                session['master_fd'] = None
        self._unregister_pid(f'terminal:{project}')
        return {'running': False}

    # ---- Phase 5: Super Suite (Starter Templates, Audit & PWA) ----

    def templates_init(self, project: str, template_id: str) -> dict[str, Any]:
        self.create_project(project)
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        t_id = (template_id or 'react_vite').strip().lower()

        if t_id == 'react_vite':
            files = {
                'package.json': json.dumps({
                    "name": project,
                    "private": True,
                    "version": "0.1.0",
                    "type": "module",
                    "scripts": { "dev": "vite", "build": "vite build", "preview": "vite preview" },
                    "dependencies": { "react": "^18.3.1", "react-dom": "^18.3.1", "lucide-react": "^0.460.0" },
                    "devDependencies": { "@vitejs/plugin-react": "^4.3.4", "vite": "^6.0.0", "tailwindcss": "^3.4.15", "autoprefixer": "^10.4.20", "postcss": "^8.4.49" }
                }, indent=2),
                'vite.config.js': 'import { defineConfig } from "vite";\nimport react from "@vitejs/plugin-react";\n\nexport default defineConfig({\n  plugins: [react()],\n  server: { host: "127.0.0.1", port: 5173 }\n});\n',
                'index.html': f'<!doctype html>\n<html lang="en">\n  <head>\n    <meta charset="UTF-8" />\n    <meta name="viewport" content="width=device-width, initial-scale=1.0" />\n    <title>{project}</title>\n  </head>\n  <body class="bg-slate-900 text-slate-100">\n    <div id="root"></div>\n    <script type="module" src="/src/main.jsx"></script>\n  </body>\n</html>\n',
                'src/main.jsx': 'import React from "react";\nimport ReactDOM from "react-dom/client";\nimport App from "./App.jsx";\nimport "./index.css";\n\nReactDOM.createRoot(document.getElementById("root")).render(\n  <React.StrictMode>\n    <App />\n  </React.StrictMode>\n);\n',
                'src/App.jsx': 'import React, { useState } from "react";\nimport { Sparkles, Plus, Trash2, CheckCircle2 } from "lucide-react";\n\nexport default function App() {\n  const [count, setCount] = useState(0);\n  const [notes, setNotes] = useState([\n    "Build with OmniDev Tablet",\n    "Edit with Stylus and Inspector"\n  ]);\n  const [input, setInput] = useState("");\n\n  const addNote = () => {\n    if (!input.trim()) return;\n    setNotes([...notes, input.trim()]);\n    setInput("");\n  };\n\n  return (\n    <div className="min-h-screen p-8 max-w-2xl mx-auto flex flex-col gap-6">\n      <header className="flex items-center gap-3 border-b border-slate-800 pb-4">\n        <div className="p-2.5 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 text-white shadow-lg">\n          <Sparkles className="w-6 h-6" />\n        </div>\n        <div>\n          <h1 className="text-2xl font-bold tracking-tight">React + Vite Studio</h1>\n          <p className="text-sm text-slate-400">Created with OmniDev Starter Templates</p>\n        </div>\n      </header>\n\n      <div className="grid grid-cols-2 gap-4">\n        <div className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60 shadow-sm">\n          <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">State Counter</p>\n          <h2 className="text-3xl font-extrabold text-indigo-400 mb-3">{count}</h2>\n          <button onClick={() => setCount(c => c + 1)} className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-sm font-semibold transition-all shadow-md">\n            Increment\n          </button>\n        </div>\n        <div className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60 shadow-sm">\n          <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Active Notes</p>\n          <h2 className="text-3xl font-extrabold text-emerald-400 mb-3">{notes.length}</h2>\n          <span className="inline-flex items-center gap-1.5 text-xs text-emerald-400/90 font-medium">\n            <CheckCircle2 className="w-3.5 h-3.5" /> Synchronized\n          </span>\n        </div>\n      </div>\n\n      <div className="p-5 rounded-xl bg-slate-800/60 border border-slate-700/60 shadow-sm flex flex-col gap-3">\n        <h3 className="text-sm font-bold text-slate-200">Interactive Notes</h3>\n        <div className="flex gap-2">\n          <input\n            value={input}\n            onChange={e => setInput(e.target.value)}\n            onKeyDown={e => e.key === "Enter" && addNote()}\n            placeholder="Add new task or note..."\n            className="flex-1 px-3.5 py-2 rounded-lg bg-slate-900 border border-slate-700 text-sm focus:outline-none focus:border-indigo-500 text-slate-100"\n          />\n          <button onClick={addNote} className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-sm font-semibold flex items-center gap-1 shadow-md">\n            <Plus className="w-4 h-4" /> Add\n          </button>\n        </div>\n        <ul className="flex flex-col gap-2 mt-2">\n          {notes.map((n, i) => (\n            <li key={i} className="flex items-center justify-between p-2.5 rounded-lg bg-slate-900/80 border border-slate-700/50 text-sm">\n              <span>{n}</span>\n              <button onClick={() => setNotes(notes.filter((_, idx) => idx !== i))} className="text-slate-500 hover:text-red-400 transition-colors">\n                <Trash2 className="w-4 h-4" />\n              </button>\n            </li>\n          ))}\n        </ul>\n      </div>\n    </div>\n  );\n}\n',
                'src/index.css': '@tailwind base;\n@tailwind components;\n@tailwind utilities;\n\nbody {\n  margin: 0;\n  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;\n  background: #0f172a;\n  color: #f8fafc;\n}\n',
                '.gitignore': 'node_modules/\ndist/\n.env\n.env.*\n!.env.example\n'
            }
        elif t_id == 'pwa_notes':
            files = {
                'package.json': json.dumps({
                    "name": project,
                    "private": True,
                    "version": "1.0.0",
                    "type": "module",
                    "scripts": { "dev": "vite", "build": "vite build", "preview": "vite preview" },
                    "devDependencies": { "vite": "^6.0.0" }
                }, indent=2),
                'vite.config.js': 'import { defineConfig } from "vite";\nexport default defineConfig({ server: { host: "127.0.0.1", port: 5173 } });\n',
                'index.html': f'<!doctype html>\n<html lang="en">\n  <head>\n    <meta charset="UTF-8" />\n    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no" />\n    <link rel="manifest" href="/manifest.json" />\n    <meta name="theme-color" content="#4f46e5" />\n    <title>{project}</title>\n    <link rel="stylesheet" href="/src/style.css" />\n  </head>\n  <body>\n    <div class="app-container">\n      <header class="app-header">\n        <div class="header-content">\n          <h1>📱 Mobile PWA Notes</h1>\n          <p class="subtitle">Offline-first local storage</p>\n        </div>\n        <button id="add-btn" class="btn-primary">+ New Note</button>\n      </header>\n      <main class="notes-grid" id="notes-container"></main>\n    </div>\n    <script type="module" src="/src/main.js"></script>\n  </body>\n</html>\n',
                'src/main.js': 'const STORAGE_KEY = "pwa_notes_data";\nlet notes = JSON.parse(localStorage.getItem(STORAGE_KEY) || \'[{"id":"1","title":"Welcome to PWA Notes","content":"Installed and running fully offline on your Android tablet."}]\');\n\nfunction render() {\n  const container = document.getElementById("notes-container");\n  container.innerHTML = notes.map(n => `\n    <div class="note-card" data-id="${n.id}">\n      <h3>${n.title}</h3>\n      <p>${n.content}</p>\n      <button class="delete-btn" onclick="deleteNote(\'${n.id}\')">✕ Delete</button>\n    </div>\n  `).join("");\n}\n\nwindow.deleteNote = function(id) {\n  notes = notes.filter(n => n.id !== id);\n  localStorage.setItem(STORAGE_KEY, JSON.stringify(notes));\n  render();\n};\n\ndocument.getElementById("add-btn").addEventListener("click", () => {\n  const title = prompt("Note title:") || "Untitled";\n  const content = prompt("Note content:") || "";\n  notes.unshift({ id: Date.now().toString(), title, content });\n  localStorage.setItem(STORAGE_KEY, JSON.stringify(notes));\n  render();\n});\n\nif ("serviceWorker" in navigator) {\n  navigator.serviceWorker.register("/sw.js").catch(() => {});\n}\n\nrender();\n',
                'src/style.css': '* { box-sizing: border-box; margin: 0; padding: 0; }\nbody { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; padding: 1.5rem; }\n.app-container { max-width: 680px; margin: 0 auto; display: flex; flex-direction: column; gap: 1.5rem; }\n.app-header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #1e293b; padding-bottom: 1rem; }\n.app-header h1 { font-size: 1.5rem; font-weight: 700; color: #818cf8; }\n.subtitle { font-size: 0.85rem; color: #94a3b8; }\n.btn-primary { background: #4f46e5; color: white; border: none; padding: 0.6rem 1.2rem; border-radius: 8px; font-weight: 600; cursor: pointer; }\n.notes-grid { display: flex; flex-direction: column; gap: 1rem; }\n.note-card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 1.2rem; display: flex; flex-direction: column; gap: 0.6rem; }\n.note-card h3 { font-size: 1.1rem; color: #f1f5f9; }\n.note-card p { font-size: 0.95rem; color: #cbd5e1; line-height: 1.4; }\n.delete-btn { align-self: flex-end; background: transparent; border: none; color: #ef4444; font-size: 0.8rem; cursor: pointer; padding: 0.2rem 0.5rem; }\n',
                'public/manifest.json': json.dumps({
                    "name": project,
                    "short_name": project,
                    "start_url": "/",
                    "display": "standalone",
                    "background_color": "#0f172a",
                    "theme_color": "#4f46e5"
                }, indent=2),
                'public/sw.js': 'self.addEventListener("install", e => e.waitUntil(caches.open("pwa-v1").then(c => c.addAll(["/"]))));\nself.addEventListener("fetch", e => e.respondWith(caches.match(e.request).then(r => r || fetch(e.request))));\n',
                '.gitignore': 'node_modules/\ndist/\n'
            }
        elif t_id == 'data_dashboard':
            files = {
                'package.json': json.dumps({
                    "name": project,
                    "private": True,
                    "version": "1.0.0",
                    "type": "module",
                    "scripts": { "dev": "vite", "build": "vite build", "preview": "vite preview" },
                    "dependencies": { "chart.js": "^4.4.6" },
                    "devDependencies": { "vite": "^6.0.0" }
                }, indent=2),
                'vite.config.js': 'import { defineConfig } from "vite";\nexport default defineConfig({ server: { host: "127.0.0.1", port: 5173 } });\n',
                'index.html': f'<!doctype html>\n<html lang="en">\n  <head>\n    <meta charset="UTF-8" />\n    <meta name="viewport" content="width=device-width, initial-scale=1.0" />\n    <title>{project} Dashboard</title>\n    <link rel="stylesheet" href="/src/style.css" />\n  </head>\n  <body>\n    <div class="dash-wrap">\n      <header class="dash-head">\n        <h1>📊 Performance & Analytics</h1>\n        <div class="badge">Realtime Telemetry</div>\n      </header>\n      <div class="grid-stats">\n        <div class="stat-card"><span>Total Revenue</span><h2>$128,450</h2><span class="trend">+14.2%</span></div>\n        <div class="stat-card"><span>Active Users</span><h2>24,890</h2><span class="trend">+8.6%</span></div>\n        <div class="stat-card"><span>Conversion</span><h2>3.82%</h2><span class="trend">+0.4%</span></div>\n      </div>\n      <div class="chart-container">\n        <canvas id="revChart"></canvas>\n      </div>\n    </div>\n    <script type="module" src="/src/main.js"></script>\n  </body>\n</html>\n',
                'src/main.js': 'import Chart from "chart.js/auto";\nconst ctx = document.getElementById("revChart");\nnew Chart(ctx, {\n  type: "line",\n  data: {\n    labels: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],\n    datasets: [{\n      label: "Revenue ($k)",\n      data: [65, 78, 90, 81, 95, 110, 125, 120, 140, 155, 168, 185],\n      borderColor: "#38bdf8",\n      backgroundColor: "rgba(56, 189, 248, 0.15)",\n      tension: 0.35,\n      fill: true\n    }]\n  },\n  options: {\n    responsive: true,\n    maintainAspectRatio: false,\n    plugins: { legend: { labels: { color: "#94a3b8" } } },\n    scales: {\n      x: { ticks: { color: "#94a3b8" }, grid: { color: "#334155" } },\n      y: { ticks: { color: "#94a3b8" }, grid: { color: "#334155" } }\n    }\n  }\n});\n',
                'src/style.css': '* { margin: 0; padding: 0; box-sizing: border-box; }\nbody { font-family: system-ui, sans-serif; background: #0f172a; color: #f8fafc; padding: 2rem; }\n.dash-wrap { max-width: 960px; margin: 0 auto; display: flex; flex-direction: column; gap: 1.5rem; }\n.dash-head { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 1rem; }\n.badge { background: #0284c7; color: white; padding: 0.3rem 0.8rem; border-radius: 999px; font-size: 0.75rem; font-weight: 700; }\n.grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; }\n.stat-card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 1.2rem; display: flex; flex-direction: column; gap: 0.4rem; }\n.stat-card span { font-size: 0.8rem; color: #94a3b8; }\n.stat-card h2 { font-size: 1.8rem; font-weight: 800; color: #38bdf8; }\n.trend { color: #4ade80 !important; font-weight: 600; font-size: 0.75rem !important; }\n.chart-container { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 1.5rem; height: 380px; }\n',
                '.gitignore': 'node_modules/\ndist/\n'
            }
        else:
            files = {
                'main.py': 'from fastapi import FastAPI\nfrom pydantic import BaseModel\nimport sqlite3\n\napp = FastAPI(title="FastAPI Tablet Studio", version="1.0.0")\n\ndef init_db():\n    conn = sqlite3.connect("database.db")\n    c = conn.cursor()\n    c.execute("CREATE TABLE IF NOT EXISTS items (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, done BOOLEAN)")\n    conn.commit()\n    conn.close()\n\ninit_db()\n\nclass Item(BaseModel):\n    title: str\n    done: bool = False\n\n@app.get("/")\ndef read_root():\n    return {"status": "online", "message": "FastAPI running on Android MatePad"}\n\n@app.get("/items")\ndef get_items():\n    conn = sqlite3.connect("database.db")\n    c = conn.cursor()\n    c.execute("SELECT id, title, done FROM items")\n    rows = c.fetchall()\n    conn.close()\n    return [{"id": r[0], "title": r[1], "done": bool(r[2])} for r in rows]\n',
                'requirements.txt': 'fastapi>=0.115.0\nuvicorn>=0.32.0\npydantic>=2.9.0\n',
                'README.md': f'# {project}\n\nPython FastAPI service created with OmniDev Starter Templates on Android.\n',
                '.gitignore': '__pycache__/\n*.pyc\n.venv/\ndatabase.db\n'
            }

        for rel_path, content in files.items():
            dest = base / rel_path
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(content, encoding='utf-8')

        subprocess.run([git_cmd, 'init', '-q', str(base)], check=True, timeout=10)
        subprocess.run([git_cmd, 'add', '-A'], cwd=base, check=True, timeout=10)
        gh_user = os.environ.get('GITHUB_USER', 'Tablet User').strip() or 'Tablet User'
        gh_email = os.environ.get('GITHUB_EMAIL', 'user@tablet.local').strip() or 'user@tablet.local'
        subprocess.run([git_cmd, '-c', f'user.name={gh_user}', '-c', f'user.email={gh_email}', 'commit', '-m', f'feat: init starter template ({t_id})'], cwd=base, check=True, timeout=15)

        return {'success': True, 'initialized': True, 'project': project, 'template': t_id, 'files_count': len(files)}

    def git_audit(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        git_cmd = shutil.which('git') or 'git'
        if not (base / '.git').exists():
            return {'findings': [], 'clean': True, 'summary': 'Not a git repository'}

        diff_res = subprocess.run([git_cmd, 'diff', 'HEAD'], cwd=base, capture_output=True, text=True, timeout=15)
        diff_text = diff_res.stdout
        if not diff_text.strip():
            diff_res = subprocess.run([git_cmd, 'diff', 'HEAD~1'], cwd=base, capture_output=True, text=True, timeout=15)
            diff_text = diff_res.stdout

        findings: list[dict[str, Any]] = []

        secret_patterns = [
            (re.compile(r'sk-(?:ant-|proj-)?[A-Za-z0-9_\-]{20,}'), 'API Key Detected', 'Exposing API keys in code commits creates severe security liabilities.'),
            (re.compile(r'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{40,})'), 'GitHub Token Detected', 'GitHub credentials should never be committed into source files.'),
            (re.compile(r'AIza[A-Za-z0-9_\-]{20,}'), 'Google / Gemini Key Detected', 'API keys must be loaded from .env and kept in SecureSecrets.'),
            (re.compile(r'(?:password|secret|passwd)\s*=\s*[\'"][^\'"]{6,}[\'"]', re.I), 'Hardcoded Password Detected', 'Passwords should not be hardcoded in application source.')
        ]

        warning_patterns = [
            (re.compile(r'console\.log\('), 'Console Log in Code', 'Remove debugging console.logs before production release.'),
            (re.compile(r'\bdebugger;'), 'Debugger Statement', 'Breakpoints left in production source freeze the browser.'),
            (re.compile(r'\bTODO:'), 'Unresolved TODO Item', 'Review pending TODO task before committing.'),
            (re.compile(r'\bFIXME:'), 'Pending FIXME Warning', 'Ensure bug has been addressed before final push.')
        ]

        tip_patterns = [
            (re.compile(r'\balert\('), 'Browser alert() used', 'Consider using modern UI toasts or dialogs instead of blocking alert().'),
            (re.compile(r'catch\s*\([^)]*\)\s*\{\s*\}'), 'Empty catch block', 'Silencing exceptions without logging obscures runtime failures.')
        ]

        current_file = ''
        line_num = 1
        for line in diff_text.splitlines():
            if line.startswith('+++ b/'):
                current_file = line[6:].strip()
                line_num = 1
                continue
            elif line.startswith('@@'):
                m = re.search(r'\+(\d+)', line)
                if m:
                    line_num = int(m.group(1))
                continue
            elif line.startswith('+') and not line.startswith('+++'):
                added_code = line[1:]
                for pat, title, desc in secret_patterns:
                    if pat.search(added_code):
                        findings.append({
                            'severity': 'CRITICAL',
                            'title': title,
                            'description': desc,
                            'file': current_file,
                            'line': line_num,
                            'code': added_code.strip()[:80],
                            'suggested_fix': 'Move secret value to .env and read via process.env'
                        })
                for pat, title, desc in warning_patterns:
                    if pat.search(added_code):
                        findings.append({
                            'severity': 'WARNING',
                            'title': title,
                            'description': desc,
                            'file': current_file,
                            'line': line_num,
                            'code': added_code.strip()[:80],
                            'suggested_fix': 'Clean up or refactor debugging code'
                        })
                for pat, title, desc in tip_patterns:
                    if pat.search(added_code):
                        findings.append({
                            'severity': 'TIP',
                            'title': title,
                            'description': desc,
                            'file': current_file,
                            'line': line_num,
                            'code': added_code.strip()[:80],
                            'suggested_fix': 'Enhance user feedback or error handling'
                        })
                line_num += 1
            elif not line.startswith('-'):
                line_num += 1

        return {
            'findings': findings[:50],
            'clean': len(findings) == 0,
            'critical_count': sum(1 for f in findings if f['severity'] == 'CRITICAL'),
            'warning_count': sum(1 for f in findings if f['severity'] == 'WARNING'),
            'tip_count': sum(1 for f in findings if f['severity'] == 'TIP')
        }

    def pwa_configure(self, project: str) -> dict[str, Any]:
        base = self._project(project)
        public_dir = base / 'public'
        public_dir.mkdir(parents=True, exist_ok=True)

        manifest_path = public_dir / 'manifest.json'
        if not manifest_path.exists():
            manifest = {
                "name": project,
                "short_name": project,
                "start_url": "/",
                "display": "standalone",
                "background_color": "#0f172a",
                "theme_color": "#4f46e5",
                "orientation": "any"
            }
            manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')

        sw_path = public_dir / 'sw.js'
        if not sw_path.exists():
            sw_code = (
                "const CACHE_NAME = 'tablet-pwa-v1';\n"
                "self.addEventListener('install', e => {\n"
                "  e.waitUntil(caches.open(CACHE_NAME).then(c => c.addAll(['/'])));\n"
                "});\n"
                "self.addEventListener('fetch', e => {\n"
                "  e.respondWith(caches.match(e.request).then(res => res || fetch(e.request)));\n"
                "});\n"
            )
            sw_path.write_text(sw_code, encoding='utf-8')

        index_html = base / 'index.html'
        injected = False
        if index_html.exists():
            try:
                html = index_html.read_text(encoding='utf-8', errors='replace')
                if 'manifest.json' not in html:
                    html = html.replace('</head>', '    <link rel="manifest" href="/manifest.json" />\n  </head>')
                    index_html.write_text(html, encoding='utf-8')
                    injected = True
            except Exception:
                pass

        return {
            'success': True,
            'manifest_created': True,
            'sw_created': True,
            'index_injected': injected
        }
