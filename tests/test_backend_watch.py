import importlib.util
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path


WATCHER_PATH = Path(__file__).resolve().parents[1] / "ops/backend_watch.py"


class BackendWatchTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(WATCHER_PATH.exists(), "backend source watcher is missing")
        spec = importlib.util.spec_from_file_location("backend_watch", WATCHER_PATH)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "App.java").write_text("one")
        self.workspace = self.root / "workspace"
        self.jar = self.root / "app/app.jar"
        self.config = self.root / "application.properties"
        self.config.write_text("server.port=8080")
        self.builder = self.root / "builder.py"
        self.builder.write_text('''import pathlib, signal, subprocess, sys, time
root = pathlib.Path.cwd()
assert sys.argv[1:] == ["-B", "-ntp", "-pl", "ruoyi-admin", "-am", "-Pdev,gen", "-DskipTests", "package"]
with (root.parent / "builds").open("a") as log:
    log.write("build\\n")
source = (root / "App.java").read_text()
if (root / "spawn-child").exists():
    child = subprocess.Popen([sys.executable, str(root.parent / "child.py")])
    def stop(signum, frame):
        child.wait(timeout=2)
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, stop)
if (root / "slow").exists():
    (root.parent / "building").touch()
    while not (root.parent / "release").exists():
        time.sleep(.01)
if source == "bad":
    sys.exit(1)
target = root / "ruoyi-admin/target"
target.mkdir(parents=True, exist_ok=True)
(target / "ruoyi-admin.jar").write_text(source)
''')
        self.java = self.root / "java.py"
        self.java.write_text('''import os, pathlib, signal, sys, time
assert sys.argv[1] == "-jar"
assert sys.argv[3].startswith("--spring.config.additional-location=file:")
jar = pathlib.Path(sys.argv[2])
with (jar.parent.parent / "runs").open("a") as log:
    log.write(str(os.getpid()) + ":" + jar.read_text() + "\\n")
while True:
    time.sleep(.1)
''')
        self.watcher = self.module.BackendWatcher(
            source=self.source, workspace=self.workspace, jar=self.jar,
            config=self.config, debounce=.04,
            maven=[sys.executable, str(self.builder)], java=[sys.executable, str(self.java)],
        )

    def tearDown(self):
        if hasattr(self, "watcher"):
            self.watcher.stop()
        if hasattr(self, "temp"):
            self.temp.cleanup()

    def pump_until(self, predicate, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.watcher.tick()
            if predicate():
                return
            time.sleep(.01)
        self.fail("watcher did not reach expected state")

    def lines(self, name):
        path = self.root / name
        return path.read_text().splitlines() if path.exists() else []

    def start(self):
        self.pump_until(lambda: len(self.lines("runs")) == 1)
        return self.watcher.app_process.pid

    def test_initial_build_mirrors_source_excludes_outputs_and_runs_isolated_jar(self):
        for name in ("target/stale", ".git/config", ".flattened-pom.xml"):
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("excluded")
        self.start()
        self.assertEqual((self.workspace / "App.java").read_text(), "one")
        self.assertFalse((self.workspace / "target").exists())
        self.assertFalse((self.workspace / ".git").exists())
        self.assertFalse((self.workspace / ".flattened-pom.xml").exists())
        (self.workspace / "ruoyi-admin/target/ruoyi-admin.jar").write_text("changed by build")
        self.assertEqual(self.jar.read_text(), "one")

    def test_failed_build_keeps_last_java_and_waits_for_new_edit(self):
        pid = self.start()
        (self.source / "App.java").write_text("bad")
        self.pump_until(lambda: len(self.lines("builds")) == 2 and self.watcher.build_process is None)
        for _ in range(12):
            self.watcher.tick()
            time.sleep(.01)
        self.assertEqual(len(self.lines("builds")), 2)
        self.assertEqual(self.watcher.app_process.pid, pid)
        self.assertIsNone(self.watcher.app_process.poll())
        self.assertEqual(self.jar.read_text(), "one")
        (self.source / "App.java").write_text("fixed")
        self.pump_until(lambda: len(self.lines("runs")) == 2)
        self.assertEqual(self.jar.read_text(), "fixed")
        self.assertNotEqual(self.watcher.app_process.pid, pid)

    def test_edits_during_build_trigger_followup_without_stopping_running_java(self):
        pid = self.start()
        (self.source / "slow").touch()
        (self.source / "App.java").write_text("two")
        self.pump_until(lambda: (self.root / "building").exists())
        self.assertEqual(self.watcher.app_process.pid, pid)
        (self.source / "App.java").write_text("three")
        self.watcher.tick()
        (self.root / "release").touch()
        self.pump_until(lambda: len(self.lines("runs")) == 3)
        self.assertEqual(self.jar.read_text(), "three")
        self.assertEqual(len(self.lines("builds")), 3)

    def test_deleted_sources_remove_stale_classes_before_next_build(self):
        (self.source / "Removed.java").write_text("class Removed {}")
        self.start()
        stale = self.workspace / "module/target/classes/Removed.class"
        stale.parent.mkdir(parents=True)
        stale.write_text("old class")
        (self.source / "Removed.java").unlink()
        self.pump_until(lambda: len(self.lines("runs")) == 2)
        self.assertFalse((self.workspace / "Removed.java").exists())
        self.assertFalse(stale.exists())

    def test_initial_sync_cleans_persisted_workspace_after_offline_source_deletion(self):
        self.workspace.mkdir()
        (self.workspace / "Deleted.java").write_text("old source")
        stale = self.workspace / "module/target/Deleted.class"
        stale.parent.mkdir(parents=True)
        stale.write_text("old class")
        self.start()
        self.assertFalse((self.workspace / "Deleted.java").exists())
        self.assertFalse(stale.exists())

    def test_same_size_edit_with_preserved_mtime_is_detected(self):
        self.start()
        path = self.source / "App.java"
        before = path.stat()
        path.write_text("two")
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.pump_until(lambda: len(self.lines("runs")) == 2)
        self.assertEqual(self.jar.read_text(), "two")
        self.assertGreater((self.workspace / "App.java").stat().st_mtime_ns, before.st_mtime_ns,
                           "Maven needs a fresh destination timestamp to recompile changed content")

    def test_burst_is_debounced_to_one_build(self):
        self.start()
        for value in ("two", "three", "four"):
            (self.source / "App.java").write_text(value)
            self.watcher.tick()
            time.sleep(.01)
        self.assertEqual(len(self.lines("builds")), 1)
        self.pump_until(lambda: len(self.lines("runs")) == 2)
        self.assertEqual(self.jar.read_text(), "four")
        self.assertEqual(len(self.lines("builds")), 2)

    def test_stop_terminates_both_build_and_running_java(self):
        self.start()
        app = self.watcher.app_process
        (self.source / "slow").touch()
        self.pump_until(lambda: (self.root / "building").exists())
        build = self.watcher.build_process
        self.watcher.stop()
        self.assertIsNotNone(app.poll())
        self.assertIsNotNone(build.poll())

    def test_stop_signals_build_descendants_not_only_maven_parent(self):
        (self.root / "child.py").write_text('''import pathlib, signal, time
root = pathlib.Path(__file__).parent
def stop(signum, frame):
    (root / "child-stopped").touch()
    raise SystemExit(0)
signal.signal(signal.SIGTERM, stop)
(root / "child-ready").touch()
while True:
    time.sleep(.1)
''')
        (self.source / "spawn-child").touch()
        (self.source / "slow").touch()
        self.pump_until(lambda: (self.root / "child-ready").exists())
        self.watcher.stop()
        self.assertTrue((self.root / "child-stopped").exists())


if __name__ == "__main__":
    unittest.main()
