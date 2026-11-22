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
import subprocess
import threading
import time
import uuid
from pathlib import Path
from contextlib import contextmanager
from typing import Any
from urllib.parse import urlparse

ALLOWED_MODES = {'plan', 'build'}
TERMINAL = {'PLANNED', 'COMPLETED', 'NEEDS_VERIFICATION', 'FAILED', 'CANCELLED', 'BLOCKED_SETUP', 'INTERRUPTED'}
MAX_LOG_CHARS = 6000
MAX_FILE_BYTES = 256 * 1024
MAX_EVENTS_PER_TASK = 2500
NAME_RE = re.compile(r'^[a-zA-Z0-9][a-zA-Z0-9._-]{0,69}$')
REDACTION = re.compile(
    r'(sk-(?:ant-|proj-)?[A-Za-z0-9_\-]{12,}'
    r'|gh[pousr]_[A-Za-z0-9]{16,}'
    r'|AIza[A-Za-z0-9_\-]{20,}'
    r'|Bearer\s+[A-Za-z0-9._\-]{12,}'
    r'|(?:OPENAI|ANTHROPIC|GEMINI|GOOGLE|GITHUB|GH|AWS|STRIPE|DATABASE|[A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|API_KEY))[A-Z0-9_]*\s*=\s*["\']?[^\s"\'\n]{4,})'
)

class BridgeError(Exception):
    def __init__(self, message: str, status: int = 400):
        self.status = status
        super().__init__(message)

