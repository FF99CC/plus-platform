"""Local Docker operations, always scoped to this directory and Compose project."""
import json
import os
import subprocess
from pathlib import Path

from .local_config import ConfigError, PROJECT, compose_invocation


def require_local_endpoint(endpoint):
    if not endpoint.startswith("unix://"):
        raise ConfigError("仅允许本机 Docker Unix socket；不会连接远程 Docker 服务。")


def docker_ready():
    if os.environ.get("DOCKER_HOST") and not os.environ.get("DOCKER_CONTEXT"):
        endpoint = os.environ["DOCKER_HOST"]
    else:
        result = subprocess.run(["docker", "context", "inspect"], text=True, capture_output=True, check=True)
        endpoint = json.loads(result.stdout)[0]["Endpoints"]["docker"]["Host"]
    require_local_endpoint(endpoint)
    result = subprocess.run(["docker", "info"], text=True, capture_output=True)
    if result.returncode:
        raise ConfigError("本机 Docker 尚未就绪。请启动 Docker 后重试；没有启动或改动任何服务。")


def validate_compose_model(root, model):
    if model.get("name") != PROJECT:
        raise ConfigError("Compose 项目名与独立环境不一致。")
    for kind in ("volumes", "networks"):
        for resource in model.get(kind, {}).values():
            if resource.get("external") or not resource.get("name", PROJECT + "_").startswith(PROJECT + "_"):
                raise ConfigError("不允许引用独立环境以外的共享卷或网络。")
    for name, service in model.get("services", {}).items():
        if service.get("network_mode") or service.get("privileged") or service.get("container_name"):
            raise ConfigError("不允许主机网络、特权容器或固定的共享容器名。")
        for port in service.get("ports", []):
            if (name != "web" or port.get("host_ip") != "127.0.0.1" or
                    int(port.get("target", 0)) != 80 or str(port.get("published")) == "18083"):
                raise ConfigError("仅允许 web 在本机独立端口开放访问。")
        for volume in service.get("volumes", []):
            if volume.get("type") == "bind":
                source = Path(volume["source"]).resolve()
                try:
                    source.relative_to(Path(root).resolve())
                except ValueError:
                    raise ConfigError("不允许挂载新工程目录以外的路径。") from None


def compose(root, config, arguments, capture=False, development=False):
    command, env = compose_invocation(root, config, arguments, development=development)
    return subprocess.run(command, env=env, cwd=str(root), text=True,
                          capture_output=capture, check=True)


def check_compose(root, config, development=False):
    # The JSON includes secrets; inspect only in memory and never print it.
    result = compose(root, config, ["config", "--format", "json"], capture=True, development=development)
    model = json.loads(result.stdout)
    validate_compose_model(root, model)
    return model


def refuse_untracked_volumes(root):
    if (root / ".runtime" / "initialized.json").exists():
        return
    result = subprocess.run(
        ["docker", "volume", "ls", "--filter", "label=com.docker.compose.project=" + PROJECT,
         "--format", "{{.Name}}"], capture_output=True, text=True, check=True,
    )
    if result.stdout.strip():
        raise ConfigError("已存在本工程的数据卷但缺少初始化记录。请恢复原 .env.local 和 .runtime；不会重新初始化已有卷。")
