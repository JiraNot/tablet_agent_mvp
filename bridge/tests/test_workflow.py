import json, os, pathlib, sys, tempfile, time, unittest
from unittest.mock import patch
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from core import LocalCore

class WorkflowTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.temp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.core = LocalCore(self.root / 'work')
        self.core.create_project('project')
        self.bin = self.root / 'bin'; self.bin.mkdir()
        codex = self.bin / 'codex'
        codex.write_text('''#!/usr/bin/env python3
import json,os,sys
marker=os.environ['TABLET_AGENT_TEST_LOG']
with open(marker,'a') as f:f.write(json.dumps(sys.argv[1:])+"\\n")
print(json.dumps({'type':'thread.started','thread_id':'a-valid-thread-identifier-123456'}))
print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':'Finished work'}}))
print(json.dumps({'type':'turn.completed'}))
''')
        codex.chmod(0o755)
        npm = self.bin / 'npm'
        npm.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
p=pathlib.Path(os.environ['TABLET_AGENT_NPM_MARKER'])
if not p.exists():
    p.write_text('first build failed')
    print('Build failure: synthetic test')
    sys.exit(1)
print('Build succeeded')
''')
        npm.chmod(0o755)
        if sys.platform == 'win32':
            (self.bin / 'codex.cmd').write_text(f'@echo off\n"{sys.executable}" "{codex}" %*\n')
            (self.bin / 'npm.cmd').write_text(f'@echo off\n"{sys.executable}" "{npm}" %*\n')
        (self.core.workspaces / 'project' / 'package.json').write_text(json.dumps({'scripts':{'build':'echo done'}}))
        self.env = {'PATH': str(self.bin) + os.pathsep + os.environ.get('PATH',''),
                 'TABLET_AGENT_TEST_LOG': str(self.root / 'agent_calls'),
                 'TABLET_AGENT_NPM_MARKER': str(self.root / 'npm_marker')}
    def until_terminal(self,task_id):
        end=time.monotonic()+8
        while time.monotonic()<end:
            status=self.core.task(task_id)['status']
            if status in {'COMPLETED','FAILED','NEEDS_VERIFICATION','PLANNED'}:
                return status
            time.sleep(.04)
        return self.core.task(task_id)['status']
    def test_build_auto_repairs_and_persists_thread(self):
        with patch.dict(os.environ,self.env):
            task=self.core.start_task('project','Implement dashboard','build')
            result=self.until_terminal(task['id'])
        self.assertEqual(result,'COMPLETED',self.core.task(task['id']))
        self.assertEqual(self.core._thread_for_project('project'),'a-valid-thread-identifier-123456')
        events=self.core.events(task['id'])
        self.assertTrue(any(e['message']=='REPAIRING' for e in events))
        calls=[json.loads(t) for t in (self.root/'agent_calls').read_text().splitlines()]
        self.assertEqual(len(calls),2)
        self.assertIn('resume',calls[1])
    def test_followup_reuses_session_id(self):
        with patch.dict(os.environ,self.env):
            first=self.core.start_task('project','First plan','plan')
            self.assertEqual(self.until_terminal(first['id']),'PLANNED')
            second=self.core.start_task('project','Second plan','plan')
            self.assertEqual(self.until_terminal(second['id']),'PLANNED')
        calls=[json.loads(t) for t in (self.root/'agent_calls').read_text().splitlines()]
        self.assertNotIn('resume',calls[0])
        self.assertIn('resume',calls[1])

    def test_followup_in_same_task_appends_user_turn_and_keeps_task_id(self):
        with patch.dict(os.environ,self.env):
            first=self.core.start_task('project','First plan','plan')
            self.assertEqual(self.until_terminal(first['id']),'PLANNED')
            continued=self.core.start_task('project','Follow-up question','plan',task_id=first['id'])
            self.assertEqual(continued['id'],first['id'])
            self.assertEqual(self.until_terminal(first['id']),'PLANNED')
            tasks=self.core.tasks('project')
            self.assertEqual(len(tasks),1)
            self.assertEqual(tasks[0]['prompt'],'First plan')
            events=self.core.events_page(first['id'],0)['events']
            user_events=[e for e in events if e['kind']=='user']
            self.assertEqual(len(user_events),1)
            self.assertEqual(user_events[0]['message'],'Follow-up question')

if __name__=='__main__':unittest.main()
