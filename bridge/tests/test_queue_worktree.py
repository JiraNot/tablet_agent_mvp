import os
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from bridge.core import LocalCore


class QueueAndWorktreeTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.tmp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.core = LocalCore(self.root / 'home')
        self.core.create_project('queue_proj')

    @patch('bridge.core.LocalCore._worker')
    def test_task_queue_fifo_execution(self, mock_worker):
        # Fake an in-flight active task
        t1 = self.core.start_task('queue_proj', 'Task 1 in progress', 'plan', agent='codex')
        self.core.set_status(t1['id'], 'PLANNING')

        # Submit task 2 while task 1 is PLANNING
        t2 = self.core.start_task('queue_proj', 'Task 2 waiting in queue', 'plan', agent='codex')
        self.assertEqual(t2['status'], 'QUEUED')
        self.assertIn('queue_proj', self.core.task_queues)
        self.assertEqual(len(self.core.task_queues['queue_proj']), 1)

        # Complete task 1
        self.core.set_status(t1['id'], 'PLANNED')
        self.core._dispatch_next_queued_task('queue_proj')

        # Task 2 should now be dispatched and queue empty
        self.assertNotIn('queue_proj', self.core.task_queues)

    def test_git_worktree_lifecycle(self):
        if not shutil.which('git'):
            self.skipTest('git not available')
        proj = 'queue_proj'
        task_id = 'test-wt-001'
        wt_dir = self.core.create_worktree(proj, task_id)
        self.assertTrue(wt_dir.is_dir())
        self.assertIn(task_id, self.core.active_worktrees)

        # Cleanup
        self.core.cleanup_worktree(proj, task_id)
        self.assertNotIn(task_id, self.core.active_worktrees)
        self.assertFalse(wt_dir.exists())


if __name__ == '__main__':
    unittest.main()
