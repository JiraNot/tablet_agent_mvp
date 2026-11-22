import json, pathlib, sys, tempfile, unittest
from urllib.request import Request, urlopen
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from core import LocalCore, BridgeError, CodexAdapter, ClaudeCodeAdapter, OpenCodeAdapter, AntigravityAdapter
from server import create_server

class AdapterTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.temp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.core = LocalCore(self.root / 'data')
        self.core.create_project('proj')

    def test_adapter_command_building(self):
        p = pathlib.Path('/fake/project')
        codex = CodexAdapter()
        cmd_plan = codex.build_cmd(p, 'Plan this', 'plan')
        self.assertIn('--sandbox', cmd_plan)
        self.assertIn('read-only', cmd_plan)
        self.assertNotIn('resume', cmd_plan)

        cmd_resume = codex.build_cmd(p, 'Continue', 'build', thread_id='th-123', model='gpt-6.2-sol')
        self.assertIn('resume', cmd_resume)
        self.assertIn('th-123', cmd_resume)
        self.assertIn('--model', cmd_resume)
        self.assertIn('gpt-6.2-sol', cmd_resume)

        claude = ClaudeCodeAdapter()
        cmd_claude = claude.build_cmd(p, 'Claude task', 'build', thread_id='cl-456', model='claude-3-7-sonnet')
        self.assertIn('--resume', cmd_claude)
        self.assertIn('cl-456', cmd_claude)
        self.assertIn('--model', cmd_claude)
        self.assertIn('claude-3-7-sonnet', cmd_claude)

        opencode = OpenCodeAdapter()
        cmd_open = opencode.build_cmd(p, 'OpenCode task', 'plan', thread_id='ignored')
        self.assertNotIn('--resume', cmd_open)
        self.assertIn('--mode', cmd_open)
        self.assertIn('plan', cmd_open)

        agy = AntigravityAdapter()
        cmd_agy = agy.build_cmd(p, 'Antigravity task', 'build', thread_id='ag-789', model='gemini-4-neon-high')
        self.assertIn('--print=Antigravity task', cmd_agy)
        self.assertIn('stream-json', cmd_agy)
        self.assertNotIn('--mode', cmd_agy)
        self.assertIn('--conversation', cmd_agy)
        self.assertIn('ag-789', cmd_agy)
        self.assertIn('--model', cmd_agy)
        self.assertIn('gemini-4-neon-high', cmd_agy)

        cmd_agy_plan = agy.build_cmd(p, 'Plan task', 'plan')
        self.assertIn('--mode', cmd_agy_plan)
        self.assertIn('plan', cmd_agy_plan)
        self.assertIn('--print=Plan task', cmd_agy_plan)
        self.assertNotIn('--conversation', cmd_agy_plan)

    def test_available_agents_api(self):
        agents = self.core.available_agents()
        agent_names = [a['name'] for a in agents]
        self.assertIn('codex', agent_names)
        self.assertIn('claude', agent_names)
        self.assertIn('opencode', agent_names)
        self.assertIn('antigravity', agent_names)

        codex_meta = next(a for a in agents if a['name'] == 'codex')
        self.assertTrue(codex_meta['supports_resume'])
        self.assertIn('gpt-6.2-sol', codex_meta['models'])
        self.assertIn('gpt-6.2-astra', codex_meta['models'])

        claude_meta = next(a for a in agents if a['name'] == 'claude')
        self.assertTrue(claude_meta['supports_resume'])
        self.assertIn('claude-3-7-sonnet', claude_meta['models'])

        agy_meta = next(a for a in agents if a['name'] == 'antigravity')
        self.assertTrue(agy_meta['supports_resume'])
        self.assertIn('gemini-4-neon-high', agy_meta['models'])
        self.assertIn('gemini-4-neon-xhigh', agy_meta['models'])
        self.assertIn('gemini-4-argon-high', agy_meta['models'])
        self.assertIn('gemini-3.8-flash-high', agy_meta['models'])
        self.assertIn('gpt-oss-120b-medium', agy_meta['models'])

    def test_start_task_agent_validation(self):
        # Unknown agent
        with self.assertRaises(BridgeError):
            self.core.start_task('proj', 'Some prompt', 'build', agent='unknown_ai')

    def test_agents_http_endpoint(self):
        server, core = create_server(self.root / 'data_srv', port=0)
        port = server.server_address[1]
        import threading
        th = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05})
        th.daemon = True
        th.start()
        def stop_server():
            server.shutdown()
            server.server_close()
        self.addCleanup(stop_server)

        req = Request(f'http://127.0.0.1:{port}/v1/agents', headers={'Authorization': f'Bearer {core.token}'})
        with urlopen(req) as resp:
            data = json.loads(resp.read().decode())
        self.assertIn('agents', data)
        names = [a['name'] for a in data['agents']]
        self.assertIn('codex', names)
        self.assertIn('claude', names)

    def test_stream_json_parsing_for_claude_and_agy(self):
        import sys as _sys
        script = (
            'import json; '
            'print(json.dumps({"type":"system","session_id":"sess-abc12345"})); '
            'print(json.dumps({"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit"},{"type":"text","text":"Updated layout cleanly."}]}})); '
            'print(json.dumps({"type":"result","subtype":"success","session_id":"sess-abc12345","result":"Updated layout cleanly."}))'
        )
        tid = 'test-stream-json-1'
        with self.core._db() as db:
            db.execute(
                'INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)',
                (tid, 'proj', 'Plan UI', 'antigravity', 'plan', 'PLANNING', 0.0, 0.0)
            )
        rc, out = self.core._run(tid, [_sys.executable, '-c', script], self.core._project('proj'), timeout=10, phase='agent')
        self.assertEqual(rc, 0)
        self.assertIn('Updated layout cleanly.', out)
        self.assertEqual(self.core.thread_candidates.get(tid), 'sess-abc12345')
        evts = self.core.events(tid, 0)
        kinds = [e['kind'] for e in evts]
        self.assertIn('thread', kinds)
        self.assertIn('tool', kinds)
        self.assertIn('message', kinds)

    def test_agy_event_stream_parsing(self):
        import sys as _sys
        script = (
            'import json; '
            'print(json.dumps({"event":"init","conversation_id":"conv-agy-123456"})); '
            'print(json.dumps({"event":"step_update","step_update":{"step_type":"tool","tool_name":"write_file","state":"DONE"}})); '
            'print(json.dumps({"event":"result","result":{"conversation_id":"conv-agy-123456","status":"SUCCESS","response":"Built Vite app."}}))'
        )
        tid = 'test-agy-events-1'
        with self.core._db() as db:
            db.execute(
                'INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)',
                (tid, 'proj', 'Build UI', 'antigravity', 'build', 'EXECUTING', 0.0, 0.0)
            )
        rc, out = self.core._run(tid, [_sys.executable, '-c', script], self.core._project('proj'), timeout=10, phase='agent')
        self.assertEqual(rc, 0)
        self.assertIn('Built Vite app.', out)
        self.assertEqual(self.core.thread_candidates.get(tid), 'conv-agy-123456')
        evts = self.core.events(tid, 0)
        kinds = [e['kind'] for e in evts]
        self.assertIn('thread', kinds)
        self.assertIn('tool', kinds)
        self.assertIn('message', kinds)

    def test_agy_denied_actions_fails(self):
        import sys as _sys
        script = (
            'import json; '
            'print(json.dumps({"event":"init","conversation_id":"conv-agy-denied"})); '
            'print(json.dumps({"event":"result","result":{"conversation_id":"conv-agy-denied","status":"SUCCESS","response":"","denied_actions":[{"action":"command","display_name":"Bash"}]}}))'
        )
        tid = 'test-agy-denied-1'
        with self.core._db() as db:
            db.execute(
                'INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)',
                (tid, 'proj', 'Build UI', 'antigravity', 'build', 'EXECUTING', 0.0, 0.0)
            )
        rc, _out = self.core._run(tid, [_sys.executable, '-c', script], self.core._project('proj'), timeout=10, phase='agent')
        self.assertNotEqual(rc, 0)
        evts = self.core.events(tid, 0)
        self.assertTrue(any(e['kind'] == 'error' and 'denied actions' in e['message'] for e in evts))

if __name__ == '__main__':
    unittest.main()
