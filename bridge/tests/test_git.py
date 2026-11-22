import json, os, pathlib, shutil, subprocess, sys, tempfile, threading, unittest
from urllib.request import Request, urlopen
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from core import LocalCore, BridgeError
from server import create_server

class GitTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.temp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.core = LocalCore(self.root / 'data')
        self.core.create_project('testgit')
        self.proj_path = self.core._project('testgit')

        # Initialize real git repo inside testgit
        git = shutil.which('git')
        if not git:
            self.skipTest("git executable not found")
        subprocess.run([git, 'init'], cwd=self.proj_path, capture_output=True, check=True)
        subprocess.run([git, 'config', 'user.email', 'test@tablet.local'], cwd=self.proj_path, capture_output=True, check=True)
        subprocess.run([git, 'config', 'user.name', 'Tablet Test'], cwd=self.proj_path, capture_output=True, check=True)
        # Initial commit
        (self.proj_path / 'README.md').write_text('# Initial\n')
        subprocess.run([git, 'add', '.'], cwd=self.proj_path, capture_output=True, check=True)
        subprocess.run([git, 'commit', '-m', 'Initial commit'], cwd=self.proj_path, capture_output=True, check=True)

    def test_git_status_and_diff(self):
        # Clean initially
        st = self.core.git_status('testgit')
        self.assertTrue(st['is_git'])
        self.assertTrue(st['clean'])
        self.assertEqual(len(st['files']), 0)

        # Modify a file
        (self.proj_path / 'README.md').write_text('# Modified content\n')
        # Add untracked file
        (self.proj_path / 'newfile.txt').write_text('new text\n')

        st2 = self.core.git_status('testgit')
        self.assertFalse(st2['clean'])
        self.assertGreaterEqual(st2['uncommitted_count'], 2)

        # Check diff
        diff_res = self.core.git_diff('testgit')
        self.assertTrue(diff_res['has_changes'])
        self.assertIn('Modified content', diff_res['diff'])

    def test_git_commit_flow(self):
        # Empty commit message should fail
        with self.assertRaises(BridgeError):
            self.core.git_commit('testgit', '   ')

        # Create changes
        (self.proj_path / 'app.js').write_text('console.log("hello world");\n')
        res = self.core.git_commit('testgit', 'feat: add app.js')
        self.assertTrue(res['success'])
        self.assertTrue(res['committed'])
        self.assertTrue(bool(res['sha']))

        # After commit, working tree should be clean
        st = self.core.git_status('testgit')
        self.assertTrue(st['clean'])

        # Committing with nothing to commit
        res2 = self.core.git_commit('testgit', 'another commit')
        self.assertFalse(res2['committed'])

    def test_git_http_endpoints(self):
        server, core = create_server(self.root / 'data_srv', port=0)
        port = server.server_address[1]
        th = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05})
        th.daemon = True
        th.start()
        def stop_server():
            server.shutdown()
            server.server_close()
        self.addCleanup(stop_server)

        core.create_project('api_proj')
        git = shutil.which('git')
        if not git:
            return
        p = core._project('api_proj')
        subprocess.run([git, 'init'], cwd=p, capture_output=True, check=True)
        subprocess.run([git, 'config', 'user.email', 'test@tablet.local'], cwd=p, capture_output=True, check=True)
        subprocess.run([git, 'config', 'user.name', 'Tablet Test'], cwd=p, capture_output=True, check=True)
        (p / 'file1.txt').write_text('content1\n')

        # Test status via HTTP
        req_status = Request(f'http://127.0.0.1:{port}/v1/projects/api_proj/git/status',
                             headers={'Authorization': f'Bearer {core.token}'})
        with urlopen(req_status) as resp:
            st = json.loads(resp.read().decode())
        self.assertTrue(st['is_git'])
        self.assertFalse(st['clean'])

        # Test diff via HTTP
        (p / 'file1.txt').write_text('changed1\n')
        req_diff = Request(f'http://127.0.0.1:{port}/v1/projects/api_proj/git/diff',
                           headers={'Authorization': f'Bearer {core.token}'})
        with urlopen(req_diff) as resp:
            df = json.loads(resp.read().decode())
        self.assertIn('diff', df)

        # Test commit via HTTP
        body = json.dumps({'message': 'Initial test commit via API'}).encode('utf-8')
        req_commit = Request(f'http://127.0.0.1:{port}/v1/projects/api_proj/git/commit',
                             data=body,
                             headers={'Authorization': f'Bearer {core.token}', 'Content-Type': 'application/json'},
                             method='POST')
        with urlopen(req_commit) as resp:
            c_res = json.loads(resp.read().decode())
        self.assertTrue(c_res['success'])
        self.assertTrue(c_res['committed'])

if __name__ == '__main__':
    unittest.main()
