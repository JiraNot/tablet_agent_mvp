import tempfile
import unittest
from pathlib import Path

from bridge.core import HermesMemoryLedger


class HermesMemoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.proj = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_empty_memory_loads_cleanly(self):
        data = HermesMemoryLedger.load_memory(self.proj)
        self.assertEqual(data['proven_patterns'], [])
        self.assertEqual(data['known_pitfalls'], [])
        self.assertEqual(data['past_fixes'], [])
        self.assertEqual(HermesMemoryLedger.build_context(self.proj), '')

    def test_record_success_and_pitfall_and_fixes(self):
        HermesMemoryLedger.record_success(self.proj, 'Verified: npm run build')
        HermesMemoryLedger.record_fix(self.proj, 'swc arm64 missing', 'installed @next/swc-linux-arm64-musl')
        HermesMemoryLedger.record_pitfall(self.proj, 'npm test failed: jest out of memory')

        mem_file = self.proj / '.agents' / 'memory.md'
        self.assertTrue(mem_file.is_file())
        text = mem_file.read_text(encoding='utf-8')
        self.assertIn('## Proven Patterns', text)
        self.assertIn('Verified: npm run build', text)
        self.assertIn('## Past Fixes', text)
        self.assertIn('installed @next/swc-linux-arm64-musl', text)
        self.assertIn('## Known Pitfalls', text)
        self.assertIn('jest out of memory', text)

        data = HermesMemoryLedger.load_memory(self.proj)
        self.assertEqual(len(data['proven_patterns']), 1)
        self.assertEqual(len(data['past_fixes']), 1)
        self.assertEqual(len(data['known_pitfalls']), 1)

        ctx = HermesMemoryLedger.build_context(self.proj)
        self.assertIn('Hermes Memory Ledger (.agents/memory.md)', ctx)
        self.assertIn('Verified: npm run build', ctx)
        self.assertIn('installed @next/swc-linux-arm64-musl', ctx)


if __name__ == '__main__':
    unittest.main()
