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

    def test_git_draft_pr(self):
        # Create a new branch and a commit
        git = shutil.which('git')
        subprocess.run([git, 'checkout', '-b', 'feat/test-pr'], cwd=self.proj_path, capture_output=True, check=True)
        (self.proj_path / 'feature.py').write_text('def hello():\n    return "world"\n')
        self.core.git_commit('testgit', 'feat(core): add hello world feature')

        # Draft PR
        pr_draft = self.core.git_draft_pr('testgit', base_branch='master')
        self.assertEqual(pr_draft['head'], 'feat/test-pr')
        self.assertIn('feat', pr_draft['title'])
        self.assertIn('## Summary', pr_draft['body'])
        self.assertIn('## Verification', pr_draft['body'])
        self.assertGreaterEqual(pr_draft['commits_count'], 1)

    def test_git_resolve_conflicts(self):
        # Create a conflicted file with conflict markers
        conflicted_content = (
            "import os\n"
            "<<<<<<< HEAD\n"
            "import sys\n"
            "def local_function():\n"
            "    return 'local'\n"
            "=======\n"
            "import json\n"
            "def remote_function():\n"
            "    return 'remote'\n"
            ">>>>>>> origin/main\n"
        )
        conflicted_file = self.proj_path / 'conflicted.py'
        conflicted_file.write_text(conflicted_content, encoding='utf-8')

        # Stage as unmerged conflict or unmerged file in git
        git = shutil.which('git')
        subprocess.run([git, 'add', 'conflicted.py'], cwd=self.proj_path, capture_output=True, check=True)

        # Status check
        st = self.core.git_status('testgit')
        self.assertTrue(st['is_git'])

        # Now test resolve_conflicts method
        # Manually invoke resolve_conflicts by mocking diff filter or setting unmerged
        # Test the conflict resolution logic
        res = self.core.git_resolve_conflicts('testgit')
        # If git diff --diff-filter=U doesn't see real unmerged index, res['success'] is True
        self.assertTrue(res['success'])

    def test_git_resolve_real_merge_conflict(self):
        git = shutil.which('git')
        # Create branch-conflict
        subprocess.run([git, 'checkout', '-b', 'branch-conflict'], cwd=self.proj_path, capture_output=True, check=True)
        (self.proj_path / 'shared.txt').write_text("line 1\nlocal feature line\nline 3\n", encoding='utf-8')
        subprocess.run([git, 'add', 'shared.txt'], cwd=self.proj_path, capture_output=True, check=True)
        subprocess.run([git, 'commit', '-m', 'feat: local change on branch-conflict'], cwd=self.proj_path, capture_output=True, check=True)

        # Checkout master (or initial branch)
        subprocess.run([git, 'checkout', 'master'], cwd=self.proj_path, capture_output=True, check=True)
        (self.proj_path / 'shared.txt').write_text("line 1\nremote conflicting line\nline 3\n", encoding='utf-8')
        subprocess.run([git, 'add', 'shared.txt'], cwd=self.proj_path, capture_output=True, check=True)
        subprocess.run([git, 'commit', '-m', 'feat: remote change on master'], cwd=self.proj_path, capture_output=True, check=True)

        # Trigger merge conflict
        merge_res = subprocess.run([git, 'merge', 'branch-conflict'], cwd=self.proj_path, capture_output=True, text=True)
        self.assertNotEqual(merge_res.returncode, 0)
        self.assertIn('CONFLICT', merge_res.stdout + merge_res.stderr)

        # Check status detects conflict
        st = self.core.git_status('testgit')
        self.assertTrue(st['has_conflicts'])
        self.assertIn('shared.txt', st['conflicted_files'])

        # Now auto-resolve via AI
        resolve_res = self.core.git_resolve_conflicts('testgit')
        self.assertTrue(resolve_res['success'])
        self.assertIn('shared.txt', resolve_res['resolved_files'])
        self.assertTrue(bool(resolve_res['commit_sha']))

        # Verify markers are gone
        resolved_text = (self.proj_path / 'shared.txt').read_text(encoding='utf-8')
        self.assertNotIn('<<<<<<<', resolved_text)
        self.assertNotIn('=======', resolved_text)
        self.assertNotIn('>>>>>>>', resolved_text)

        # Check status is now clean
        st_after = self.core.git_status('testgit')
        self.assertFalse(st_after['has_conflicts'])
        self.assertTrue(st_after['clean'])

    def test_create_project_git_clone_url_normalization_and_token(self):
        # Invalid project name
        with self.assertRaises(BridgeError):
            self.core.create_project('invalid name with spaces', 'octocat/Hello-World')

        # Invalid URLs
        with self.assertRaises(BridgeError):
            self.core.create_project('proj_bad_url', 'https://malicious.com/repo.git')

        # Helpful error on private repo without token
        with self.assertRaises(BridgeError) as ctx:
            self.core.create_project('proj_private', 'https://github.com/nonexistent-user-12345/private-repo-67890.git')
        self.assertIn('Repository not found or private', str(ctx.exception))

    def test_git_push_token_and_fine_grained_token_redaction(self):
        # Invalid remote should fail cleanly
        with self.assertRaises(BridgeError):
            self.core.git_push('testgit', remote='invalid remote name;', token='github_pat_11ABCD1234567890')

        # Test fine-grained PAT redaction
        from core import REDACTION
        raw_msg = "Error connecting to https://github.com with token github_pat_11ABCDE1234567890_abcdefghijklmnopqrstuvwxyz012345678901234567890"
        redacted = REDACTION.sub('[REDACTED]', raw_msg)
        self.assertNotIn('github_pat_', redacted)
        self.assertIn('[REDACTED]', redacted)

        # Test no duplicate Authorization header args
        auth_args = self.core._github_auth_git_args('ghp_testtoken1234567890')
        self.assertIn('credential.helper=', auth_args)
        self.assertIn('http.https://github.com/.extraheader=', auth_args)
        self.assertTrue(any('AUTHORIZATION: basic ' in a for a in auth_args))

if __name__ == '__main__':
    unittest.main()
