#!/usr/bin/env python3
"""Exercise the migration guard against disposable Git histories, without a DB."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check-flyway-migrations.py"
MIGRATION = "backend/src/main/resources/db/migration/"
PRIVACY = "backend/src/main/resources/db/privacy/"
EXISTING = MIGRATION + "V20261015_03__existing.sql"


class FlywayMigrationTests(unittest.TestCase):
    def setUp(self):
        local = ROOT / ".local"
        local.mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="flyway-guard-test-", dir=local)
        self.addCleanup(self.directory.cleanup)
        self.repository = Path(self.directory.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Migration guard test")
        self.git("config", "user.email", "migration-test@example.invalid")
        self.write(EXISTING)
        self.write(MIGRATION + "B20260922_01__initial.sql")
        self.write(MIGRATION + "V20260922_01__reconcile.sql")
        self.write(PRIVACY + "V1__initial.sql")
        self.commit()
        self.git("tag", "develop-base")

    def git(self, *arguments):
        return subprocess.run(
            ["git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", *arguments],
            cwd=self.repository,
            check=True, capture_output=True, text=True,
        ).stdout.strip()

    def write(self, path, sql="SELECT 1;\n"):
        target = self.repository / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(sql, encoding="utf-8")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "--quiet", "--allow-empty", "-m", "Migration fixture")

    def check(self, expected_error=None, base="develop-base"):
        self.commit()
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--base-ref", base],
            cwd=self.repository, capture_output=True, text=True,
        )
        output = result.stdout + result.stderr
        if expected_error:
            self.assertEqual(result.returncode, 1, output)
            self.assertIn(expected_error, output)
        else:
            self.assertEqual(result.returncode, 0, output)
            self.assertIn("check passed", output)
        return output

    def test_unchanged_migrations_pass(self):
        self.check()

    def test_newer_migrations_pass(self):
        self.write(MIGRATION + "V20261016_01__new.sql")
        self.write(MIGRATION + "V20261015_04__new.sql")
        self.check()

    def test_lower_version_is_rejected(self):
        self.write(MIGRATION + "V20261012_01__late_pr.sql")
        output = self.check("must be greater than develop version 20261015.3")
        self.assertIn("V20261012_01__late_pr.sql", output)

    def test_existing_version_cannot_be_reused_with_a_different_description(self):
        self.write(MIGRATION + "V20261015_03__other.sql")
        self.check("Duplicate V version")

    def test_duplicate_versions_among_new_files_are_rejected(self):
        self.write(MIGRATION + "V20261016_01__first.sql")
        self.write(MIGRATION + "V20261016_01__second.sql")
        self.check("Duplicate V version")

    def test_version_aliases_are_duplicates(self):
        self.write(MIGRATION + "V020261015.003.0__alias.sql")
        self.check("Duplicate V version")

    def test_version_comparison_is_numeric(self):
        self.write(MIGRATION + "V20261015_9__previous.sql")
        self.commit()
        latest_develop = self.git("rev-parse", "HEAD")
        self.write(MIGRATION + "V20261015_10__new.sql")
        self.check(base=latest_develop)

    def test_modified_existing_migration_is_rejected(self):
        self.write(EXISTING, "SELECT 2;\n")
        self.check("Modified existing migration: " + EXISTING)

    def test_deleted_existing_migration_is_rejected(self):
        (self.repository / EXISTING).unlink()
        self.check("Deleted or renamed existing migration: " + EXISTING)

    def test_renamed_existing_migration_is_rejected_even_with_a_higher_version(self):
        (self.repository / EXISTING).rename(self.repository / (MIGRATION + "V20261016_01__renamed.sql"))
        self.check("Deleted or renamed existing migration: " + EXISTING)

    def test_moving_an_existing_migration_to_a_subdirectory_is_rejected(self):
        destination = self.repository / MIGRATION / "moved" / Path(EXISTING).name
        destination.parent.mkdir()
        (self.repository / EXISTING).rename(destination)
        self.check("Deleted or renamed existing migration: " + EXISTING)

    def test_baseline_content_is_immutable(self):
        baseline = MIGRATION + "B20260922_01__initial.sql"
        self.write(baseline, "SELECT 2;\n")
        self.check("Modified existing migration: " + baseline)

    def test_duplicate_baseline_versions_are_rejected(self):
        self.write(MIGRATION + "B20260922_01__other.sql")
        self.check("Duplicate B version")

    def test_privacy_versions_are_compared_with_their_own_history(self):
        self.write(PRIVACY + "V2__new.sql")
        self.check()

    def test_lower_privacy_version_is_rejected(self):
        self.write(PRIVACY + "V0_5__late.sql")
        self.check("must be greater than develop version 1")

    def test_privacy_content_is_immutable(self):
        privacy = PRIVACY + "V1__initial.sql"
        self.write(privacy, "SELECT 2;\n")
        self.check("Modified existing migration: " + privacy)

    def test_same_version_in_independent_locations_is_allowed(self):
        self.write(PRIVACY + "V20261015_03__new.sql")
        self.check()

    def test_duplicate_versions_in_subdirectories_are_rejected(self):
        self.write(MIGRATION + "nested/V20261015_03__duplicate.sql")
        self.check("Duplicate V version")

    def test_invalid_version_filename_is_rejected(self):
        self.write(MIGRATION + "V20261012_bad__invalid.sql")
        self.check("Invalid migration filename")

    def test_unrelated_files_do_not_affect_the_guard(self):
        self.write("README.md", "Fixture documentation\n")
        self.check()

    def test_missing_base_revision_fails_instead_of_skipping_checks(self):
        self.check("Cannot read migration history", base="missing-develop")


if __name__ == "__main__":
    unittest.main()
