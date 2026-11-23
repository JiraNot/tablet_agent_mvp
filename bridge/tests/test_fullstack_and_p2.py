import json
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.request import Request, urlopen

from bridge.core import BridgeError, LocalCore
from bridge.server import create_server


class FullStackAndP2Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.server, self.core = create_server(self.root, port=0)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.core.create_project('fsapp')

    def tearDown(self):
        try:
            for p in list(self.core.previews.keys()):
                self.core.stop_preview(p)
            for p in list(self.core.terminals.keys()):
                self.core.terminal_stop(p)
        except Exception:
            pass
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def call(self, method: str, path: str, body: dict | None = None):
        data = None if body is None else json.dumps(body).encode('utf-8')
        req = Request(f'http://127.0.0.1:{self.port}{path}', data=data, method=method)
        req.add_header('Authorization', f'Bearer {self.core.token}')
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode('utf-8'))
        except Exception as e:
            if hasattr(e, 'code'):
                return e.code, json.loads(e.read().decode('utf-8'))
            raise

    def test_write_file_create_dirs_for_apply_to_file(self):
        st, res = self.call('PUT', '/v1/projects/fsapp/file?path=src/components/Card.tsx', {
            'content': 'export const Card = () => <div>Hi</div>;\n',
            'create_dirs': True,
        })
        self.assertEqual(st, 200)
        self.assertEqual(res['path'], 'src/components/Card.tsx')
        saved = self.core._project('fsapp') / 'src' / 'components' / 'Card.tsx'
        self.assertTrue(saved.is_file())

    def test_git_branches_checkout_checkpoint_and_safe_push_validation(self):
        proj = self.core._project('fsapp')
        subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=t@t', 'add', '-A'], cwd=proj, check=True)
        subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=t@t', 'commit', '-m', 'init'], cwd=proj, check=True)

        st, br = self.call('GET', '/v1/projects/fsapp/git/branches')
        self.assertEqual(st, 200)
        self.assertTrue(br['is_git'])
        self.assertTrue(len(br['branches']) >= 1)

        st2, co = self.call('POST', '/v1/projects/fsapp/git/checkout', {'branch': 'feature/ui', 'create': True})
        self.assertEqual(st2, 200)
        self.assertEqual(co['branch'], 'feature/ui')

        # Reject invalid branch name (option injection)
        st_bad, _ = self.call('POST', '/v1/projects/fsapp/git/checkout', {'branch': '--force', 'create': True})
        self.assertEqual(st_bad, 400)

        # Checkpoint before apply
        (proj / 'note.txt').write_text('dirty change', encoding='utf-8')
        st_cp, cp = self.call('POST', '/v1/projects/fsapp/git/checkpoint', {'label': 'apply-code'})
        self.assertEqual(st_cp, 200)
        self.assertTrue(cp['success'])

        # Reject invalid remote name in push
        st_push_bad, _ = self.call('POST', '/v1/projects/fsapp/git/push', {'remote': '--force', 'branch': 'feature/ui'})
        self.assertEqual(st_push_bad, 400)

    def test_workspace_search_skips_env_and_node_modules(self):
        proj = self.core._project('fsapp')
        (proj / 'app.py').write_text('def hello_world():\n    return "OmniDev"\n', encoding='utf-8')
        (proj / '.env').write_text('API_SECRET=OmniDevSuperSecret12345\n', encoding='utf-8')
        (proj / 'node_modules').mkdir()
        (proj / 'node_modules' / 'lib.js').write_text('OmniDev in deps\n', encoding='utf-8')

        st, res = self.call('GET', '/v1/projects/fsapp/search?q=omnidev')
        self.assertEqual(st, 200)
        files = [m['file'] for m in res['matches']]
        self.assertIn('app.py', files)
        self.assertNotIn('.env', files)
        self.assertFalse(any('node_modules' in f for f in files))

    def test_project_rename_and_delete(self):
        self.core.create_project('temp_proj')
        st_ren, ren = self.call('POST', '/v1/projects/temp_proj/rename', {'new_name': 'renamed_proj'})
        self.assertEqual(st_ren, 200)
        self.assertEqual(ren['name'], 'renamed_proj')
        self.assertFalse((self.core.workspaces / 'temp_proj').exists())
        self.assertTrue((self.core.workspaces / 'renamed_proj').is_dir())

        st_del, deleted = self.call('DELETE', '/v1/projects/renamed_proj')
        self.assertEqual(st_del, 200)
        self.assertTrue(deleted['deleted'])
        self.assertFalse((self.core.workspaces / 'renamed_proj').exists())

    def test_fullstack_detect_verify_and_static_preview_with_logs(self):
        proj = self.core._project('fsapp')
        # Python test detection
        (proj / 'test_sample.py').write_text('import unittest\nclass T(unittest.TestCase):\n def test_ok(self): self.assertTrue(True)\n', encoding='utf-8')
        cmd, needs_install = self.core._detect_verify_command(proj)
        self.assertIsNotNone(cmd)
        self.assertFalse(needs_install)

        # Static HTML preview + log buffer
        (proj / 'index.html').write_text('<h1>OmniDev FullStack</h1>', encoding='utf-8')
        st, prev = self.call('POST', '/v1/projects/fsapp/preview/start', {'port': 18923})
        self.assertEqual(st, 200)
        self.assertEqual(prev['status'], 'running')
        self.assertEqual(prev['stack'], 'static')
        self.assertIsInstance(prev['logs'], list)

        st_stop, stopped = self.call('POST', '/v1/projects/fsapp/preview/stop', {})
        self.assertEqual(st_stop, 200)
        self.assertEqual(stopped['status'], 'stopped')

    def test_terminal_session_lifecycle(self):
        st, started = self.call('POST', '/v1/projects/fsapp/terminal/start', {'cols': 100, 'rows': 30})
        self.assertEqual(st, 200)
        self.assertTrue(started['running'])
        self.assertEqual(started['cols'], 100)

        st_in, _ = self.call('POST', '/v1/projects/fsapp/terminal/input', {'data': 'echo OMNIDEV_PTY_OK\r\n'})
        self.assertEqual(st_in, 200)

        collected = ''
        seq = 0
        for _ in range(20):
            st_p, polled = self.call('GET', f'/v1/projects/fsapp/terminal/poll?after={seq}')
            self.assertEqual(st_p, 200)
            seq = polled['seq']
            collected += polled['data']
            if 'OMNIDEV_PTY_OK' in collected:
                break
            time.sleep(0.1)
        self.assertIn('OMNIDEV_PTY_OK', collected)

        st_rs, resized = self.call('POST', '/v1/projects/fsapp/terminal/resize', {'cols': 120, 'rows': 40})
        self.assertEqual(st_rs, 200)
        self.assertEqual(resized['cols'], 120)

        st_stop, stopped = self.call('POST', '/v1/projects/fsapp/terminal/stop', {})
        self.assertEqual(st_stop, 200)
        self.assertFalse(stopped['running'])


if __name__ == '__main__':
    unittest.main()