class BaseAgentAdapter:
    def __init__(self, name: str, display_name: str, binary: str, can_plan: bool, can_build: bool, supports_resume: bool, models: list[str]):
        self.name = name
        self.display_name = display_name
        self.binary = binary
        self.can_plan = can_plan
        self.can_build = can_build
        self.supports_resume = supports_resume
        self.models = models

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

    def build_cmd(self, project_path: Path, prompt: str, mode: str, thread_id: str | None = None, model: str = '', sandbox_override: str | None = None) -> list[str]:
        bin_cmd = self._resolve_binary(project_path)
        if mode == 'plan':
            sandbox = 'read-only'
        elif sandbox_override == 'danger-full-access':
            sandbox = 'danger-full-access'
        else:
            sandbox = 'workspace-write'
        cmd = bin_cmd + ['exec', '--json', '--sandbox', sandbox, '--skip-git-repo-check']
        if thread_id and self.supports_resume:
            cmd += ['resume', thread_id]
        if model and model != 'default':
            cmd += ['--model', model]
        cmd.append(prompt)
        return cmd

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
        self.thread_candidates: dict[str, str] = {}
        self.previews: dict[str, dict[str, Any]] = {}
        self.approval_events: dict[str, threading.Event] = {}
        self.adapters: dict[str, BaseAgentAdapter] = {
            'codex': CodexAdapter(),
            'claude': ClaudeCodeAdapter(),
            'opencode': OpenCodeAdapter(),
            'antigravity': AntigravityAdapter()
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
        db.execute('PRAGMA journal_mode=WAL')
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
        with self._db() as db:
            db.execute('DELETE FROM process_pids')
            db.execute("UPDATE tasks SET status='INTERRUPTED', error='Bridge restarted; CLI process status unknown', updated_at=? WHERE status NOT IN ('PLANNED','COMPLETED','NEEDS_VERIFICATION','FAILED','CANCELLED','BLOCKED_SETUP','INTERRUPTED')", (now,))
            db.execute("UPDATE approvals SET status='DENIED', resolved_at=? WHERE status='PENDING'", (now,))

    def get_settings(self) -> dict[str, Any]:
        with self._db() as db:
            rows = db.execute('SELECT key, value FROM settings').fetchall()
        raw = {r['key']: r['value'] for r in rows}
        return {
            'codex_proot_sandbox_bypass': raw.get('codex_proot_sandbox_bypass', 'false') == 'true'
        }

    def update_settings(self, patch: dict[str, Any]) -> dict[str, Any]:
        if 'codex_proot_sandbox_bypass' in patch:
            val = patch['codex_proot_sandbox_bypass']
            if not isinstance(val, bool):
                raise BridgeError('codex_proot_sandbox_bypass must be a boolean')
            with self._db() as db:
                db.execute(
                    'INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value',
                    ('codex_proot_sandbox_bypass', 'true' if val else 'false')
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

    def create_project(self, name: str, git_url: str = '') -> dict[str, Any]:
        if not NAME_RE.fullmatch(name) or name in {'.', '..'}:
            raise BridgeError('Invalid project name')
        target = self.workspaces / name
        if target.exists():
            raise BridgeError('Project already exists', 409)
        if git_url:
            u = urlparse(git_url)
            if u.scheme != 'https' or u.hostname != 'github.com' or u.username or u.password or u.port:
                raise BridgeError('Only credential-free https://github.com URLs are accepted')
            if not re.fullmatch(r'/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?/?', u.path):
                raise BridgeError('Expected a GitHub repository URL')
            if u.query or u.fragment:
                raise BridgeError('Git URL must not contain a query or fragment')
            try:
                subprocess.run(['git', 'clone', '--', git_url, str(target)], timeout=160, capture_output=True, check=True)
            except (OSError, subprocess.SubprocessError) as e:
                shutil.rmtree(target, ignore_errors=True)
                raise BridgeError(f'Clone failed: {str(e)[:300]}', 422)
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

    def write_file(self, project: str, rel: str, content: str):
        if len(content.encode('utf-8')) > MAX_FILE_BYTES:
            raise BridgeError('File too large (256 KiB limit)', 413)
        p = self._file(project, rel)
        if not p.parent.is_dir():
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

    def append(self, task: str, kind: str, message: str):
        msg = REDACTION.sub('[REDACTED]', str(message))[:MAX_LOG_CHARS]
        with self._db() as db:
            db.execute('INSERT INTO events(task_id,timestamp,kind,message) VALUES(?,?,?,?)', (task, time.time(), kind, msg))
            db.execute('DELETE FROM events WHERE task_id=? AND seq NOT IN (SELECT seq FROM events WHERE task_id=? ORDER BY seq DESC LIMIT ?)',(task,task,MAX_EVENTS_PER_TASK))

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
        return {'events': items, 'has_more': has_more, 'gap_detected': gap_detected, 'min_seq': min_seq}

    def events(self, task_id: str, after_seq: int = 0) -> list[dict[str, Any]]:
        return self.events_page(task_id, after_seq)['events']

    def available_agents(self) -> list[dict[str, Any]]:
        return [{
            'name': a.name,
            'display_name': a.display_name,
            'binary': a.binary,
            'available': a.is_available(),
            'can_plan': a.can_plan,
            'can_build': a.can_build,
            'supports_resume': a.supports_resume,
            'models': a.models
        } for a in self.adapters.values()]

    def start_task(self, project: str, prompt: str, mode: str = 'build', agent: str = 'codex', model: str = '') -> dict[str, Any]:
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
        task_id = str(uuid.uuid4()); now=time.time()
        # Prevent concurrent agents from editing the same project, including queued jobs.
        with self.lock:
            with self._db() as db:
                busy=db.execute("SELECT id FROM tasks WHERE project=? AND status IN ('QUEUED','PLANNING','WAITING_APPROVAL','EXECUTING','VERIFYING','REPAIRING') LIMIT 1", (project,)).fetchone()
                if busy:
                    raise BridgeError('Another task is active in this project; finish or cancel it first', 409)
                db.execute('INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)', (task_id,project,prompt,agent,mode,'QUEUED',now,now))
        self.append(task_id,'status','QUEUED')
        threading.Thread(target=self._worker, args=(task_id, model), daemon=True, name=f'task-{task_id[:8]}').start()
        return self.task(task_id)

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
        done_marker=False
        fail_marker=False
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
                            elif item.get('type') in {'command_execution','file_change'}:
                                self.append(task_id,'tool',f"{item.get('type')}: {item.get('status','')}")
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
                        self.append(task_id,'log',line[:800])
                else:
                    lines.append(line[:800]); self.append(task_id,'verification',line[:1200])
            proc.wait(timeout=2)
            if phase == 'agent' and proc.returncode == 0 and (not done_marker or fail_marker):
                self.append(task_id, 'error', 'Agent CLI did not report turn completion')
                return 90, '\n'.join(lines)[-6000:]
            return proc.returncode or 0, '\n'.join(lines)[-6000:]
        finally:
            done.set()
            if proc.stdout: proc.stdout.close()
            self._unregister_pid(pid_key)
            with self.lock:
                if self.procs.get(task_id) is proc: self.procs.pop(task_id,None)

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
            with self._db() as db:
                for r in db.execute("SELECT id FROM approvals WHERE task_id=? AND status='PENDING'", (task_id,)):
                    evt = self.approval_events.get(r['id'])
                    if evt: evt.set()
                db.execute("UPDATE approvals SET status='DENIED', resolved_at=? WHERE task_id=? AND status='PENDING'", (time.time(), task_id))
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

    def _worker(self, task_id: str, model: str = ''):
        rec=self.task(task_id); project_path=self._project(rec['project'])
        if self.task(task_id)['status']=='CANCELLED': return
        agent_name = rec['agent']
        adapter = self.adapters.get(agent_name)
        if not adapter or not adapter.is_available():
            self.set_status(task_id,'BLOCKED_SETUP', error=f'{adapter.display_name if adapter else agent_name} CLI not found in runtime PATH'); return
        mode=rec['mode']
        self.set_status(task_id, 'PLANNING' if mode=='plan' else 'EXECUTING')
        policy = ('Plan only. Do not edit files or run mutating commands. Provide concise steps and test strategy.'
                  if mode=='plan' else
                  'You may edit files under the current workspace. Keep changes scoped; run relevant tests. Never read or disclose .env files, keys, or credentials. Do not git push or deploy. Explain what changed and tests actually executed.')
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
        thread = self._thread_for_project(rec['project'], agent_name)
        prompt_text = f'{policy}{instructions}\n\nUser task:\n{rec["prompt"]}'
        if agent_name == 'codex' and mode == 'build' and self.get_settings().get('codex_proot_sandbox_bypass'):
            cmd = adapter.build_cmd(project_path, prompt_text, mode, thread, model, sandbox_override='danger-full-access')
        else:
            cmd = adapter.build_cmd(project_path, prompt_text, mode, thread, model)
        try:
            rc, output=self._run(task_id,cmd,project_path,timeout=1800,phase='agent')
            if self.task(task_id)['status']=='CANCELLED': return
            if agent_name == 'codex' and mode == 'build' and 'cannot establish app-server socket mount isolation' in output:
                self.append(task_id, 'error', 'Codex Linux sandbox is blocked in Android PRoot. Enable the opt-in Codex PRoot Build toggle in Settings -> Security to allow workspace writes.')
            if rc!=0:
                self.set_status(task_id,'FAILED',error=f'{adapter.display_name} exited with status {rc}',result=output);return
            self._remember_thread(rec['project'],task_id, agent_name)
            if mode=='plan':
                self.set_status(task_id,'PLANNED',result=output);return
            self.set_status(task_id,'VERIFYING',result=output)
            pkg=project_path/'package.json'
            command=None
            needs_install=False
            if pkg.is_file():
                try:
                    pkg_json=json.loads(pkg.read_text())
                    scripts=pkg_json.get('scripts',{})
                    has_deps=bool(pkg_json.get('dependencies') or pkg_json.get('devDependencies'))
                    needs_install=has_deps and not (project_path/'node_modules').is_dir()
                    if 'build' in scripts and shutil.which('npm'):
                        command=['npm','run','build']
                    elif 'test' in scripts and shutil.which('npm'):
                        command=['npm','test','--','--runInBand'] if 'jest' in scripts.get('test','') else ['npm','test']
                except (ValueError, OSError):pass
            if not command:
                self.set_status(task_id,'NEEDS_VERIFICATION',verification='No supported npm build/test script found; do not claim verified success')
                return
            if needs_install and shutil.which('npm'):
                rc_inst, inst_out = self._run(task_id, ['npm', 'install', '--no-audit', '--no-fund'], project_path, timeout=300, phase='verify')
                if self.task(task_id)['status']=='CANCELLED': return
                if rc_inst != 0:
                    self.set_status(task_id,'FAILED',error=f'npm install failed (exit {rc_inst})',verification=inst_out); return
            for attempt in range(2):
                rc, verify_output=self._run(task_id, command, project_path, timeout=300, phase='verify')
                if self.task(task_id)['status']=='CANCELLED':return
                if rc==0:
                    self.set_status(task_id,'COMPLETED',verification='Verified: '+ ' '.join(command)+'\n'+verify_output[-2000:]);return
                if attempt==1:
                    self.set_status(task_id,'FAILED',error=f'Build verification failed after repair (exit {rc})',verification=verify_output);return
                self.set_status(task_id,'REPAIRING',verification=verify_output)
                fix_prompt=(f'Your previous task for project {rec["project"]} was not verified.\n'
                            f'Command: {" ".join(command)}\nExit code: {rc}\nError log:\n{REDACTION.sub("[REDACTED]",verify_output)[-2400:]}\n'
                            'Fix these errors within the workspace, rerun your own checks, and report changes. '
                            'Do not access credentials or deploy.')
                fix_thread = self._thread_for_project(rec['project'], agent_name)
                fix_cmd = adapter.build_cmd(project_path, fix_prompt, 'build', fix_thread, model)
                rc,repair_output=self._run(task_id,fix_cmd,project_path,timeout=1200,phase='agent')
                if self.task(task_id)['status']=='CANCELLED':return
                if rc:
                    self.set_status(task_id,'FAILED',error='Agent repair attempt failed',verification=verify_output,result=repair_output);return
                self._remember_thread(rec['project'],task_id, agent_name)
                self.set_status(task_id,'VERIFYING')
        except Exception as exc:
            try:
                if self.task(task_id)['status']!='CANCELLED':
                    self.set_status(task_id,'FAILED',error=f'{type(exc).__name__}: {exc}'[:1000])
            except Exception:
                pass

    def preview(self, project: str):
        self._project(project)
        with self.lock:
            item=self.previews.get(project)
            if not item: return {'status':'stopped'}
            p=item['process']
            if p.poll() is None:
                return {'status':'running','url':f'http://127.0.0.1:{item["port"]}'}
            self.previews.pop(project,None)
        return {'status':'stopped','exit_code':p.returncode}

    def start_preview(self, project: str, port: int | None = None):
        base=self._project(project)
        current=self.preview(project)
        if current['status']=='running':return current
        pkg=base/'package.json'
        if not pkg.is_file():raise BridgeError('package.json not found; preview requires npm project')
        try: script=json.loads(pkg.read_text())['scripts']['dev']
        except (OSError,ValueError,KeyError,TypeError):raise BridgeError('package.json scripts.dev is required')
        if not shutil.which('npm'):raise BridgeError('npm is not installed')
        if port is None:port=5173 if 'vite' in script.lower() else 3000
        if not 1024<=port<=65535:raise BridgeError('Port must be 1024-65535')
        env=os.environ.copy();env.update({'PORT':str(port),'HOST':'127.0.0.1','HOSTNAME':'127.0.0.1'})
        # npm respects cwd and script; no shell construction in our process.
        npm_cmd = shutil.which('npm') or 'npm'
        dev_cmd = [npm_cmd, 'run', 'dev']
        if 'vite' in script.lower() and '--host' not in script:
            dev_cmd += ['--', '--host', '127.0.0.1', '--port', str(port)]
        proc=subprocess.Popen(dev_cmd, cwd=base, env=env,
                              stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
                              start_new_session=True)
        self._register_pid(f'preview:{project}', proc, Path(npm_cmd).stem or 'npm')
        with self.lock:self.previews[project]={'process':proc,'port':port}
        deadline = time.monotonic() + 12.0
        status = 'starting'
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                with self.lock:self.previews.pop(project,None)
                raise BridgeError(f'Preview server exited early (code {proc.returncode})', 500)
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=0.2):
                    status = 'running'
                    break
            except OSError:
                time.sleep(0.15)
        return {'status':status,'url':f'http://127.0.0.1:{port}', 'note':'Framework must bind 127.0.0.1 and respect PORT; some scripts need manual configuration'}

    def stop_preview(self,project:str):
        with self.lock: p=self.previews.pop(project,None)
        if p:self._kill(p['process'])
        self._unregister_pid(f'preview:{project}')
        return {'status':'stopped'}

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
                return {'is_git': False, 'branch': '', 'files': [], 'clean': True, 'error': res.stderr.strip()}
            lines = [l.rstrip() for l in res.stdout.splitlines() if l.strip()]
            files = []
            for l in lines:
                status_code = l[:2]
                filename = l[3:].strip()
                files.append({'status': status_code, 'file': filename})
            return {
                'is_git': True,
                'branch': branch,
                'files': files,
                'clean': len(files) == 0,
                'uncommitted_count': len(files)
            }
        except Exception as e:
            return {'is_git': False, 'branch': '', 'files': [], 'clean': True, 'error': str(e)}

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
            res_commit = subprocess.run([git_cmd, 'commit', '-m', msg], cwd=base, capture_output=True, text=True, timeout=15)
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
