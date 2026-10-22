import os
import stat
import tempfile
import unittest
from pathlib import Path

from ops.local_config import ConfigError, compose_invocation, prepare, remember_initialized


class LocalEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_prepare_reuses_passwords_and_keeps_private_files_private(self):
        first = prepare(self.root)
        second = prepare(self.root)
        self.assertEqual(first, second)
        self.assertGreaterEqual(len(first["ADMIN_PASSWORD"]), 20)
        self.assertLessEqual(len(first["ADMIN_PASSWORD"]), 20)
        self.assertNotEqual(first["ADMIN_PASSWORD"], first["TEST_PASSWORD"])
        self.assertEqual(stat.S_IMODE((self.root / ".env.local").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE((self.root / ".runtime").stat().st_mode), 0o700)

    def test_rejects_old_system_port_before_any_runtime_action(self):
        prepare(self.root)
        self.replace("WEB_PORT", "18083")
        with self.assertRaises(ConfigError):
            prepare(self.root)

    def test_rejects_interpolation_and_shell_syntax_in_credentials(self):
        prepare(self.root)
        for value in ("a$HOME12345678901234567890", "$(touch nope)123456789012345", "'12345678901234567890"):
            self.replace("ADMIN_PASSWORD", value)
            with self.assertRaises(ConfigError):
                prepare(self.root)

    def test_invalid_boolean_is_not_silently_treated_as_false(self):
        prepare(self.root)
        self.replace("CAPTCHA_ENABLED", "ture")
        with self.assertRaises(ConfigError):
            prepare(self.root)

    def test_cannot_silently_replace_credentials_after_database_initialization(self):
        config = prepare(self.root)
        remember_initialized(self.root, config)
        self.replace("DB_PASSWORD", "DifferentPassword123456789")
        with self.assertRaises(ConfigError):
            prepare(self.root)

    def test_can_change_web_port_without_changing_database_passwords(self):
        config = prepare(self.root)
        remember_initialized(self.root, config)
        self.replace("WEB_PORT", "18881")
        updated = prepare(self.root)
        self.assertEqual(updated["WEB_PORT"], "18881")
        self.assertEqual(updated["DB_PASSWORD"], config["DB_PASSWORD"])

    def test_lost_env_after_initialization_does_not_generate_replacement_passwords(self):
        config = prepare(self.root)
        remember_initialized(self.root, config)
        (self.root / ".env.local").unlink()
        with self.assertRaises(ConfigError):
            prepare(self.root)
        self.assertFalse((self.root / ".env.local").exists())

    def test_compose_ignores_ambient_project_file_and_secret_overrides(self):
        config = prepare(self.root)
        command, env = compose_invocation(
            self.root, config, ["stop"],
            {"PATH": os.environ["PATH"], "COMPOSE_FILE": "/old/compose.yml",
             "COMPOSE_PROJECT_NAME": "em2-yun", "DB_PASSWORD": "wrong",
             "DOCKER_CONTEXT": "docker"},
        )
        self.assertEqual(command[0:4], ["docker", "compose", "-p", "em-plus-local"])
        self.assertIn(str(self.root.resolve() / "compose.yaml"), command)
        self.assertNotIn("COMPOSE_FILE", env)
        self.assertNotIn("COMPOSE_PROJECT_NAME", env)
        self.assertEqual(env["DB_PASSWORD"], config["DB_PASSWORD"])
        self.assertEqual(env["DOCKER_CONTEXT"], "docker")
        self.assertEqual(command[-1], "stop")

    def replace(self, key, value):
        path = self.root / ".env.local"
        lines = path.read_text().splitlines()
        path.write_text("\n".join(
            key + "=" + value if line.startswith(key + "=") else line for line in lines
        ) + "\n")

    def test_development_uses_explicit_overlay_without_changing_project_or_config(self):
        config = prepare(self.root)
        command, env = compose_invocation(self.root, config, ["up", "-d"],
                                           ambient={"COMPOSE_FILE": "/old/yun.yaml"}, development=True)
        files = [command[i + 1] for i, arg in enumerate(command[:-1]) if arg == "-f"]
        self.assertEqual(files, [str(self.root.resolve() / "compose.yaml"), str(self.root.resolve() / "compose.dev.yaml")])
        self.assertEqual(command[0:4], ["docker", "compose", "-p", "em-plus-local"])
        self.assertEqual(env["DB_PASSWORD"], config["DB_PASSWORD"])
        self.assertNotIn("COMPOSE_FILE", env)

    def test_image_mode_does_not_inherit_development_overlay(self):
        command, _ = compose_invocation(self.root, prepare(self.root), ["build"])
        self.assertEqual(command.count("-f"), 1)
        self.assertNotIn(str(self.root / "compose.dev.yaml"), command)


if __name__ == "__main__":
    unittest.main()
