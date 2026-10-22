import tempfile
import unittest
from pathlib import Path

from ops.bootstrap import render_password_sql, write_seed


HASH = "$2b$12$" + "A" * 53


class SeedTests(unittest.TestCase):
    def test_seed_orders_credentials_before_completion_and_keeps_upstream_sql(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "source"
            target = base / "target"
            source.mkdir()
            (source / "ry_vue.sql").write_text("-- upstream base\n")
            (source / "ry_workflow.sql").write_text("-- upstream flow\n")
            write_seed(source, target, {name: HASH for name in ("admin", "test", "test1")})
            self.assertEqual((target / "010-base.sql").read_text(), "SET NAMES utf8mb4;\n-- upstream base\n")
            self.assertEqual((target / "020-workflow.sql").read_text(), "SET NAMES utf8mb4;\n-- upstream flow\n")
            sql = (target / "090-credentials.sql").read_text()
            self.assertTrue(sql.startswith("SET NAMES utf8mb4;\n"))
            self.assertIn("user_name = 'admin'", sql)
            self.assertIn("user_name = 'test1'", sql)
            self.assertNotIn("DROP", sql)
            self.assertIn("local_bootstrap_complete", (target / "099-complete.sql").read_text())
            self.assertEqual(len(list(target.glob("*.sql"))), 4)
            # Repeated preparation must not append duplicate SQL files.
            write_seed(source, target, {name: HASH for name in ("admin", "test", "test1")})
            self.assertEqual(len(list(target.glob("*.sql"))), 4)

    def test_sql_rejects_non_bcrypt_strings_and_unexpected_accounts(self):
        with self.assertRaises(ValueError):
            render_password_sql({"admin": "'; DROP TABLE sys_user;"})
        with self.assertRaises(ValueError):
            render_password_sql({"admin'": HASH})


if __name__ == "__main__":
    unittest.main()
