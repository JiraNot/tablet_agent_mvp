import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from bridge.core import LocalCore


class PlanArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.core = LocalCore(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def test_plan_mode_saves_markdown_artifact_and_emits_event(self):
        proj = self.core.workspaces / 'demo'
        proj.mkdir(parents=True)
        adapter = self.core.adapters['codex']
        with mock.patch.object(adapter, 'is_available', return_value=True), \
             mock.patch.object(adapter, 'build_cmd', return_value=['echo', 'ok']), \
             mock.patch.object(self.core, '_run', return_value=(0, '1. Optimize Dockerfile\n2. Enable BuildKit cache')):
            task = self.core.start_task('demo', 'Plan how to speed up Docker build', mode='plan', agent='codex')
            tid = task['id']
            deadline = time.time() + 5.0
            updated = self.core.task(tid)
            while updated['status'] in {'QUEUED', 'PLANNING'} and time.time() < deadline:
                time.sleep(0.05)
                updated = self.core.task(tid)

        self.assertEqual(updated['status'], 'PLANNED')

        plan_path = proj / '.agents' / 'plans' / f'plan_{tid}.md'
        self.assertTrue(plan_path.is_file())
        content = plan_path.read_text(encoding='utf-8')
        self.assertIn('# Implementation Plan', content)
        self.assertIn('Optimize Dockerfile', content)

        events = self.core.events(tid)
        art_events = [e for e in events if e.get('kind') == 'plan.artifact']
        self.assertEqual(len(art_events), 1)
        self.assertEqual(art_events[0]['message'], f'.agents/plans/plan_{tid}.md')


if __name__ == '__main__':
    unittest.main()
