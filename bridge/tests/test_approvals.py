import json, pathlib, sys, tempfile, threading, time, unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from core import LocalCore, BridgeError
from server import create_server

class ApprovalsTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.temp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.core = LocalCore(self.root / 'data')
        self.core.create_project('proj')

    def test_approval_lifecycle_allow(self):
        with self.core._db() as db:
            db.execute("INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                       ('t1', 'proj', 'Do dangerous work', 'codex', 'build', 'EXECUTING', time.time(), time.time()))

        outcome = []
        def background_request():
            allowed = self.core.request_approval('t1', 'command_execution', 'git push origin main', timeout=3.0)
            outcome.append(allowed)

        th = threading.Thread(target=background_request)
        th.start()
        time.sleep(0.05)

        task = self.core.task('t1')
        self.assertEqual(task['status'], 'WAITING_APPROVAL')

        apps = self.core.approvals('t1')
        self.assertEqual(len(apps), 1)
        self.assertEqual(apps[0]['status'], 'PENDING')
        self.assertEqual(apps[0]['target'], 'git push origin main')

        # Resolve with allow
        resolved = self.core.resolve_approval('t1', apps[0]['id'], 'allow')
        self.assertEqual(resolved['status'], 'ALLOWED')

        th.join(timeout=3)
        self.assertEqual(outcome, [True])
        self.assertEqual(self.core.task('t1')['status'], 'EXECUTING')

    def test_approval_lifecycle_deny(self):
        with self.core._db() as db:
            db.execute("INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                       ('t2', 'proj', 'Delete files', 'codex', 'build', 'EXECUTING', time.time(), time.time()))

        outcome = []
        def background_request():
            allowed = self.core.request_approval('t2', 'command_execution', 'rm -rf /', timeout=3.0)
            outcome.append(allowed)

        th = threading.Thread(target=background_request)
        th.start()
        time.sleep(0.05)

        apps = self.core.approvals('t2')
        self.core.resolve_approval('t2', apps[0]['id'], 'deny')
        th.join(timeout=3)

        self.assertEqual(outcome, [False])
        apps_after = self.core.approvals('t2')
        self.assertEqual(apps_after[0]['status'], 'DENIED')

    def test_approval_http_api(self):
        server, core = create_server(self.root / 'http_data', 0)
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

        core.create_project('demo')
        with core._db() as db:
            db.execute("INSERT INTO tasks(id,project,prompt,agent,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                       ('t3', 'demo', 'Test API', 'codex', 'build', 'EXECUTING', time.time(), time.time()))

        outcome = []
        def bg():
            allowed = core.request_approval('t3', 'command_execution', 'npm install bad-package', timeout=3.0)
            outcome.append(allowed)

        th = threading.Thread(target=bg)
        th.start()
        time.sleep(0.05)

        base = f"http://127.0.0.1:{server.server_address[1]}"
        # GET approvals
        req = Request(f"{base}/v1/tasks/t3/approvals", headers={'Authorization': 'Bearer ' + core.token})
        with urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read().decode())
            self.assertEqual(len(data['approvals']), 1)
            aid = data['approvals'][0]['id']

        # POST decision allow
        req_post = Request(
            f"{base}/v1/tasks/t3/approvals/{aid}",
            data=json.dumps({'decision': 'allow'}).encode(),
            headers={'Authorization': 'Bearer ' + core.token, 'Content-Type': 'application/json'},
            method='POST'
        )
        with urlopen(req_post, timeout=3) as resp:
            data_post = json.loads(resp.read().decode())
            self.assertEqual(data_post['status'], 'ALLOWED')

        th.join(timeout=3)
        self.assertEqual(outcome, [True])

if __name__ == '__main__':
    unittest.main()
