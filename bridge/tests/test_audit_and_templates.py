import json, os, pathlib, shutil, subprocess, sys, tempfile, threading, unittest
from urllib.request import Request, urlopen

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from core import LocalCore, BridgeError
from server import create_server

class AuditAndTemplatesTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.temp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.core = LocalCore(self.root / 'data')

        self.server, _ = create_server(self.root / 'data', port=0)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def _req(self, method, path, body=None):
        url = f'http://127.0.0.1:{self.port}{path}'
        data = json.dumps(body).encode('utf-8') if body is not None else None
        req = Request(url, data=data, method=method)
        req.add_header('Authorization', f'Bearer {self.core.token}')
        req.add_header('Content-Type', 'application/json')
        with urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))

    def test_starter_templates_init(self):
        # 1. React Vite
        res1 = self.core.templates_init('app_react', 'react_vite')
        self.assertTrue(res1['initialized'])
        p1 = self.core._project('app_react')
        self.assertTrue((p1 / 'package.json').exists())
        self.assertTrue((p1 / 'src' / 'App.jsx').exists())
        self.assertTrue((p1 / '.git').exists())

        # 2. PWA Notes
        res2 = self.core.templates_init('app_pwa', 'pwa_notes')
        self.assertTrue(res2['initialized'])
        p2 = self.core._project('app_pwa')
        self.assertTrue((p2 / 'public' / 'sw.js').exists())
        self.assertTrue((p2 / 'public' / 'manifest.json').exists())
        self.assertTrue((p2 / 'src' / 'main.js').exists())

        # 3. Data Dashboard
        res3 = self.core.templates_init('app_dash', 'data_dashboard')
        self.assertTrue(res3['initialized'])
        p3 = self.core._project('app_dash')
        self.assertTrue((p3 / 'index.html').exists())
        self.assertIn('chart.js', (p3 / 'package.json').read_text())

        # 4. FastAPI SQLite
        res4 = self.core.templates_init('app_fastapi', 'fastapi_sqlite')
        self.assertTrue(res4['initialized'])
        p4 = self.core._project('app_fastapi')
        self.assertTrue((p4 / 'main.py').exists())
        self.assertTrue((p4 / 'requirements.txt').exists())

    def test_git_audit_security_and_hygiene(self):
        self.core.create_project('audit_proj')
        p = self.core._project('audit_proj')
        git = shutil.which('git')
        if not git:
            self.skipTest("git executable not found")

        subprocess.run([git, 'init'], cwd=p, capture_output=True, check=True)
        subprocess.run([git, 'config', 'user.email', 'test@tablet.local'], cwd=p, capture_output=True, check=True)
        subprocess.run([git, 'config', 'user.name', 'Tablet Test'], cwd=p, capture_output=True, check=True)
        (p / 'index.js').write_text('// initial file\n')
        subprocess.run([git, 'add', '.'], cwd=p, capture_output=True, check=True)
        subprocess.run([git, 'commit', '-m', 'Initial commit'], cwd=p, capture_output=True, check=True)

        # Clean state audit
        audit_clean = self.core.git_audit('audit_proj')
        self.assertTrue(audit_clean['clean'])
        self.assertEqual(len(audit_clean['findings']), 0)

        # Introduce issues: secret, warning, tip
        code_with_issues = (
            "// updated file\n"
            "const apiKey = 'sk-abcdef123456789012345678901234567890';\n"
            "console.log('debug output', apiKey);\n"
            "alert('Done!');\n"
        )
        (p / 'index.js').write_text(code_with_issues)

        audit_issues = self.core.git_audit('audit_proj')
        self.assertFalse(audit_issues['clean'])
        self.assertGreaterEqual(audit_issues['critical_count'], 1)
        self.assertGreaterEqual(audit_issues['warning_count'], 1)
        self.assertGreaterEqual(audit_issues['tip_count'], 1)

        crit = [f for f in audit_issues['findings'] if f['severity'] == 'CRITICAL'][0]
        self.assertIn('API Key', crit['title'])

    def test_pwa_configure(self):
        self.core.create_project('web_app')
        p = self.core._project('web_app')
        (p / 'index.html').write_text('<!doctype html><html><head><title>App</title></head><body>Hello</body></html>')

        res = self.core.pwa_configure('web_app')
        self.assertTrue(res['success'])
        self.assertTrue((p / 'public' / 'manifest.json').exists())
        self.assertTrue((p / 'public' / 'sw.js').exists())
        self.assertTrue(res['index_injected'])
        self.assertIn('manifest.json', (p / 'index.html').read_text())

    def test_http_routes(self):
        # 1. POST /v1/templates/init
        st1, d1 = self._req('POST', '/v1/templates/init', {'project': 'http_react', 'template_id': 'react_vite'})
        self.assertEqual(st1, 201)
        self.assertTrue(d1['initialized'])

        # 2. POST /v1/projects/:name/pwa/configure
        st2, d2 = self._req('POST', '/v1/projects/http_react/pwa/configure', {})
        self.assertEqual(st2, 200)
        self.assertTrue(d2['success'])

        # 3. POST /v1/projects/:name/git/audit
        st3, d3 = self._req('POST', '/v1/projects/http_react/git/audit', {})
        self.assertEqual(st3, 200)
        self.assertIn('findings', d3)
        self.assertIn('clean', d3)

if __name__ == '__main__':
    unittest.main()
