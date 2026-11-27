import tempfile
import unittest
from pathlib import Path

from bridge.core import BridgeError, LocalCore


class SkillsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.core = LocalCore(self.root)
        self.proj = self.core.workspaces / 'demo'
        self.proj.mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_builtin_skills_include_grill_ponytail_impeccable_and_essential(self):
        items = self.core.list_skills('demo')
        ids = {s['id'] for s in items}
        for req in (
            'grill-me',
            'ponytail',
            'impeccable',
            'tdd-verify',
            'coolify-deploy-debug',
            'security-audit',
            'web-qa-playwright',
        ):
            self.assertIn(req, ids)

    def test_toggle_skill_persists_and_injects_context(self):
        self.core.toggle_skill('demo', 'impeccable', True)
        items = {s['id']: s for s in self.core.list_skills('demo')}
        self.assertTrue(items['impeccable']['enabled'])
        self.assertFalse(items['ponytail']['enabled'])

        ctx = self.core._build_skills_context('demo', 'Improve dashboard layout')
        self.assertIn('Impeccable (/impeccable)', ctx)
        self.assertNotIn('/ponytail', ctx)

        self.core.toggle_skill('demo', 'impeccable', False)
        self.assertEqual(self.core._build_skills_context('demo', 'Improve dashboard layout'), '')

    def test_inline_slash_command_activates_skill(self):
        ctx = self.core._build_skills_context('demo', '/grill-me design a billing flow')
        self.assertIn('Grill Me (/grill-me)', ctx)

        ctx2 = self.core._build_skills_context('demo', 'Please /ponytail this module')
        self.assertIn('Ponytail (/ponytail)', ctx2)

    def test_create_custom_skill_writes_skill_md_and_enables(self):
        res = self.core.create_custom_skill(
            'demo',
            'API Reviewer',
            'Review REST endpoints',
            'Check status codes and pagination.',
        )
        self.assertEqual(res['id'], 'api-reviewer')
        self.assertTrue(res['enabled'])
        skill_md = self.proj / '.agents' / 'skills' / 'api-reviewer' / 'SKILL.md'
        self.assertTrue(skill_md.is_file())
        self.assertIn('Check status codes', skill_md.read_text(encoding='utf-8'))

        items = {s['id']: s for s in self.core.list_skills('demo')}
        self.assertIn('api-reviewer', items)
        self.assertTrue(items['api-reviewer']['enabled'])

        with self.assertRaises(BridgeError):
            self.core.create_custom_skill('demo', '', 'desc', 'instr')


if __name__ == '__main__':
    unittest.main()
