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
REDACTION = re.compile(r'(sk-[A-Za-z0-9_\-]{16,}|gh[pousr]_[A-Za-z0-9]{16,}|AIza[A-Za-z0-9_-]{20,})')

class BridgeError(Exception):
    def __init__(self, message: str, status: int = 400):
        self.status = status
        super().__init__(message)

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
            ''')

    def _recover_interrupted(self):
        with self._db() as db:
            db.execute("UPDATE tasks SET status='INTERRUPTED', error='Bridge restarted; CLI process status unknown', updated_at=? WHERE status NOT IN ('PLANNED','COMPLETED','NEEDS_VERIFICATION','FAILED','CANCELLED','BLOCKED_SETUP','INTERRUPTED')", (time.time(),))

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

    def events(self, task_id: str, after_seq: int = 0) -> list[dict[str, Any]]:
        self.task(task_id)
        with self._db() as db:
            return [dict(r) for r in db.execute('SELECT * FROM events WHERE task_id=? AND seq>? ORDER BY seq LIMIT 120',(task_id,max(0,after_seq)))]

    def start_task(self, project: str, prompt: str, mode: str = 'build', agent: str = 'codex') -> dict[str, Any]:
        self._project(project)
        if mode not in ALLOWED_MODES:
            raise BridgeError('Mode must be plan or build')
        if agent != 'codex':
            raise BridgeError('This runnable MVP supports Codex only; other adapters are planned')
        if not prompt.strip() or len(prompt) > 24000:
            raise BridgeError('Prompt length must be 1-24000 characters')
        task_id = str(uuid.uuid4()); now=time.time()
        # Prevent concurrent agents from editing the same project, including queued jobs.
        with self.lock:
            with self._db() as db:
                busy=db.execute("SELECT id FROM tasks WHERE project=? AND status IN ('QUEUED','PLANNING','EXECUTING','VERIFYING','REPAIRING') LIMIT 1", (project,)).fetchone()
                if busy:
                    raise BridgeError('Another task is active in this project; finish or cancel it first', 409)
                db.execute('INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)', (task_id,project,prompt,agent,mode,'QUEUED',now,now))
        self.append(task_id,'status','QUEUED')
        threading.Thread(target=self._worker, args=(task_id,), daemon=True, name=f'task-{task_id[:8]}').start()
        return self.task(task_id)

    def _run(self, task_id: str, cmd: list[str], cwd: Path, timeout: float, phase: str) -> tuple[int,str]:
        self.append(task_id,'log',f'Starting {phase}: {cmd[0]} (arguments hidden)')
        proc = subprocess.Popen(cmd, cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, errors='replace', bufsize=1,
                                start_new_session=True)
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
                        elif kind=='turn.completed':
                            done_marker=True
                        elif kind in {'turn.failed','error'}:
                            fail_marker=True
                            self.append(task_id,'error',json.dumps(data,ensure_ascii=False)[:1800])
                    except json.JSONDecodeError:
                        self.append(task_id,'log',line[:800])
                else:
                    lines.append(line[:800]); self.append(task_id,'verification',line[:1200])
            proc.wait(timeout=2)
            if phase == 'agent' and proc.returncode == 0 and (not done_marker or fail_marker):
                self.append(task_id, 'error', 'Codex did not complete successfully')
                return 90, '\n'.join(lines)[-6000:]
            return proc.returncode or 0, '\n'.join(lines)[-6000:]
        finally:
            done.set()
            if proc.stdout: proc.stdout.close()
            with self.lock:
                if self.procs.get(task_id) is proc: self.procs.pop(task_id,None)

    def _kill(self, proc: subprocess.Popen):
        if proc.poll() is not None: return
        try: os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError: return
        try: proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try: os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError: pass

    def cancel(self, task_id: str):
        rec=self.task(task_id)
        if rec['status'] in TERMINAL: return rec
        self.set_status(task_id,'CANCELLED')
        with self.lock: proc=self.procs.get(task_id)
        if proc: self._kill(proc)
        return self.task(task_id)

    def _thread_for_project(self, project: str) -> str | None:
        with self._db() as db:
            row=db.execute('SELECT session_id FROM agent_threads WHERE project=? AND agent=?',(project,'codex')).fetchone()
        return row['session_id'] if row else None

    def _remember_thread(self, project: str, task_id: str):
        thread_id=self.thread_candidates.pop(task_id,None)
        if not thread_id:return
        with self._db() as db:
            db.execute('INSERT INTO agent_threads(project,agent,session_id,updated_at) VALUES(?,?,?,?) ON CONFLICT(project) DO UPDATE SET session_id=excluded.session_id, updated_at=excluded.updated_at', (project,'codex',thread_id,time.time()))

    def _agent_cmd(self, project: str, prompt: str, mode: str) -> list[str]:
        command=['codex','exec','--json','--sandbox','read-only' if mode=='plan' else 'workspace-write','--skip-git-repo-check']
        thread=self._thread_for_project(project)
        if thread:command += ['resume',thread]
        command.append(prompt)
        return command

    def _worker(self, task_id: str):
        rec=self.task(task_id); project_path=self._project(rec['project'])
        if self.task(task_id)['status']=='CANCELLED': return
        if not shutil.which('codex'):
            self.set_status(task_id,'BLOCKED_SETUP', error='Codex CLI not found in Termux PATH'); return
        mode=rec['mode']
        self.set_status(task_id, 'PLANNING' if mode=='plan' else 'EXECUTING')
        policy = ('Plan only. Do not edit files or run mutating commands. Provide concise steps and test strategy.'
                  if mode=='plan' else
                  'You may edit files under the current workspace. Keep changes scoped; run relevant tests. Never read or disclose .env files, keys, or credentials. Do not git push or deploy. Explain what changed and tests actually executed.')
        cmd=self._agent_cmd(rec['project'], f'{policy}\n\nUser task:\n{rec["prompt"]}',mode)
        try:
            rc, output=self._run(task_id,cmd,project_path,timeout=1800,phase='agent')
            if self.task(task_id)['status']=='CANCELLED': return
            if rc!=0:
                self.set_status(task_id,'FAILED',error=f'Codex exited with status {rc}',result=output);return
            self._remember_thread(rec['project'],task_id)
            if mode=='plan':
                self.set_status(task_id,'PLANNED',result=output);return
            self.set_status(task_id,'VERIFYING',result=output)
            pkg=project_path/'package.json'
            command=None
            if pkg.is_file():
                try:
                    scripts=json.loads(pkg.read_text()).get('scripts',{})
                    if 'build' in scripts and shutil.which('npm'):
                        command=['npm','run','build']
                    elif 'test' in scripts and shutil.which('npm'):
                        command=['npm','test','--','--runInBand'] if 'jest' in scripts.get('test','') else ['npm','test']
                except (ValueError, OSError):pass
            if not command:
                self.set_status(task_id,'NEEDS_VERIFICATION',verification='No supported npm build/test script found; do not claim verified success')
                return
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
                fix_cmd=self._agent_cmd(rec['project'],fix_prompt,'build')
                rc,repair_output=self._run(task_id,fix_cmd,project_path,timeout=1200,phase='agent')
                if self.task(task_id)['status']=='CANCELLED':return
                if rc:
                    self.set_status(task_id,'FAILED',error='Agent repair attempt failed',verification=verify_output,result=repair_output);return
                self._remember_thread(rec['project'],task_id)
                self.set_status(task_id,'VERIFYING')
        except Exception as exc:
            if self.task(task_id)['status']!='CANCELLED':
                self.set_status(task_id,'FAILED',error=f'{type(exc).__name__}: {exc}'[:1000])

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
        proc=subprocess.Popen(['npm','run','dev'], cwd=base, env=env,
                              stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
                              start_new_session=True)
        with self.lock:self.previews[project]={'process':proc,'port':port}
        return {'status':'starting','url':f'http://127.0.0.1:{port}', 'note':'Framework must bind 127.0.0.1 and respect PORT; some scripts need manual configuration'}

    def stop_preview(self,project:str):
        with self.lock: p=self.previews.pop(project,None)
        if p:self._kill(p['process'])
        return {'status':'stopped'}
