# 演示服务器部署

在 SSH 登录用户的家目录克隆仓库；在服务器本机运行 Docker 命令，不使用远程 Docker endpoint。
先确认目标目录和 `em-plus-local` 数据卷不存在。已有部署应保留 `.env.local`、`.runtime/` 和全部数据卷，不能重新生成配置或执行 `down -v`。

首次部署先运行 `python3 dev.py prepare`，在服务器私有的 `.env.local` 中设置端口、验证码和初始登录密码，再运行 `python3 dev.py up`。
基础设施凭据保持自动生成的独立随机值；应用登录密码允许 8–20 位。
需要指定额外演示账号的初始密码时，添加可选的 `DEMO_PASSWORD`，再执行 `python3 dev.py demo`。
指定密码仅用于首次创建；重复初始化保留人工修改，不会重置现有账号。
私有配置和运行记录不提交 Git，不复制其他环境的初始化记录。

## 接入现有容器 Caddy

先检查现有 Caddy 的挂载、网络、端口和域名解析。具体域名、服务器路径、代理网络名称及覆盖配置只在服务器克隆后设置，不写入公开仓库。
在服务器 `.runtime/caddy.compose.yaml` 中保存本环境的覆盖配置，仅把 Web 接入代理网络，数据服务保留在项目私有网络。

启动或更新流程：

```sh
git pull --ff-only
python3 dev.py up
docker compose -p em-plus-local --env-file .env.local -f compose.yaml -f .runtime/caddy.compose.yaml up -d --no-deps --wait web
python3 dev.py demo
```

每次 `dev.py up` 后重新应用本环境的 Web 覆盖配置，以保留代理网络。
Web 原有的 `127.0.0.1` 端口仅供本机初始化和检查。

修改前备份服务器现有 Caddyfile，追加本环境的独立站点，反向代理到 Web 容器，保留所有其他站点。
以下仅为占位示例，实际域名与 Web 网络别名在服务器上替换：

```caddyfile
example.com {
    encode zstd gzip
    reverse_proxy app-web:80
}
```

在现有 Caddy 容器中执行 `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile`，通过后再 `caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile`。
使用容器挂载的 Caddyfile 修改时，应原地写入以保留 bind mount 指向的 inode；校验失败则恢复备份，不 reload。

## 验收与数据

检查容器健康、HTTPS 证书、页面和静态资源、`/prod-api/auth/code`，实际登录并检查菜单权限、审批路线预览和备案审批。
基础流程由 `test1` 审批；演示模板默认保留草稿，预览并发布所需模板后，新申请才使用该模板。金额单位为元。

MySQL、Valkey、初始化 SQL 和后端日志均使用命名卷；容器重建不会删除数据。
备份应同时覆盖 MySQL 数据、`.env.local`、`.runtime/`；数据库运行中使用一致性逻辑备份或数据库支持的快照。
此部署提供演示环境，尚未配置自动异地备份。
