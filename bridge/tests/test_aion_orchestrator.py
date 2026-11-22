import json
import os
import re
import sys
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from bridge.core import LocalCore, QUOTA_ERROR_RE, HermesMemoryLedger


class AionOrchestratorTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.tmp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.core = LocalCore(self.root / 'home')
        self.core.create_project('orch_proj')

    def test_quota_error_regex(self):
        matches = [
            "Error: 429 Too Many Requests: quota exceeded for current model",
            "Rate limit reached for requests per minute",
            "insufficient_quota: You have exceeded your current quota",
            "Your credit balance is too low to complete this request",
            "Monthly usage limit reached",
        ]
        for m in matches:
            self.assertTrue(bool(QUOTA_ERROR_RE.search(m)), f"Expected match for: {m}")

        self.assertFalse(bool(QUOTA_ERROR_RE.search("TypeError: Cannot read property of undefined")))

    @patch('bridge.core.LocalCore._worker')
    def test_cross_agent_handoff_context_synthesized(self, mock_worker):
        proj_dir = self.core._project('orch_proj')
        HermesMemoryLedger.record_success(proj_dir, 'Verified: npm run build')

        # Create first task with codex
        t1 = self.core.start_task('orch_proj', 'First codex plan', 'plan', agent='codex')
        # Mark turn 1 complete so project is not busy for turn 2
        self.core.set_status(t1['id'], 'PLANNED')
        self.core.append(t1['id'], 'message', 'Codex initial plan response with architecture steps.')

        # Second turn in same task switching agent to antigravity
        t2 = self.core.start_task('orch_proj', 'Implement step 1', 'plan', agent='antigravity', task_id=t1['id'])
        self.assertEqual(t2['id'], t1['id'])
        self.assertEqual(t2['agent'], 'antigravity')

        events = self.core.events_page(t1['id'], 0)['events']
        log_events = [e for e in events if e['kind'] == 'log']
        self.assertTrue(any('Aion Handoff: Switched agent from codex to antigravity' in e['message'] for e in log_events))


if __name__ == '__main__':
    unittest.main()
