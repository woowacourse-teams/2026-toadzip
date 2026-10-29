#!/usr/bin/env python3
"""Check runtime preparation without relying on an operational .local directory."""

import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import bluegreen
spec = importlib.util.spec_from_file_location('prepare_bluegreen', ROOT / 'scripts/prepare-bluegreen.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareTests(unittest.TestCase):
    def setUp(self):
        (ROOT / '.local').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='prepare-test-', dir=ROOT / '.local')
        self.folder = Path(self.temporary.name)
        self.runtime = self.folder / 'runtime'
        self.source = ROOT / 'infra/nginx/edge/default.conf'
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()

    def tearDown(self):
        self.output.__exit__(None, None, None)
        self.temporary.cleanup()

    def test_first_prepare_copies_template_and_creates_persistent_asset_directory(self):
        destination = module.prepare(self.runtime, self.source)
        self.assertEqual(destination.read_text(), self.source.read_text())
        self.assertTrue((self.runtime / 'assets').is_dir())
        self.assertTrue((self.runtime / 'deploy.lock').is_file())

    def test_existing_active_colors_and_assets_are_never_overwritten(self):
        destination = module.prepare(self.runtime, self.source)
        current = self.source.read_text().replace('server frontend:8080;', 'server frontend-green:8080;')
        destination.write_text(current)
        asset = self.runtime / 'assets/old.js'
        asset.write_text('keep')
        module.prepare(self.runtime, self.source)
        self.assertEqual(destination.read_text(), current)
        self.assertEqual(asset.read_text(), 'keep')

    def test_explicit_configuration_source_is_copied_without_moving_or_deleting_it(self):
        original = self.folder / 'current.conf'
        original.write_text(self.source.read_text().replace('server backend:8080;', 'server backend-blue:8080;'))
        destination = module.prepare(self.runtime, original)
        self.assertEqual(destination.read_bytes(), original.read_bytes())
        self.assertTrue(original.is_file())

    def test_invalid_template_is_rejected_before_creating_runtime(self):
        original = self.folder / 'invalid.conf'
        original.write_text('not a deployment configuration')
        with self.assertRaises(bluegreen.DeploymentError):
            module.prepare(self.runtime, original)
        self.assertFalse(self.runtime.exists())

    def test_busy_deployment_blocks_preparation(self):
        module.prepare(self.runtime, self.source)
        with (self.runtime / 'deploy.lock').open('a') as handle:
            module.fcntl.flock(handle, module.fcntl.LOCK_EX | module.fcntl.LOCK_NB)
            with self.assertRaisesRegex(bluegreen.DeploymentError, 'Another deployment'):
                module.prepare(self.runtime, self.source)

    def test_alias_uses_the_same_asset_and_lock_directory_as_deployment(self):
        module.prepare(self.runtime, self.source)
        alias = self.folder / 'alias'
        alias.mkdir()
        (alias / 'edge').symlink_to(self.runtime / 'edge', target_is_directory=True)
        module.prepare(alias, self.source)
        self.assertFalse((alias / 'assets').exists())
        self.assertTrue((self.runtime / 'assets').is_dir())

    def test_operational_defaults_and_template_safety(self):
        self.assertEqual(bluegreen.DEFAULT_RUNTIME, ROOT / 'infra/runtime/bluegreen')
        with self.assertRaisesRegex(bluegreen.DeploymentError, 'tracked configuration'):
            module.prepare(ROOT / 'infra/nginx', self.source)
        for path in ('scripts/bluegreen.py', 'scripts/release-bluegreen.py', 'scripts/deploy-bluegreen.ps1',
                     'scripts/switch-frontend.ps1', 'compose.yaml',
                     '.github/workflows/deploy-dev.yml', '.github/workflows/deploy-prod.yml'):
            self.assertNotIn('.local/', (ROOT / path).read_text(), path)


if __name__ == '__main__':
    unittest.main()
