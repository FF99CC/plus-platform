#!/usr/bin/env python3
"""Poll the readonly source tree and rebuild the backend in a separate workspace."""

import hashlib
import os
import shutil
import signal
import subprocess
import time
from pathlib import Path


IGNORED_DIRS = {".git", "target"}
MAVEN_ARGS = ["-B", "-ntp", "-pl", "ruoyi-admin", "-am", "-Pdev,gen", "-DskipTests", "package"]


def log(message):
    print(f"[backend-dev] {message}", flush=True)


def snapshot(root):
    """Hash content so fast edits and preserved timestamps are still detected."""
    if not root.is_dir():
        raise FileNotFoundError(f"源码目录不存在: {root}")
    files = {}
    for directory, dirs, names in os.walk(root):
        parent = Path(directory)
        dirs[:] = sorted(name for name in dirs
                         if name not in IGNORED_DIRS and not (parent / name).is_symlink())
        for name in sorted(names):
            path = parent / name
            if name == ".flattened-pom.xml" or path.is_symlink():
                continue
            files[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files


def sync_source(source, workspace, files):
    """Keep build outputs for normal edits; discard them after source deletion."""
    workspace.mkdir(parents=True, exist_ok=True)
    previous = snapshot(workspace)
    removed = previous.keys() - files.keys()
    for name in removed:
        (workspace / name).unlink()
    # Empty directories also have to disappear for directory -> file renames.
    for directory, dirs, names in os.walk(workspace, topdown=False):
        path = Path(directory)
        if path != workspace and not any(path.iterdir()):
            path.rmdir()
    if removed:
        log("源码删除或重命名，清理旧编译产物")
        for directory, dirs, _ in os.walk(workspace):
            for name in list(dirs):
                if name == "target":
                    shutil.rmtree(Path(directory) / name)
                    dirs.remove(name)
    for name, digest in files.items():
        if previous.get(name) != digest:
            destination = workspace / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            # Fresh timestamps make Maven notice content changes even when an
            # editor or checkout preserves the source file's old mtime.
            shutil.copyfile(source / name, destination)


def stop_process(process):
    """Maven may spawn Java children; terminate its whole process group."""
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait()
        return
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    # A child could ignore SIGTERM even after the group leader has exited.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


class BackendWatcher:
    def __init__(self, source=Path("/source/backend"), workspace=Path("/workspace/backend"),
                 jar=Path("/app/app.jar"), config=Path("/config/application-local.properties"),
                 debounce=1.0, maven=None, java=None):
        self.source = Path(source)
        self.workspace = Path(workspace)
        self.jar = Path(jar)
        self.config = Path(config)
        self.debounce = debounce
        self.maven = maven if maven is not None else ["mvn"]
        self.java = java if java is not None else ["java"]
        self.observed = None
        self.changed_at = 0
        self.pending = True
        self.build_process = None
        self.app_process = None
        self.stopped = False

    def tick(self):
        if self.stopped:
            return
        current = snapshot(self.source)
        now = time.monotonic()
        if current != self.observed:
            first = self.observed is None
            self.observed = current
            self.changed_at = now - self.debounce if first else now
            self.pending = True
            if not first:
                log("检测到源码变化，等待保存完成")

        if self.build_process is not None and self.build_process.poll() is not None:
            result = self.build_process.returncode
            self.build_process = None
            if result == 0:
                self.restart_app()
            else:
                log(f"编译失败 (exit {result})，保留上次服务；修改源码后重试")

        if self.app_process is not None and self.app_process.poll() is not None:
            log(f"Java 服务已退出 (exit {self.app_process.returncode})；修改源码后重新构建")
            self.app_process = None

        if self.pending and self.build_process is None and now - self.changed_at >= self.debounce:
            sync_source(self.source, self.workspace, self.observed)
            log("开始 Maven 编译（首次编译可能需要下载依赖）")
            self.build_process = subprocess.Popen(
                self.maven + MAVEN_ARGS, cwd=self.workspace, start_new_session=True,
            )
            self.pending = False

    def restart_app(self):
        built_jar = self.workspace / "ruoyi-admin/target/ruoyi-admin.jar"
        staged_jar = self.jar.with_suffix(".jar.next")
        try:
            self.jar.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(built_jar, staged_jar)
        except OSError as error:
            log(f"无法复制新 JAR，保留上次服务: {error}")
            return
        log("编译成功，重启 Java 服务")
        stop_process(self.app_process)
        self.app_process = None
        staged_jar.replace(self.jar)
        self.app_process = subprocess.Popen(
            self.java + ["-jar", str(self.jar),
                         f"--spring.config.additional-location=file:{self.config}"],
            cwd=self.jar.parent, start_new_session=True,
        )

    def stop(self):
        self.stopped = True
        stop_process(self.build_process)
        stop_process(self.app_process)
        self.build_process = None
        self.app_process = None


def main():
    watcher = BackendWatcher()

    def request_stop(signum, frame):
        watcher.stopped = True

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    log("开发监听已启动，每秒检查源码变化")
    try:
        while not watcher.stopped:
            try:
                watcher.tick()
            except OSError as error:
                # Editors may briefly remove/rename a file during an atomic save.
                log(f"源码同步暂不可用，稍后重试: {error}")
            time.sleep(1)
    finally:
        log("停止编译和 Java 服务")
        watcher.stop()


if __name__ == "__main__":
    main()
