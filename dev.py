#!/usr/bin/env python3
"""One entry point for the isolated local Plus installation; Python 3.9+, stdlib only."""
import argparse
import contextlib
import fcntl
import json
import socket
import subprocess
import sys
from pathlib import Path

from ops.local_config import ConfigError, prepare, remember_initialized
from ops.runtime import check_compose, compose, docker_ready, refuse_untracked_volumes
from ops.migrations import migrate
from ops.project_setup import provision
from ops.demo_setup import DemoSetup, show_credentials


ROOT = Path(__file__).resolve().parent
SERVICES = ("bootstrap", "db", "redis", "backend", "web")


@contextlib.contextmanager
def operation_lock():
    runtime = ROOT / ".runtime"
    runtime.mkdir(exist_ok=True, mode=0o700)
    runtime.chmod(0o700)
    with (runtime / "operation.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ConfigError("已有本工程命令正在运行，请等待其完成。") from None
        yield


def check_port(config):
    # An existing web service may already own the port; only check on first startup.
    if (ROOT / ".runtime" / "initialized.json").exists():
        return
    with socket.socket() as connection:
        try:
            connection.bind(("127.0.0.1", int(config["WEB_PORT"])))
        except OSError:
            raise ConfigError("WEB_PORT 已被占用；请在 .env.local 中换一个端口。") from None


def main():
    parser = argparse.ArgumentParser(description="独立 Plus 本地环境；不会操作旧系统。")
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in (
        ("prepare", "生成并校验统一配置，不需要运行 Docker"),
        ("check", "检查脚本配置和 Compose，不启动容器"),
        ("up", "构建并启动本地环境，自动初始化空数据库"),
        ("dev", "启动开发模式：前端热更新，后端自动编译重启，沿用本地数据"),
        ("build", "只构建镜像，不启动或初始化数据库"),
        ("status", "显示本工程容器状态"),
        ("stop", "停止本工程容器，保留全部数据"),
        ("migrate", "执行本工程尚未执行的版本化数据库迁移"),
        ("provision", "发布缺少的项目演示审批流程，保留已有配置"),
        ("demo", "补充虚构公司、部门、人员及审批草稿，保留已有设置"),
    ):
        commands.add_parser(name, help=help_text)
    credentials_parser = commands.add_parser("credentials", help="查看初始账号密码")
    credentials_parser.add_argument("--demo", action="store_true", help="查看额外的演示账号")
    log_parser = commands.add_parser("logs", help="显示本工程日志，默认最后 150 行")
    log_parser.add_argument("service", nargs="?", choices=SERVICES)
    log_parser.add_argument("-f", "--follow", action="store_true")
    args = parser.parse_args()

    readonly = args.command in ("status", "logs", "credentials")
    if readonly and not (ROOT / ".env.local").exists():
        raise ConfigError("请先运行 python3 dev.py prepare 生成本工程的本地配置。")
    # Reading existing atomic configuration files must not hold the startup lock,
    # especially during a potentially unlimited logs -f session.
    with contextlib.nullcontext() if readonly else operation_lock():
        config = prepare(ROOT)
        if args.command == "credentials":
            if args.demo:
                show_credentials(ROOT)
                return
            print("以下是首次初始化密码；在页面中改密后以页面设置为准。")
            for account, key in (("admin", "ADMIN_PASSWORD"), ("test", "TEST_PASSWORD"), ("test1", "TEST1_PASSWORD")):
                print("%s: %s" % (account, config[key]))
            return
        if args.command == "prepare":
            print("配置已就绪：%s\n密码与原配置保持稳定；尚未启动任何容器。" % (ROOT / ".env.local"))
            return
        if args.command in ("check", "up", "build", "dev", "migrate", "provision", "demo"):
            check_compose(ROOT, config, development=args.command == "dev")
            if args.command == "check":
                check_compose(ROOT, config, development=True)
        if args.command == "check":
            print("配置及 Compose 隔离检查通过。此检查不代表镜像构建、登录或审批已通过。")
            return

        docker_ready()
        if args.command in ("up", "dev"):
            development = args.command == "dev"
            refuse_untracked_volumes(ROOT)
            check_port(config)
            remember_initialized(ROOT, config)
            compose(ROOT, config, ["build"], development=development)
            compose(ROOT, config, ["up", "-d", "--wait", "--wait-timeout", "360", "bootstrap", "db", "redis"])
            migrate(ROOT, config)
            compose(ROOT, config, ["up", "-d", "--no-deps", "--wait", "--wait-timeout",
                                   "1200" if development else "360"] +
                    (["--force-recreate"] if development else []) + ["backend", "web"],
                    development=development)
            provision(ROOT, config)
            print("服务已启动：http://127.0.0.1:%s\n查看登录密码：python3 dev.py credentials" % config["WEB_PORT"])
            print("test 为业务员，test1 为审批负责人；admin 维护账号与审批流程。")
            if development:
                print("开发模式已后台运行：保存前端自动更新；保存后端自动编译、重启（期间接口会短暂不可用）。")
                print("查看编译进度：python3 dev.py logs backend -f；切回镜像模式：python3 dev.py up")
        elif args.command == "build":
            compose(ROOT, config, ["build"])
        elif args.command == "status":
            compose(ROOT, config, ["ps", "-a"])
        elif args.command == "migrate":
            migrate(ROOT, config)
        elif args.command == "provision":
            provision(ROOT, config)
        elif args.command == "demo":
            DemoSetup(ROOT, config).run()
        elif args.command == "stop":
            compose(ROOT, config, ["stop"])
        elif args.command == "logs":
            arguments = ["logs", "--tail", "150"]
            if args.follow:
                arguments.append("-f")
            if args.service:
                arguments.append(args.service)
            compose(ROOT, config, arguments)


if __name__ == "__main__":
    try:
        main()
    except (ConfigError, FileNotFoundError, json.JSONDecodeError) as error:
        print("未执行完成：%s" % error, file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError:
        print("命令未成功。请查看上方错误；运行 python3 dev.py logs 查看本工程日志。"
              "\n数据卷不会自动删除，数据库初始化失败也不会自动重置。", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n已中断命令。容器可能仍在运行，可用 python3 dev.py status 查看。", file=sys.stderr)
        sys.exit(130)
