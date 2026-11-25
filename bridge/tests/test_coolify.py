import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from bridge.core import BridgeError, LocalCore


class CoolifyIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.core = LocalCore(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def test_coolify_base_url_validation(self):
        self.assertEqual(
            LocalCore._coolify_base_url('coolify.example.com/'),
            'https://coolify.example.com',
        )
        self.assertEqual(
            LocalCore._coolify_base_url('http://10.0.0.5:8000/'),
            'http://10.0.0.5:8000',
        )
        with self.assertRaises(BridgeError) as ctx:
            LocalCore._coolify_base_url('')
        self.assertEqual(ctx.exception.status, 400)

        with self.assertRaises(BridgeError) as ctx2:
            LocalCore._coolify_base_url('https://')
        self.assertEqual(ctx2.exception.status, 400)

    def test_coolify_test_requires_token(self):
        with mock.patch.dict('os.environ', {}, clear=True):
            with self.assertRaises(BridgeError) as ctx:
                self.core.coolify_test('https://coolify.example.com', '')
            self.assertEqual(ctx.exception.status, 401)

    def test_coolify_overview_matches_git_origin_and_caches_secret_free_context(self):
        proj = self.core.workspaces / 'controlaistudio'
        proj.mkdir(parents=True)
        subprocess.run(['git', 'init'], cwd=proj, check=True, capture_output=True)
        subprocess.run(
            ['git', 'remote', 'add', 'origin', 'https://github.com/acme/controlaistudio.git'],
            cwd=proj,
            check=True,
            capture_output=True,
        )

        fake_apps = [
            {
                'uuid': 'app-other',
                'name': 'unrelated-blog',
                'fqdn': 'https://blog.example.com',
                'git_repository': 'https://github.com/acme/blog',
                'git_branch': 'main',
                'build_pack': 'nixpacks',
                'status': 'running',
            },
            {
                'uuid': 'app-match',
                'name': 'studio-prod',
                'fqdn': 'https://studio.example.com',
                'git_repository': 'git@github.com:acme/controlaistudio.git',
                'git_branch': 'main',
                'build_pack': 'dockerfile',
                'status': 'exited',
            },
        ]
        fake_dbs = [
            {'uuid': 'db-1', 'name': 'studio-pg', 'type': 'postgresql', 'status': 'running'}
        ]

        def fake_req(base_url, token, path, method='GET'):
            if path == '/applications':
                return fake_apps
            if path == '/databases':
                return fake_dbs
            return {}

        secret_token = 'coolify-secret-token-12345'
        with mock.patch.object(self.core, '_coolify_request', side_effect=fake_req):
            res = self.core.coolify_overview('https://coolify.example.com', secret_token, 'controlaistudio')

        self.assertEqual(res['matched_count'], 1)
        self.assertEqual(res['apps'][0]['uuid'], 'app-match')
        self.assertTrue(res['apps'][0]['matched'])
        self.assertFalse(res['apps'][1]['matched'])
        self.assertEqual(len(res['databases']), 1)

        cached = self.core.coolify_context_cache.get('controlaistudio', '')
        self.assertIn("App 'studio-prod'", cached)
        self.assertIn('studio-pg', cached)
        self.assertNotIn(secret_token, cached)

    def test_coolify_deployment_logs_redacts_secrets(self):
        raw_logs = json.dumps([
            {'output': 'Building image...'},
            {'output': 'OPENAI_API_KEY=sk-1234567890abcdefghijklmnop'},
            {'output': 'Error: ghp_abcdefghijklmnopqrstuvwxyz123456 failed auth'},
        ])
        with mock.patch.object(
            self.core,
            '_coolify_request',
            return_value={'status': 'failed', 'logs': raw_logs},
        ):
            res = self.core.coolify_deployment_logs(
                'https://coolify.example.com',
                'tok-123',
                'dep-abc',
            )

        self.assertEqual(res['deployment_uuid'], 'dep-abc')
        self.assertEqual(res['status'], 'failed')
        self.assertIn('Building image...', res['logs'])
        self.assertNotIn('sk-1234567890abcdefghijklmnop', res['logs'])
        self.assertNotIn('ghp_abcdefghijklmnopqrstuvwxyz123456', res['logs'])
        self.assertIn('[REDACTED]', res['logs'])

    def test_coolify_requires_uuids(self):
        with self.assertRaises(BridgeError) as ctx1:
            self.core.coolify_deployments('https://coolify.example.com', 'tok', '!!!')
        self.assertEqual(ctx1.exception.status, 400)

        with self.assertRaises(BridgeError) as ctx2:
            self.core.coolify_deploy('https://coolify.example.com', 'tok', '')
        self.assertEqual(ctx2.exception.status, 400)


if __name__ == '__main__':
    unittest.main()
