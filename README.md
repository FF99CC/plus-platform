# 工作台

基于 RuoYi-Vue-Plus，提供项目备案、投标、施工立项和审批。
[业务说明](docs/project-usage.md)介绍办理流程，[开发说明](docs/development.md)介绍代码和测试。

## 启动

需要 Python 3.9+、Docker 和 Docker Compose v2。先启动 Docker，再在工程目录执行：

```sh
python3 dev.py dev
python3 dev.py credentials  # 查看初始账号密码
```

打开 http://127.0.0.1:18880 。首次启动会下载依赖、初始化空数据库并配置审批流程。
本机不用另外安装 Java、Node 或数据库。

保存前端代码后，页面自动更新；保存后端代码后，自动编译并重启。
编译失败时继续运行上次版本，修正后会再次编译。后端重启期间接口会短暂不可用。

## 常用命令

| 命令 | 用途 |
|---|---|
| `python3 dev.py dev` | 开发模式，保存代码后自动更新 |
| `python3 dev.py up` | 构建并启动打包后的应用 |
| `python3 dev.py build` | 检查代码并构建镜像，不启动服务 |
| `python3 dev.py status` | 查看服务状态 |
| `python3 dev.py logs backend -f` | 持续查看后端日志；前端用 `web` |
| `python3 dev.py stop` | 停止服务，保留数据 |
| `python3 dev.py demo` | 服务启动后，添加演示公司、人员和审批草稿 |

`dev` 和 `up` 会互相切换，共用本地账号和数据。关闭终端不会停止服务。
修改依赖、配置或启动脚本后，重新运行当前使用的命令。
两种方式都是本地运行；正式部署还需配置 HTTPS、独立数据和备份。

演示数据的账号和流程用法见[业务说明](docs/project-usage.md#演示数据)。初始化可重复运行，保留页面上的修改；不会自动发布演示草稿。

## 配置与数据

配置文件 `.env.local` 会自动生成，格式见 [.env.example](.env.example)。
`WEB_PORT` 设置访问端口，`CAPTCHA_ENABLED` 控制登录验证码。

- 保留 `.env.local` 和 `.runtime/initialized.json`，启动现有数据库时会核对这两份配置。
- `.env.local` 包含密码，不提交 Git。不要直接改里面的密码；修改文件不会同步修改已有账号和数据库密码。
- `credentials` 只显示初始密码；在页面改过密码后，以新密码为准。
- 重复启动不会重置数据或覆盖已有审批流程。启动失败时先查看日志。
- 普通停止用 `stop`；不要用 `docker compose down -v`，它会删除数据卷。

本地服务使用独立的 `em-plus-local` 环境，不连接旧系统。缓存使用 Valkey，日志命令中的服务名仍为 `redis`。

## 目录

| 目录 | 内容 |
|---|---|
| `backend/` | 后端代码 |
| `frontend/` | 前端页面和接口调用 |
| `ops/` | 启动、部署、数据库迁移和审批定义 |
| `tests/` | 脚本测试和业务流程测试 |
| `docs/` | 业务与开发说明 |
| `.temp/` | 草稿、临时脚本和验证结果，不提交 Git、不进入镜像 |

`.temp/` 可按需创建和清空；`.runtime/` 存放运行记录，不要整体删除。

上游版本见 [sources.lock.json](sources.lock.json)。保留[后端许可证](backend/LICENSE)、[前端许可证](frontend/LICENSE)和[第三方声明](frontend/public/THIRD_PARTY_NOTICES.txt)。
