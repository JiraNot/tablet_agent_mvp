import json
import os
import sys
from pathlib import Path
import tempfile
import unittest
import zipfile

from bridge.core import LocalCore


class ProjectExportImportTests(unittest.TestCase):
    def setUp(self):
        kwargs = {'ignore_cleanup_errors': True} if sys.version_info >= (3, 10) else {}
        self.tmp = tempfile.TemporaryDirectory(**kwargs)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.core = LocalCore(self.root / 'home')
        self.core.create_project('exp_proj')

    def test_export_and_import_project_zip(self):
        proj_dir = self.core._project('exp_proj')
        (proj_dir / 'index.html').write_text('<h1>Hello Tablet</h1>', encoding='utf-8')
        (proj_dir / '.env').write_text('SECRET=12345', encoding='utf-8')
        (proj_dir / 'app-debug.apk').write_bytes(b'PK_FAKE_APK_CONTENT')

        # Run task to generate SQLite history
        t = self.core.start_task('exp_proj', 'Plan app', 'plan')
        self.core.set_status(t['id'], 'PLANNED', result='Plan ready')

        # Check list_project_apks
        apks = self.core.list_project_apks('exp_proj')
        self.assertEqual(len(apks), 1)
        self.assertEqual(apks[0]['name'], 'app-debug.apk')

        # Export without .env (default secure)
        res = self.core.export_project('exp_proj', include_env=False)
        self.assertTrue(Path(res['path']).is_file())
        with zipfile.ZipFile(res['path'], 'r') as zf:
            names = zf.namelist()
            self.assertIn('project_state.json', names)
            self.assertIn('index.html', names)
            self.assertNotIn('.env', names)

        # Import into fresh project name
        imported = self.core.import_project(res['path'], target_name='restored_proj')
        self.assertEqual(imported['name'], 'restored_proj')
        restored_dir = self.core._project('restored_proj')
        self.assertTrue((restored_dir / 'index.html').is_file())

        # Check restored tasks in SQLite
        tasks = self.core.tasks('restored_proj')
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0]['prompt'], 'Plan app')


if __name__ == '__main__':
    unittest.main()
