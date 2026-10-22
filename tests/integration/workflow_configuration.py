"""Explicit integration exercise for the isolated em-plus-local environment.

Run only when temporary workflow publication is authorized. New projects and the
copied definition remain as audit samples; dedicated users/departments are removed
and the original published definition is restored even when an assertion fails.
Passwords and tokens stay in memory. Existing organization records are never edited.
"""

import copy
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

from ops.local_api import CLIENT_ID, LocalApi
from ops.local_config import ConfigError, private_write
from ops.migrations import sql
from ops.runtime import check_compose, docker_ready


FLOW_CODE = "pm_registration"
DEPT_LEADER = "#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}"
COUNT_TABLES = (
    "pm_project", "pm_application", "pm_event", "pm_task",
    "flow_instance", "flow_task", "flow_his_task", "flow_user", "flow_instance_biz_ext",
    "flow_definition", "flow_node", "flow_skip", "sys_user", "sys_dept",
)


def database_counts(root, config):
    """Read only: detect orphan instances/tasks as well as business side effects."""
    statement = " UNION ALL ".join(
        "SELECT '%s', COUNT(*) FROM %s" % (table, table) for table in COUNT_TABLES
    ) + ";"
    return {name: int(count) for name, count in
            (line.split("\t") for line in sql(root, config, statement).splitlines())}


