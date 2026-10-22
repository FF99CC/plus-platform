import tempfile
import unittest
import fcntl
import json
from pathlib import Path
from unittest.mock import patch

import dev
from ops.local_config import prepare

from ops.local_config import ConfigError
from ops.runtime import require_local_endpoint, validate_compose_model


class IsolationTests(unittest.TestCase):
    def test_prepare_allows_dependency_manifest_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "backend").mkdir()
            (root / "backend/pom.xml").write_text("updated dependency configuration")
            (root / "sources.lock.json").write_text(json.dumps({
                "verification_files": {"backend/pom.xml": "old snapshot hash"},
            }))
            with patch.object(dev, "ROOT", root), patch("sys.argv", ["dev.py", "prepare"]):
                dev.main()
            self.assertTrue((root / ".env.local").exists())

    def test_standalone_data_commands_validate_isolation_before_work(self):
        for command in ("migrate", "provision"):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                prepare(root)
                with patch.object(dev, "ROOT", root), patch("sys.argv", ["dev.py", command]), \
                        patch.object(dev, "docker_ready"), \
                        patch.object(dev, "check_compose", side_effect=ConfigError("unsafe Compose")), \
                        patch.object(dev, command) as action:
                    with self.assertRaises(ConfigError):
                        dev.main()
                    action.assert_not_called()

    def test_dev_starts_same_data_services_without_destructive_docker_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = prepare(root)
            actions = []

            def docker_boundary(root_arg, config, arguments, **kwargs):
                self.assertEqual(root_arg, root)
                self.assertEqual(config, original)
                actions.append((arguments, kwargs.get("development", False)))

            with patch.object(dev, "ROOT", root), patch("sys.argv", ["dev.py", "dev"]), \
                    patch.object(dev, "check_compose"), \
                    patch.object(dev, "docker_ready"), patch.object(dev, "check_port"), \
                    patch.object(dev, "refuse_untracked_volumes"), patch.object(dev, "migrate"), \
                    patch.object(dev, "provision"), patch.object(dev, "compose", side_effect=docker_boundary):
                dev.main()
            self.assertEqual(prepare(root), original)
            self.assertTrue((root / ".runtime" / "initialized.json").exists())
            for arguments, development in actions:
                self.assertFalse(set(arguments) & {"down", "rm", "-v", "--renew-anon-volumes", "--remove-orphans"})
                if arguments[0] == "up" and "db" in arguments:
                    self.assertFalse(development)
                if arguments[0] == "up" and "web" in arguments:
                    self.assertTrue(development)
            self.assertTrue(any("web" in args and mode for args, mode in actions))

    def test_following_logs_does_not_block_stop_in_another_terminal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prepare(root)

            def observe_streaming(*args, **kwargs):
                # Exercise the actual filesystem lock while the Docker boundary is replaced.
                with (root / ".runtime" / "operation.lock").open("a") as second_terminal:
                    fcntl.flock(second_terminal, fcntl.LOCK_EX | fcntl.LOCK_NB)

            with patch.object(dev, "ROOT", root), patch("sys.argv", ["dev.py", "logs", "backend", "-f"]), \
                    patch.object(dev, "docker_ready"), patch.object(dev, "compose", side_effect=observe_streaming):
                dev.main()

    def test_remote_docker_endpoints_are_refused(self):
        for endpoint in ("tcp://192.168.101.179:2375", "ssh://remote", "https://example.com"):
            with self.assertRaises(ConfigError):
                require_local_endpoint(endpoint)
        require_local_endpoint("unix:///var/run/docker.sock")

    def test_compose_cannot_expose_database_or_bind_old_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = {
                "name": "em-plus-local",
                "services": {"web": {"ports": [{"host_ip": "127.0.0.1", "target": 80, "published": "18880"}]}},
            }
            validate_compose_model(root, model)
            model["services"]["db"] = {"ports": [{"host_ip": "127.0.0.1", "target": 3306, "published": "3306"}]}
            with self.assertRaises(ConfigError):
                validate_compose_model(root, model)
            model["services"]["db"] = {"volumes": [{"type": "bind", "source": "/old/yun", "target": "/data"}]}
            with self.assertRaises(ConfigError):
                validate_compose_model(root, model)

    def test_compose_rejects_shared_networks_and_volumes(self):
        for resource in ("volumes", "networks"):
            model = {"name": "em-plus-local", "services": {},
                     resource: {"data": {"name": "em2-yun_data", "external": True}}}
            with self.assertRaises(ConfigError):
                validate_compose_model(Path("/tmp/new"), model)


if __name__ == "__main__":
    unittest.main()
