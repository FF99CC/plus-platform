import hashlib
import tempfile
import unittest
from pathlib import Path
from ops.local_config import ConfigError
from ops.migrations import pending_migrations

class MigrationTests(unittest.TestCase):
    def test_applied_migration_is_never_replayed_and_changed_history_fails(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'V001__demo.sql'; p.write_text('SELECT 1;')
            checksum=hashlib.sha256(p.read_bytes()).hexdigest()
            self.assertEqual(pending_migrations(Path(d), {p.name:(checksum, '1')}), [])
            p.write_text('SELECT 2;')
            with self.assertRaises(ConfigError): pending_migrations(Path(d), {p.name:(checksum,'1')})
    def test_failed_migration_requires_review_not_automatic_retry(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'V001__demo.sql'; p.write_text('SELECT 1;')
            checksum=hashlib.sha256(p.read_bytes()).hexdigest()
            with self.assertRaises(ConfigError): pending_migrations(Path(d), {p.name:(checksum,'0')})
    def test_new_migrations_are_applied_in_numeric_filename_order(self):
        with tempfile.TemporaryDirectory() as d:
            for n in ('V002__b.sql','V001__a.sql'): (Path(d)/n).write_text('SELECT 1;')
            self.assertEqual([p.name for p,_ in pending_migrations(Path(d),{})], ['V001__a.sql','V002__b.sql'])
