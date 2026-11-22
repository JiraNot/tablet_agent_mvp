import json, os, pathlib, sys, tempfile, time, unittest
from unittest.mock import patch
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from core import LocalCore, BridgeError
from server import create_server
from urllib.request import Request,urlopen
from urllib.error import HTTPError
import threading

class CoreTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=pathlib.Path(self.temp.name)
        self.core=LocalCore(self.root/'data')
        self.core.create_project('demo')
    def test_create_project_and_credential_storage(self):
        self.assertEqual(self.core.projects()[0]['name'],'demo')
        self.assertEqual(len(self.core.token)>20,True)
        self.assertEqual(self.core.token,LocalCore(self.root/'data').token)
    def test_path_traversal_and_symlinks(self):
        with self.assertRaises(BridgeError):self.core.read_file('demo','../../etc/passwd')
        with self.assertRaises(BridgeError):self.core.read_file('demo','/etc/passwd')
        (self.core.workspaces/'demo'/'escape').symlink_to('/etc/passwd')
        with self.assertRaises(BridgeError):self.core.read_file('demo','escape')
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
        fake=bin_dir/'codex';fake.write_text('#!/bin/sh\nprintf \'{"type":"turn.completed"}\\n\'\n');fake.chmod(0o755)
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
        restarted=LocalCore(self.root/'data')
        self.assertEqual(restarted.task(r['id'])['status'],'INTERRUPTED')
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

if __name__=='__main__':unittest.main()
