import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from bridge.core import BridgeError, LocalCore


class McpAndConcurrencyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.core = LocalCore(self.root)
        self.proj = self.core.workspaces / 'demo'
        self.proj.mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_mcp_upsert_toggle_delete_and_sync_masks_secrets(self):
        with mock.patch('pathlib.Path.home', return_value=self.root):
            res = self.core.upsert_mcp_server(
                'postgres',
                'PostgreSQL',
                transport='stdio',
                command='npx',
                args=['-y', '@modelcontextprotocol/server-postgres'],
                env={'POSTGRES_CONNECTION_STRING': 'postgres://user:secret@host/db'},
                enabled=True,
            )
            self.assertEqual(res['id'], 'postgres')
            self.assertTrue(res['enabled'])

            listed = self.core.list_mcp_servers()
            srv = next(s for s in listed['servers'] if s['id'] == 'postgres')
            self.assertEqual(srv['env_keys'], ['POSTGRES_CONNECTION_STRING'])
            self.assertNotIn('secret', json.dumps(listed))

            claude_cfg = json.loads((self.root / '.claude.json').read_text(encoding='utf-8'))
            self.assertIn('postgres', claude_cfg['mcpServers'])

            toml_txt = (self.root / '.codex' / 'config.toml').read_text(encoding='utf-8')
            self.assertIn('[mcp_servers.postgres]', toml_txt)

            self.core.toggle_mcp_server('postgres', False)
            claude_cfg2 = json.loads((self.root / '.claude.json').read_text(encoding='utf-8'))
            self.assertNotIn('postgres', claude_cfg2['mcpServers'])

            self.core.delete_mcp_server('postgres')
            self.assertEqual(self.core.list_mcp_servers()['servers'], [])

    def test_concurrent_plan_chats_run_without_blocking_same_project(self):
        adapter = self.core.adapters['codex']
        gate = __import__('threading').Event()

        def slow_run(task_id, cmd, cwd, timeout, phase='agent'):
            gate.wait(timeout=3.0)
            return (0, 'Plan complete')

        with mock.patch.object(adapter, 'is_available', return_value=True), \
             mock.patch.object(adapter, 'build_cmd', return_value=['echo', 'ok']), \
             mock.patch.object(self.core, '_run', side_effect=slow_run):
            t1 = self.core.start_task('demo', 'Plan chat 1', mode='plan', agent='codex')
            t2 = self.core.start_task('demo', 'Plan chat 2', mode='plan', agent='codex')
            time.sleep(0.15)
            s1 = self.core.task(t1['id'])['status']
            s2 = self.core.task(t2['id'])['status']
            self.assertEqual(s1, 'PLANNING')
            self.assertEqual(s2, 'PLANNING')
            gate.set()
            deadline = time.time() + 5.0
            while (self.core.task(t1['id'])['status'] == 'PLANNING' or self.core.task(t2['id'])['status'] == 'PLANNING') and time.time() < deadline:
                time.sleep(0.05)
            self.assertEqual(self.core.task(t1['id'])['status'], 'PLANNED')
            self.assertEqual(self.core.task(t2['id'])['status'], 'PLANNED')

    def test_tablet_browser_cli_script_created(self):
        self.assertTrue((self.core.home / 'bin' / 'tablet-browser').is_file())


if __name__ == '__main__':
    unittest.main()
