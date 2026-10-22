# 开发说明

## 技术栈

- 后端：Java 21、Maven、Spring Boot、MyBatis-Plus、WarmFlow。
- 前端：Vue 3、TypeScript、Element Plus、Vite，使用 Node 24 和 pnpm 12。
- 数据库与缓存：MySQL 8.4、Valkey 8.1。

具体版本以 POM、Dockerfile、前端 package.json 和锁文件为准。启动和配置见 [README](../README.md)。

## 代码位置

- `backend/ruoyi-admin`：后端启动入口，加载各个模块。
- `backend/ruoyi-modules`：Plus 自带的账号、权限、工作流等功能。
- `backend/business-modules`：我们开发的业务，项目管理放在其中的 `project` 目录。
- `frontend/src/views/project`：项目管理页面。
- `frontend/src/api/project`：页面调用后端接口的代码和数据类型。

后续业务模块放在 `business-modules` 下，在对应 POM 中加入模块和依赖。

## 项目业务怎么写

页面调用后端接口，由应用服务检查业务规则，再访问数据库或发起审批。

| 目录 | 职责 |
|---|---|
| `web` | 接收请求，检查参数和接口权限 |
| `application` | 串起业务步骤，处理查询、修改和事务 |
| `domain` | 定义业务数据和规则，例如哪些阶段可以提交立项 |
| `infrastructure` | 数据库访问、申请快照和审批引擎调用 |
| `mapper` | MyBatis-Plus 查询和 SQL |

查询和修改数据的方法分开写，共用应用服务和数据库（CQRS-lite）。
新增业务使用 MyBatis-Plus，复杂查询放 Mapper，不直接使用 JdbcTemplate。

- 权限和阶段判断放在后端，不能只靠页面隐藏按钮。
- 一次业务操作中的数据修改放在同一个事务里，防止只保存一部分。
- 保留行锁和重复提交检查，避免同一申请被重复办理。
- 申请保留提交时的数据，审批和跟进记录不要覆盖。

## 接口与审批

项目接口以 `/project` 开头，前端通过 `/prod-api` 访问。响应格式为 `{code,msg,data}`，ID 使用字符串。
接口字段见 `ProjectController`、`ProjectRequests` 和前端 `api/project`；办理规则见 [业务说明](project-usage.md)，阶段判断在 `ProjectRules` 中。

`ProjectWorkflow` 负责调用 WarmFlow。三种审批为 `pm_registration`、`pm_bid`、`pm_initiation`，流程的 businessId 使用 `pm:<申请ID>`。
审批人和当前节点从引擎实时任务读取，支持条件分支、多级审批及多人办理。通过一个节点不代表整条流程结束，只有引擎结束后才更新项目阶段。

分支条件使用提交时的申请快照：`applicationType`、`acquisitionMode`、`projectType`、`estimatedAmount`、`bidType`、`bidAmount`、`projectAmount`。金额单位为元，未填金额按 0、未填可选文本按空字符串处理。客户端不能指定流程变量或跳过审批权限。

审批结果需要核对申请和流程状态；同一结果通知多次也只能处理一次。
`WorkflowHttpGuard` 防止通过通用流程接口绕过项目权限。修改审批功能时保留这些检查。

## 数据库和初始化

- 表结构变更新增到 `ops/migrations/Vxxx__*.sql`，不要修改已经执行的文件。
- `dev` 和 `up` 会执行新增迁移；单独执行用 `python3 dev.py migrate`。
- `ops/workflows` 存放初始审批定义。启动只补缺少的流程，保留已有配置，不覆盖人工草稿。
- `python3 dev.py provision` 单独初始化缺少的审批流程；`prepare` 只生成本地配置。
- `ops/bootstrap.py` 负责初始化空数据库，不要用它重置已有数据。

## 测试

在工程根目录检查脚本、配置和镜像构建：

```sh
python3 -m unittest discover -s tests -v
python3 dev.py check
python3 dev.py build
```

如需单独运行后端单元测试，在具备 Java 21 和 Maven 的容器内，进入 `backend` 目录执行：

```sh
mvn -pl business-modules/project,ruoyi-modules/ruoyi-workflow -am -Pdev -Dmaven.test.skip=false -DskipTests=false -DfailIfNoTests=false test
```

测试使用 H2，并读取仓库中的 `ops/migrations` 建表 SQL。在容器中运行时，也要提供这些文件并保持相对路径。

单独检查前端时，在前端开发容器的 `frontend` 目录执行：

```sh
pnpm install --frozen-lockfile
pnpm build
```

验证真实数据库和审批流程时，先启动本地服务，再在工程根目录执行：

```sh
python3 -m tests.integration.project_flow
```

此测试使用初始测试账号，会创建并保留带 `[验证]` 前缀的项目和审批记录。它不会随启动或单元测试自动运行；账号密码或角色改过后，需要先核对测试配置。
