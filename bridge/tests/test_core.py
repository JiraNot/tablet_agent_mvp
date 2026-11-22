import json, os, pathlib, socket, sys, tempfile, time, unittest
from unittest.mock import patch
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from core import LocalCore, BridgeError, CodexAdapter, REDACTION
from server import create_server
from urllib.request import Request,urlopen
from urllib.error import HTTPError
import threading

class CoreTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.temp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.core = LocalCore(self.root / 'data')
        self.core.create_project('demo')
    def test_create_project_and_credential_storage(self):
        self.assertEqual(self.core.projects()[0]['name'],'demo')
        self.assertEqual(len(self.core.token)>20,True)
        self.assertEqual(self.core.token,LocalCore(self.root/'data').token)
    def test_path_traversal_and_symlinks(self):
        with self.assertRaises(BridgeError):self.core.read_file('demo','../../etc/passwd')
        with self.assertRaises(BridgeError):self.core.read_file('demo','/etc/passwd')
        try:
            (self.core.workspaces/'demo'/'escape').symlink_to('/etc/passwd')
            with self.assertRaises(BridgeError):self.core.read_file('demo','escape')
        except (OSError, NotImplementedError):
            pass
    def test_config_read_write(self):
        self.core.write_file('demo','.env','HELLO=VALUE\n')
        self.assertEqual(self.core.read_file('demo','.env')['content'],'HELLO=VALUE\n')
        with self.assertRaises(BridgeError):self.core.read_file('demo','.git/config')
    def test_bad_project_name(self):
        for n in ['..','.','foo/bar','a b','']:
            with self.assertRaises(BridgeError):self.core.create_project(n)
    def test_plan_task_using_fake_codex(self):
        bin_dir=self.root/'bin';bin_dir.mkdir()
        fake=bin_dir/'codex'
        fake.write_text('#!/usr/bin/env python3\nimport json\nprint(json.dumps({"type":"item.completed","item":{"type":"agent_message","text":"Plan ready"}}))\nprint(json.dumps({"type":"turn.completed"}))\n')
        fake.chmod(0o755)
        if sys.platform == 'win32':
            (bin_dir / 'codex.cmd').write_text(f'@echo off\n"{sys.executable}" "{fake}" %*\n')
        with patch.dict(os.environ,{'PATH':str(bin_dir)+os.pathsep+os.environ.get('PATH','')}):
            r=self.core.start_task('demo','Write a plan','plan')
            end=time.monotonic()+5
            while time.monotonic()<end:
                t=self.core.task(r['id'])
                if t['status'] in {'PLANNED','FAILED','BLOCKED_SETUP'}:break
                time.sleep(.025)
        self.assertEqual(t['status'],'PLANNED',t)
        ev=self.core.events(t['id']);self.assertTrue(any(v['kind']=='message' for v in ev))
    def test_build_without_verifier_never_claims_completed(self):
        bin_dir=self.root/'bin';bin_dir.mkdir()
        fake=bin_dir/'codex'
        fake.write_text('#!/usr/bin/env python3\nimport json\nprint(json.dumps({"type":"turn.completed"}))\n')
        fake.chmod(0o755)
        if sys.platform == 'win32':
            (bin_dir / 'codex.cmd').write_text(f'@echo off\n"{sys.executable}" "{fake}" %*\n')
        with patch.dict(os.environ,{'PATH':str(bin_dir)+os.pathsep+os.environ.get('PATH','')}):
            r=self.core.start_task('demo','Make changes','build')
            end=time.monotonic()+5
            while time.monotonic()<end:
                t=self.core.task(r['id'])
                if t['status'] in {'NEEDS_VERIFICATION','FAILED','BLOCKED_SETUP'}:break
                time.sleep(.025)
        self.assertEqual(t['status'],'NEEDS_VERIFICATION',t)
    def test_restart_marks_work_interrupted(self):
        r={'id':'manual-id'}
        with self.core._db() as db:
            db.execute("INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",(r['id'],'demo','hello','codex','build','EXECUTING',time.time(),time.time()))
            db.execute("INSERT INTO approvals(id,task_id,type,target,status,created_at) VALUES(?,?,?,?,?,?)",('app-1',r['id'],'command_execution','npm test','PENDING',time.time()))
        restarted=LocalCore(self.root/'data')
        self.assertEqual(restarted.task(r['id'])['status'],'INTERRUPTED')
        self.assertEqual(restarted.approvals(r['id'])[0]['status'],'DENIED')
    def test_http_auth_and_loopback(self):
        server,bridge=create_server(self.root/'http',0)
        t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
        self.addCleanup(server.server_close);self.addCleanup(server.shutdown)
        port=server.server_address[1];url=f'http://127.0.0.1:{port}/v1/projects'
        with self.assertRaises(HTTPError) as e:urlopen(url,timeout=3)
        self.assertEqual(e.exception.code,401)
        req=Request(url,headers={'Authorization':'Bearer '+bridge.token})
        with urlopen(req,timeout=3) as res:
            self.assertEqual(json.loads(res.read())['projects'],[])
    def test_create_project_adds_default_gitignore(self):
        gi=self.core._project('demo')/'.gitignore'
        self.assertTrue(gi.is_file())
        txt=gi.read_text()
        self.assertIn('node_modules/',txt)
        self.assertIn('.env',txt)
        self.assertIn('!.env.example',txt)
    def test_start_preview_waits_for_ready_port(self):
        proj=self.core._project('demo')
        (proj/'package.json').write_text(json.dumps({'name':'demo','scripts':{'dev':'vite'}}))
        with socket.socket() as s:
            s.bind(('127.0.0.1',0))
            port=s.getsockname()[1]
        bin_dir=self.root/'npm_bin';bin_dir.mkdir()
        srv_script=self.root/'fake_srv.py'
        srv_script.write_text('import http.server,os,socketserver,time\ntime.sleep(0.35)\nport=int(os.environ["PORT"])\nwith socketserver.TCPServer(("127.0.0.1",port),http.server.SimpleHTTPRequestHandler) as httpd:\n    httpd.serve_forever()\n')
        npm_sh=bin_dir/'npm'
        npm_sh.write_text(f'#!/usr/bin/env sh\nexec "{sys.executable}" "{srv_script}"\n')
        npm_sh.chmod(0o755)
        if sys.platform=='win32':
            (bin_dir/'npm.cmd').write_text(f'@echo off\n"{sys.executable}" "{srv_script}"\n')
        self.addCleanup(lambda: self.core.stop_preview('demo'))
        with patch.dict(os.environ,{'PATH':str(bin_dir)+os.pathsep+os.environ.get('PATH','')}):
            res=self.core.start_preview('demo',port=port)
        self.assertEqual(res['status'],'running')
        self.assertEqual(res['url'],f'http://127.0.0.1:{port}')
    def test_codex_sandbox_never_bypassed_by_default_or_in_plan(self):
        codex=CodexAdapter()
        p=self.core._project('demo')
        default_build=codex.build_cmd(p,'Do build','build')
        self.assertIn('workspace-write',default_build)
        self.assertNotIn('danger-full-access',default_build)
        plan_with_override=codex.build_cmd(p,'Do plan','plan',sandbox_override='danger-full-access')
        self.assertIn('read-only',plan_with_override)
        self.assertNotIn('danger-full-access',plan_with_override)
        opt_in_build=codex.build_cmd(p,'Do build','build',sandbox_override='danger-full-access')
        self.assertIn('danger-full-access',opt_in_build)
    def test_redaction_and_event_gap_detection(self):
        sample='OPENAI_API_KEY=sk-proj-secret123456\nANTHROPIC_API_KEY=sk-ant-secret987654\nBearer mytokensecretvalue12\nMY_SECRET_TOKEN=topsecretvalue\nSafe line'
        redacted=REDACTION.sub('[REDACTED]',sample)
        self.assertNotIn('sk-proj-secret123456',redacted)
        self.assertNotIn('sk-ant-secret987654',redacted)
        self.assertNotIn('mytokensecretvalue12',redacted)
        self.assertNotIn('topsecretvalue',redacted)
        self.assertIn('Safe line',redacted)

        tid='gap-task-1'
        with self.core._db() as db:
            db.execute("INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",(tid,'demo','gap check','codex','plan','PLANNED',0.0,0.0))
            db.execute("INSERT INTO events(seq,task_id,timestamp,kind,message) VALUES(?,?,?,?,?)",(50,tid,0.0,'log','retained'))
        page=self.core.events_page(tid,after_seq=10)
        self.assertTrue(page['gap_detected'])
        self.assertEqual(page['min_seq'],50)
        self.assertEqual(len(page['events']),1)
    def test_orphan_process_reconciled_on_restart(self):
        import subprocess
        proc=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'],start_new_session=True)
        self.addCleanup(lambda: proc.kill() if proc.poll() is None else None)
        self.core._register_pid('task:orphan-1',proc,'python')
        restarted=LocalCore(self.root/'data')
        proc.wait(timeout=4)
        self.assertIsNotNone(proc.returncode)
        with restarted._db() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM process_pids').fetchone()[0],0)
    def test_orphan_skips_mismatched_cmdline(self):
        if not pathlib.Path(f'/proc/{os.getpid()}/cmdline').exists():
            self.skipTest('/proc not available')
        import subprocess
        proc=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'],start_new_session=True)
        self.addCleanup(lambda: proc.kill() if proc.poll() is None else None)
        self.assertTrue(self.core._terminate_pid_record(proc.pid,proc.pid,'nonexistent_binary_xyz') is False)
        self.assertIsNone(proc.poll())
        proc.terminate()
        proc.wait(timeout=4)
    def test_cancel_status_cannot_be_overwritten_and_long_output_bounded(self):
        tid='cancel-race-1'
        with self.core._db() as db:
            db.execute("INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",(tid,'demo','long','codex','build','EXECUTING',0.0,0.0))
        self.core.cancel(tid)
        self.core.set_status(tid,'COMPLETED',result='X'*20000)
        t=self.core.task(tid)
        self.assertEqual(t['status'],'CANCELLED')
        self.core.append(tid,'log','L'*20000)
        evts=self.core.events(tid,0)
        self.assertLessEqual(len(evts[-1]['message']),6000)
    def test_destructive_command_detection_and_approval_gate(self):
        self.assertTrue(self.core.is_destructive_command('rm -rf /tmp/build'))
        self.assertTrue(self.core.is_destructive_command('git reset --hard HEAD~1'))
        self.assertTrue(self.core.is_destructive_command('git push origin main'))
        self.assertFalse(self.core.is_destructive_command('npm run build'))
        self.assertFalse(self.core.is_destructive_command('git status --porcelain'))
    def test_git_checkpoint_and_rollback(self):
        self.core.write_file('demo','index.js','console.log("v1");\n')
        sha=self.core.create_git_checkpoint('demo','v1')
        self.assertIsNotNone(sha)
        self.core.write_file('demo','index.js','console.log("broken-v2");\n')
        self.core.write_file('demo','untracked.txt','junk\n')
        self.assertFalse(self.core.git_status('demo')['clean'])
        rb=self.core.git_rollback('demo')
        self.assertTrue(rb['success'])
        self.assertTrue(self.core.git_status('demo')['clean'])
        self.assertEqual(self.core.read_file('demo','index.js')['content'],'console.log("v1");\n')
    def test_disk_full_atomic_write_leaves_original_intact(self):
        self.core.write_file('demo','safe.txt','original-content')
        with patch('os.fsync', side_effect=OSError(28, 'No space left on device')):
            with self.assertRaises(OSError):
                self.core.write_file('demo','safe.txt','corrupted-partial-write')
        self.assertEqual(self.core.read_file('demo','safe.txt')['content'],'original-content')
        leftovers = [p for p in (self.core._project('demo')).iterdir() if p.name.endswith('.tmp')]
        self.assertEqual(leftovers, [])
    def test_corrupt_sqlite_db_raises_cleanly(self):
        bad_root = self.root / 'corrupt_data'
        bad_root.mkdir(parents=True, exist_ok=True)
        (bad_root / 'state.sqlite3').write_bytes(b'NOT_A_VALID_SQLITE_HEADER_1234567890')
        import sqlite3
        with self.assertRaises(sqlite3.DatabaseError):
            LocalCore(bad_root)

if __name__=='__main__':unittest.main()