def save_design(api, definition, check=True):
    # Warm Flow's designer endpoint requires this header to save node transitions.
    request = urllib.request.Request(
        api.base + "/warm-flow/save-json", method="POST",
        data=json.dumps(definition, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json", "clientid": CLIENT_ID,
                 "Authorization": "Bearer " + api.token, "onlyNodeSkip": "true"},
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        try:
            result = json.load(error)
        except ValueError:
            result = {"code": error.code, "msg": "HTTP request failed"}
    if check and result.get("code") != 200:
        raise ConfigError("设计器保存失败：" + str(result.get("msg", "未返回成功")))
    return result


def configured_design(original, seed, department_ids, direct_reviewer):
    """One acquisition-mode gateway, then explicit initiator-department branches."""
    definition = copy.deepcopy(original)

    def edge(source, target, condition=None):
        return {"skipType": "PASS", "skipCondition": condition, "skipName": None,
                "nowNodeCode": source, "nextNodeCode": target, "coordinate": ""}

    def node(template, code, name, kind, target=None, assignee=None):
        result = copy.deepcopy(template)
        # Copied definition IDs must not be reused for replacement nodes/edges.
        for field in ("id", "definitionId", "createTime", "updateTime"):
            result.pop(field, None)
        result.update(nodeCode=code, nodeName=name, nodeType=kind, permissionFlag=assignee)
        result["skipList"] = [edge(code, target)] if target else []
        return result

    start = node(seed["nodeList"][0], "cfg_start", "开始", 0, "cfg_submit")
    submit = node(seed["nodeList"][1], "cfg_submit", "提交申请", 1, "cfg_mode", "${initiator}")
    mode = node(seed["nodeList"][3], "cfg_mode", "按承接方式", 3)
    mode["skipList"] = [edge("cfg_mode", "cfg_direct", "eq@@acquisitionMode|DIRECT"),
                        edge("cfg_mode", "cfg_dept", "eq@@acquisitionMode|TENDER")]
    direct = node(seed["nodeList"][2], "cfg_direct", "直接委托审核", 1,
                  "cfg_end", str(direct_reviewer))
    department = node(seed["nodeList"][3], "cfg_dept", "按发起部门", 3)
    department["skipList"] = [
        edge("cfg_dept", "cfg_" + key, "eq@@initiatorDeptId|" + str(value))
        for key, value in department_ids.items()
    ]
    reviews = [node(seed["nodeList"][2], "cfg_" + key, label, 1, "cfg_end", DEPT_LEADER)
               for key, label in (("a", "甲部门负责人审核"), ("b", "乙部门负责人审核"),
                                  ("missing", "未配置负责人审核"))]
    end = node(seed["nodeList"][3], "cfg_end", "结束", 2)
    definition["nodeList"] = [start, submit, mode, direct, department, *reviews, end]
    definition["ext"] = '{"autoPass":false}'
    return definition


class WorkflowConfigurationExercise:
    def __init__(self, root, config):
        self.root, self.config = Path(root), config
        if config["WEB_PORT"] != "18880":
            raise ConfigError("本验证仅允许新系统 127.0.0.1:18880。")
        self.prefix = "[流程设置验证]" + datetime.now().strftime("%m%d-%H%M%S")
        self.suffix = datetime.now().strftime("%m%d%H%M%S") + secrets.token_hex(2)
        self.checks, self.projects, self.user_ids, self.dept_ids = [], [], [], []
        self.original_id = self.definition_id = None
        self.publication_attempted = False
        self.cleanup_errors = []
        self.preview_results = []

    def ok(self, label, condition):
        if not condition:
            raise ConfigError("验证未通过：" + label)
        self.checks.append(label)
        print("通过：" + label, flush=True)

    def user(self, account):
        query = urllib.parse.urlencode({"userName": account, "pageNum": 1, "pageSize": 100})
        rows = self.admin.request("/system/user/list?" + query)["data"]["rows"]
        found = [row for row in rows if row["userName"] == account]
        if len(found) != 1:
            raise ConfigError("无法唯一定位验证账号：" + account)
        return found[0]

    def provision(self):
        self.admin = LocalApi(self.root, self.config).login("admin", "ADMIN_PASSWORD")
        self.reviewer = LocalApi(self.root, self.config).login("test1", "TEST1_PASSWORD")
        self.admin_user = self.admin.request("/system/user/getInfo")["data"]["user"]
        self.reviewer_user = self.reviewer.request("/system/user/getInfo")["data"]["user"]
        template_user = self.user("test")
        template = self.admin.request("/system/user/" + str(template_user["userId"]))["data"]
        roles = template["roleIds"]
        if not roles:
            raise ConfigError("业务员模板未配置角色，不能建立专用测试用户。")
        self.departments, self.clerks, self.clerk_ids = {}, {}, {}
        leaders = {"a": self.reviewer_user["userId"], "b": self.admin_user["userId"], "missing": None}
        for number, key in enumerate(leaders):
            name = "流程验" + self.suffix + key
            self.admin.request("/system/dept", "POST", {
                "parentId": template_user["deptId"], "deptName": name, "orderNum": number,
                "leader": leaders[key], "status": "0",
            })
            departments = self.admin.request("/system/dept/list")["data"]
            matches = [d for d in departments if d["deptName"] == name]
            if len(matches) != 1:
                raise ConfigError("新增测试部门后无法唯一定位。")
            department_id = str(matches[0]["deptId"])
            self.dept_ids.append(department_id)
            self.departments[key] = department_id
            account = "wf" + self.suffix + key
            password = secrets.token_urlsafe(15)
            self.admin.request("/system/user", "POST", {
                "userName": account, "nickName": "流程验证业务员" + key,
                "deptId": department_id, "password": password, "roleIds": roles,
                "postIds": [], "status": "0", "userType": "sys_user",
                "remark": "本机流程配置专用测试账号",
            })
            self.clerk_ids[key] = str(self.user(account)["userId"])
            self.user_ids.append(self.clerk_ids[key])
            login_config = dict(self.config, WORKFLOW_SAMPLE_PASSWORD=password)
            self.clerks[key] = LocalApi(self.root, login_config).login(account, "WORKFLOW_SAMPLE_PASSWORD")

    def definitions(self, unpublished=False):
        path = "/workflow/definition/" + ("unPublishList" if unpublished else "list")
        result, page = [], 1
        while True:
            query = urllib.parse.urlencode({"flowCode": FLOW_CODE, "pageNum": page, "pageSize": 100})
            data = self.admin.request(path + "?" + query)["data"]
            result.extend(row for row in data["rows"] if row["flowCode"] == FLOW_CODE)
            if page * 100 >= data["total"]:
                return result
            page += 1

    def publish(self):
        published = [row for row in self.definitions() if row["isPublish"] == 1]
        self.ok("恰有一个原备案发布版本", len(published) == 1)
        self.original_id = str(published[0]["id"])
        original = self.admin.request("/warm-flow/query-def/" + self.original_id)["data"]
        self.original_design = copy.deepcopy(original)
        # Persist only the workflow definition as a private recovery aid, without credentials.
        backup_dir = self.root / ".temp"
        backup_dir.mkdir(exist_ok=True)
        private_write(backup_dir / ("workflow-configuration-original-" + self.suffix + ".json"),
                      json.dumps(original, ensure_ascii=False, indent=2))
        before = {str(row["id"]) for row in self.definitions(unpublished=True)}
        self.admin.request("/workflow/definition/copy/" + self.original_id, "POST")
        created = [row for row in self.definitions(unpublished=True) if str(row["id"]) not in before]
        self.ok("测试流程复制到独立草稿", len(created) == 1)
        self.definition_id = str(created[0]["id"])
        # onlyNodeSkip saves the graph only; name the audit sample through metadata API.
        self.admin.request("/workflow/definition", "PUT",
                           {"id": self.definition_id, "flowName": self.prefix})
        metadata = self.admin.request("/workflow/definition/" + self.definition_id)["data"]
        self.ok("测试流程元信息使用专用名称", metadata["flowName"] == self.prefix)
        draft = self.admin.request("/warm-flow/query-def/" + self.definition_id)["data"]
        seed = json.loads((self.root / "ops/workflows/pm_registration.json").read_text())
        self.design = configured_design(draft, seed, self.departments, self.reviewer_user["userId"])
        self.design.update(id=self.definition_id, flowName=self.prefix)
        save_design(self.admin, self.design)
        saved = self.admin.request("/warm-flow/query-def/" + self.definition_id)["data"]
        self.ok("保存承接方式、部门分支及部门负责人表达式",
                any(n["nodeCode"] == "cfg_mode" for n in saved["nodeList"])
                and sum(n.get("permissionFlag") == DEPT_LEADER for n in saved["nodeList"]) == 3)
        self.design_write_protection(saved)
        self.preview("a", "DIRECT", "直接委托审核", self.reviewer_user["userId"])
        self.ok("草稿预览不改变原发布版本", [str(row["id"]) for row in self.definitions()
                if row["isPublish"] == 1] == [self.original_id])
        self.publication_attempted = True
        self.admin.request("/workflow/definition/publish/" + self.definition_id, "PUT")
        self.ok("仅测试副本成为备案发布版本", [str(row["id"]) for row in self.definitions()
                if row["isPublish"] == 1] == [self.definition_id])

    def create(self, department, mode, label):
        actor = self.clerks[department]
        project = actor.request("/project/projects", "POST", {
            "name": self.prefix + label, "customerName": "本机流程验证客户", "location": "本地测试",
            "acquisitionMode": mode, "estimatedAmount": 120000,
        })["data"]
        self.projects.append(str(project["id"]))
        return str(project["id"])

    def detail(self, project_id, actor):
        return actor.request("/project/projects/" + project_id)["data"]

    def design_write_protection(self, draft):
        """Exercise the same designer save endpoint and header used by the new panel."""
        before = database_counts(self.root, self.config)
        draft_request = copy.deepcopy(draft)
        draft_request["id"] = self.definition_id
        denied = save_design(self.clerks["a"], draft_request, check=False)
        self.ok("业务员不能通过设计器入口保存现有草稿", denied.get("code") == 403)
        after = self.admin.request("/warm-flow/query-def/" + self.definition_id)["data"]
        self.ok("业务员保存被拒后草稿流程图不变", after == draft)
        self.ok("业务员保存被拒后无流程或业务记录副作用",
                database_counts(self.root, self.config) == before)

        before = database_counts(self.root, self.config)
        # query-def returns a designer graph with id=null; the save endpoint needs
        # an explicit definition ID to exercise the published-definition guard.
        published_request = copy.deepcopy(self.original_design)
        published_request["id"] = self.original_id
        denied = save_design(self.admin, published_request, check=False)
        self.ok("管理员不能直接保存已发布流程",
                denied.get("code") != 200 and "已发布" in denied.get("msg", ""))
        after = self.admin.request("/warm-flow/query-def/" + self.original_id)["data"]
        self.ok("管理员保存被拒后原发布流程图不变", after == self.original_design)
        self.ok("已发布流程保存被拒后无流程或业务记录副作用",
                database_counts(self.root, self.config) == before)

    def preview(self, department, mode, expected_node=None, expected_reviewer=None):
        request = {"definitionId": self.definition_id, "initiatorId": self.clerk_ids[department],
                   "variables": {"acquisitionMode": mode, "projectType": "", "estimatedAmount": 120000,
                                 "bidType": "", "bidAmount": 0, "projectAmount": 0}}
        before = database_counts(self.root, self.config)
        result = self.admin.request("/project/workflow/preview", "POST", request)["data"]
        self.ok("路线预览无业务、流程及组织记录副作用 " + department + mode,
                database_counts(self.root, self.config) == before)
        self.preview_results.append({"department": department, "mode": mode, "result": result})
        if expected_node:
            expected_code = "cfg_direct" if mode == "DIRECT" else "cfg_" + department
            steps = [step for step in result["steps"] if step["nodeCode"] == expected_code]
            self.ok("预览显示正确分支和实际审批人 " + department + mode,
                    result["complete"] and not result["warnings"] and len(steps) == 1
                    and steps[0]["nodeName"] == expected_node
                    and {str(person["userId"]) for person in steps[0]["assignees"]}
                    == {str(expected_reviewer)})
            matched = {(edge["fromNodeCode"], edge["toNodeCode"]) for edge in result["matchedEdges"]}
            required = {("cfg_mode", "cfg_direct")} if mode == "DIRECT" else {
                ("cfg_mode", "cfg_dept"), ("cfg_dept", expected_code)}
            self.ok("预览标记命中承接方式及部门连线 " + department + mode, required <= matched)
        else:
            self.ok("预览明确提示部门未配置负责人",
                    not result["complete"] and any("负责人" in warning for warning in result["warnings"]))
        return result

    def preview_permission(self):
        before = database_counts(self.root, self.config)
        result = self.clerks["a"].request("/project/workflow/preview", "POST", {
            "definitionId": self.definition_id, "initiatorId": self.clerk_ids["a"],
            "variables": {"acquisitionMode": "DIRECT"},
        }, check=False)
        self.ok("业务员无权使用管理员路线预览", result.get("code") == 403)
        self.ok("未授权预览无业务及流程副作用", database_counts(self.root, self.config) == before)

    def application(self, project_id, actor):
        applications = self.detail(project_id, actor)["applications"]
        self.ok("专用项目恰有一条备案申请 " + project_id, len(applications) == 1)
        return applications[0]

    def inbox_contains(self, actor, application_id):
        page = 1
        while True:
            data = actor.request("/project/approvals?status=SUBMITTED&pageSize=100&pageNum=" + str(page))["data"]
            if any(str(row["id"]) == str(application_id) for row in data["rows"]):
                return True
            if page * 100 >= data["total"]:
                return False
            page += 1

    def successful_route(self, department, mode, expected_node, reviewer, wrong_reviewer):
        clerk = self.clerks[department]
        reviewer_id = reviewer.request("/system/user/getInfo")["data"]["user"]["userId"]
        preview = self.preview(department, mode, expected_node, reviewer_id)
        project_id = self.create(department, mode, department + mode)
        clerk.request("/project/projects/" + project_id + "/registration", "POST", {})
        application = self.application(project_id, reviewer)
        self.ok("实际进入预期审批节点 " + department + mode,
                application["status"] == "SUBMITTED" and application["canHandle"]
                and [task["nodeName"] for task in application["currentTasks"]] == [expected_node])
        self.ok("实际审批节点与预览一致 " + department + mode,
                [task["nodeName"] for task in application["currentTasks"]]
                == [step["nodeName"] for step in preview["steps"] if step["nodeName"] == expected_node])
        self.ok("对应审批人收到待办 " + department + mode, self.inbox_contains(reviewer, application["id"]))
        self.ok("另一审批人未收到待办 " + department + mode,
                not self.inbox_contains(wrong_reviewer, application["id"]))
        denied = wrong_reviewer.request("/project/approvals/" + str(application["id"]) + "/decision",
                                        "POST", {"decision": "APPROVE", "comment": "本机越权验证"}, check=False)
        self.ok("非当前负责人不能审批 " + department + mode,
                denied.get("code") == 403 or (denied.get("code") != 200
                and "实际办理人" in denied.get("msg", "")))
        reviewer.request("/project/approvals/" + str(application["id"]) + "/decision", "POST",
                         {"decision": "APPROVE", "comment": "本机流程配置验证"})
        detail = self.detail(project_id, clerk)
        self.ok("正确审批人通过后备案完成 " + department + mode,
                detail["stage"] == "REGISTERED" and detail["applications"][0]["status"] == "APPROVED")

    def missing_leader(self):
        clerk = self.clerks["missing"]
        self.preview("missing", "TENDER")
        project_id = self.create("missing", "TENDER", "无负责人")
        before_detail = self.detail(project_id, clerk)
        before_counts = database_counts(self.root, self.config)
        result = clerk.request("/project/projects/" + project_id + "/registration", "POST", {}, check=False)
        self.ok("无部门负责人时明确中文提示",
                result.get("code") != 200 and "未设置负责人" in result.get("msg", ""))
        after = self.detail(project_id, clerk)
        self.ok("无负责人不能静默通过或遗留申请",
                after["stage"] == "DRAFT" and not after["applications"] and after == before_detail)
        self.ok("无负责人失败完整回滚业务及流程记录", database_counts(self.root, self.config) == before_counts)

    def cleanup(self):
        if self.publication_attempted:
            try:
                self.admin.request("/workflow/definition/publish/" + self.original_id, "PUT")
                self.ok("恢复原已发布备案流程", [str(row["id"]) for row in self.definitions()
                        if row["isPublish"] == 1] == [self.original_id])
            except Exception as error:
                self.cleanup_errors.append("恢复原发布流程：" + str(error))
        for kind, ids, path in (("测试用户", self.user_ids, "/system/user/"),
                                ("测试部门", list(reversed(self.dept_ids)), "/system/dept/")):
            for ident in ids:
                try:
                    self.admin.request(path + ident, "DELETE")
                except Exception as error:
                    self.cleanup_errors.append("清理" + kind + ident + "：" + str(error))
        if self.cleanup_errors:
            print("恢复/清理待处理：" + "；".join(self.cleanup_errors), flush=True)

    def run(self):
        # These read-only guards validate the Docker socket and Compose isolation before login.
        docker_ready()
        check_compose(self.root, self.config)
        try:
            self.provision()
            self.publish()
            self.preview_permission()
            self.successful_route("a", "DIRECT", "直接委托审核", self.reviewer, self.admin)
            self.successful_route("b", "DIRECT", "直接委托审核", self.reviewer, self.admin)
            self.successful_route("a", "TENDER", "甲部门负责人审核", self.reviewer, self.admin)
            self.successful_route("b", "TENDER", "乙部门负责人审核", self.admin, self.reviewer)
            self.missing_leader()
        finally:
            self.cleanup()
            result = {"checks": self.checks, "projectIds": self.projects, "prefix": self.prefix,
                      "testDefinition": self.definition_id, "restoredDefinition": self.original_id,
                      "testUserIds": self.user_ids, "testDepartmentIds": self.dept_ids,
                      "cleanupErrors": self.cleanup_errors, "previews": self.preview_results}
            directory = self.root / ".temp"
            directory.mkdir(exist_ok=True)
            private_write(directory / "workflow-configuration-integration-result.json",
                          json.dumps(result, ensure_ascii=False, indent=2))
        if self.cleanup_errors:
            raise ConfigError("验证恢复/清理失败，请检查私有结果文件。")
        print("真实流程设置验证完成：%s 项；保留 %s 条专用项目。" % (len(self.checks), len(self.projects)))
        return result


def verify(root, config):
    return WorkflowConfigurationExercise(root, config).run()


def main():
    from dev import ROOT, operation_lock
    from ops.local_config import load_config
    with operation_lock():
        verify(ROOT, load_config(ROOT / ".env.local"))


if __name__ == "__main__":
    try:
        main()
    except ConfigError as error:
        raise SystemExit(str(error)) from None
