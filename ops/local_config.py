"""Private, validated local configuration. Compatible with Python 3.9+."""
import hashlib
import json
import os
import re
import secrets
import tempfile
from pathlib import Path


PROJECT = "em-plus-local"
SECRET_KEYS = (
    "DB_ROOT_PASSWORD", "DB_PASSWORD", "REDIS_PASSWORD", "JWT_SECRET",
    "ADMIN_PASSWORD", "TEST_PASSWORD", "TEST1_PASSWORD",
)
LOGIN_KEYS = {"ADMIN_PASSWORD", "TEST_PASSWORD", "TEST1_PASSWORD"}
DEFAULTS = {"WEB_PORT": "18880", "CAPTCHA_ENABLED": "true"}
KEYS = set(DEFAULTS) | set(SECRET_KEYS)


class ConfigError(ValueError):
    pass


def private_write(path, content):
    path = Path(path)
    fd, name = tempfile.mkstemp(prefix="." + path.name + "-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(content)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def load_config(path):
    result = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key not in KEYS or key in result:
            raise ConfigError("配置第 %s 行无效、重复或包含未知配置项；请参考 .env.example。" % number)
        result[key] = value
    if set(result) != KEYS:
        raise ConfigError("配置项不完整；请对照 .env.example 恢复，切勿重新生成已有数据库的密码。")
    if not re.fullmatch(r"[0-9]{4,5}", result["WEB_PORT"]):
        raise ConfigError("WEB_PORT 必须为 1024–65535 的端口。")
    port = int(result["WEB_PORT"])
    if not 1024 <= port <= 65535 or port == 18083:
        raise ConfigError("WEB_PORT 无效或与旧系统 18083 冲突。")
    if result["CAPTCHA_ENABLED"] not in ("true", "false"):
        raise ConfigError("CAPTCHA_ENABLED 只能是 true 或 false。")
    for key in SECRET_KEYS:
        pattern = r"[A-Za-z0-9_-]{16,20}" if key in LOGIN_KEYS else r"[A-Za-z0-9_-]{20,64}"
        if not re.fullmatch(pattern, result[key]):
            limit = "16–20" if key in LOGIN_KEYS else "20–64"
            raise ConfigError("%s 必须是 %s 位字母、数字、下划线或短横线，不使用引号。" % (key, limit))
    return result


def fingerprint(config):
    payload = {key: config[key] for key in SECRET_KEYS}
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


def prepare(root):
    root = Path(root)
    runtime = root / ".runtime"
    runtime.mkdir(exist_ok=True, mode=0o700)
    runtime.chmod(0o700)
    path = root / ".env.local"
    state = runtime / "initialized.json"
    if not path.exists():
        if state.exists():
            raise ConfigError("检测到启动记录但 .env.local 丢失。请恢复原配置，不能为已有数据库重新生成密码。")
        config = dict(DEFAULTS)
        config.update({key: secrets.token_urlsafe(15 if key in LOGIN_KEYS else 24)
                       for key in SECRET_KEYS})
        private_write(path, "# 本机开发配置；不要提交或分享此文件。\n" +
                      "\n".join("%s=%s" % item for item in config.items()) + "\n")
    path.chmod(0o600)
    config = load_config(path)
    if state.exists():
        saved = json.loads(state.read_text(encoding="utf-8"))
        if saved["credential_fingerprint"] != fingerprint(config):
            raise ConfigError("本地密码配置与首次启动记录不同。请恢复原值；编辑配置不会修改已有数据库中的密码。")
    return config


def remember_initialized(root, config):
    """Lock credentials before first startup, including a partially failed startup."""
    private_write(Path(root) / ".runtime" / "initialized.json",
                  json.dumps({"credential_fingerprint": fingerprint(config)}, indent=2) + "\n")


def compose_invocation(root, config, arguments, ambient=None, development=False):
    root = Path(root).resolve()
    env = dict(os.environ if ambient is None else ambient)
    for key in list(env):
        if key.startswith("COMPOSE_") or key in KEYS:
            del env[key]
    env.update(config)
    command = [
        "docker", "compose", "-p", PROJECT,
        "--project-directory", str(root),
        "--env-file", str(root / ".env.local"),
        "-f", str(root / "compose.yaml"),
    ]
    if development:
        command += ["-f", str(root / "compose.dev.yaml")]
    command += list(arguments)
    return command, env
