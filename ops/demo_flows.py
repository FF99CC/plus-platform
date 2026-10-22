"""Build editable Warm Flow demo drafts and preview cases without performing I/O.

All monetary variables use yuan, matching ProjectForm, ActionForm and the Chinese
workflow condition editor. These fixtures change no business-stage rules and are
never published by this module.
"""

import json
import re


DEPT_LEADER = "#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}"
REQUIRED_KEYS = {
    "departments": ("demo_hq_market", "demo_hq_engineering", "demo_east_market", "demo_south_market"),
    "users": ("demo_hq_market_lead", "demo_hq_market_01", "demo_hq_engineering_01",
              "demo_east_market_01", "demo_south_market_01", "demo_general_manager", "demo_hq_general_01"),
    "roles": ("demo_finance", "demo_executive", "demo_bid_specialist"),
}


def _ids(context):
    result = {}
    for group, keys in REQUIRED_KEYS.items():
        result[group] = {}
        for key in keys:
            value = context.get(group, {}).get(key)
            if not isinstance(value, str) or not re.fullmatch(r"[1-9]\d*", value):
                raise ValueError("演示流程缺少有效ID：" + group + "." + key)
            result[group][key] = value
    return result


class _Draft:
    def __init__(self, key, title, flow_code="pm_registration"):
        self.key, self.title = key, title
        self.nodes, self.positions = {}, {}
        self.definition = {
            "flowCode": flow_code, "flowName": title, "category": "800000000000000200",
            "modelValue": "CLASSICS", "version": "1", "formCustom": "N",
            "isPublish": 0, "activityStatus": 1,
            "formPath": "/project/projects", "listenerType": None, "listenerPath": None,
            "ext": json.dumps({"autoPass": False}, separators=(",", ":")), "nodeList": [],
        }

    def node(self, code, name, kind, x, y, assignee=None):
        buttons = "back,termination,file,copy" if assignee == "${initiator}" else "back,termination,copy,transfer,trust,file"
        extension = [{"code": "ButtonPermissionEnum", "value": buttons}] if kind == 1 else []
        node = {
            "nodeCode": code, "nodeName": name, "nodeType": str(kind), "permissionFlag": assignee,
            "nodeRatio": "0.000", "anyNodeSkip": None, "listenerType": None, "listenerPath": None,
            "formCustom": "N", "formPath": None, "ext": json.dumps(extension, ensure_ascii=False),
            "coordinate": f"{x},{y}|{x},{y}", "version": "1", "skipList": [],
        }
        self.nodes[code] = node
        self.positions[code] = (x, y)
        self.definition["nodeList"].append(node)

    def edge(self, source, target, label, condition=None):
        source_node, target_node = self.nodes[source], self.nodes[target]
        sx, sy = self.positions[source]
        tx, ty = self.positions[target]
        def radius(node):
            return {"0": 20, "1": 50, "2": 20, "3": 30}[node["nodeType"]]
        start, end = sx + radius(source_node), tx - radius(target_node)
        points = [(start, sy), (end, ty)]
        if sy != ty:
            middle = (start + end) // 2
            points = [(start, sy), (middle, sy), (middle, ty), (end, ty)]
        source_node["skipList"].append({
            "skipType": "PASS", "skipCondition": condition, "skipName": label,
            "nowNodeCode": source, "nextNodeCode": target,
            "coordinate": ";".join(f"{x},{y}" for x, y in points),
        })

    def beginning(self, prefix, gateway_name, y=300):
        self.node(prefix + "_start", "开始", 0, 100, y)
        self.node(prefix + "_submit", "提交申请", 1, 260, y, "${initiator}")
        self.node(prefix + "_gate", gateway_name, 3, 440, y)
        self.edge(prefix + "_start", prefix + "_submit", "填写申请")
        self.edge(prefix + "_submit", prefix + "_gate", "提交后判断")

    def result(self, previews):
        return {"key": self.key, "title": self.title, "definition": self.definition, "previews": previews}


def _preview(name, initiator, nodes, variables=None, expected_users=None):
    facts = {"acquisitionMode": "DIRECT", "projectType": "", "estimatedAmount": "0",
             "bidType": "", "bidAmount": "0", "projectAmount": "0"}
    facts.update(variables or {})
    result = {"name": name, "initiator": initiator, "variables": facts, "expectedNodeCodes": nodes}
    if expected_users is not None:
        result["expectedUsers"] = expected_users
    return result


def _mode_flow(ids):
    draft = _Draft("registration_mode", "[演示模板]备案①承接方式与部门负责人")
    draft.beginning("mode", "按承接方式")
    draft.node("mode_direct_head", "直接委托·部门负责人", 1, 900, 140, DEPT_LEADER)
    draft.node("mode_bid", "招投标·投标专员审核", 1, 680, 460, "role:" + ids["roles"]["demo_bid_specialist"])
    draft.node("mode_tender_head", "招投标·部门负责人", 1, 900, 460, DEPT_LEADER)
    draft.node("mode_end", "结束", 2, 1180, 300)
    draft.edge("mode_gate", "mode_direct_head", "直接委托", "eq@@acquisitionMode|DIRECT")
    draft.edge("mode_gate", "mode_bid", "招投标", "eq@@acquisitionMode|TENDER")
    draft.edge("mode_bid", "mode_tender_head", "专员审核通过")
    draft.edge("mode_direct_head", "mode_end", "负责人同意")
    draft.edge("mode_tender_head", "mode_end", "负责人同意")
    return draft.result([
        _preview("直接委托·总部市场经办人", "demo_hq_market_01", ["mode_direct_head"],
                 {"acquisitionMode": "DIRECT"}, ["demo_hq_market_lead"]),
        _preview("招投标·先专员后负责人", "demo_hq_market_01", ["mode_bid", "mode_tender_head"],
                 {"acquisitionMode": "TENDER"}),
    ])


def _amount_flow(ids, initiation=False):
    key = "initiation_amount" if initiation else "registration_amount"
    prefix = "init_amount" if initiation else "reg_amount"
    field = "projectAmount" if initiation else "estimatedAmount"
    threshold, over = ("5000000", "5000000.01") if initiation else ("1000000", "1000000.01")
    title = "[演示模板]立项⑤金额分级审批（500万元）" if initiation else "[演示模板]备案②预计金额分级审批（100万元）"
    draft = _Draft(key, title, "pm_initiation" if initiation else "pm_registration")
    draft.beginning(prefix, "按立项金额（元）" if initiation else "按预计金额（元）")
    draft.node(prefix + "_low_head", "限额内·部门负责人", 1, 700, 140, DEPT_LEADER)
    draft.node(prefix + "_high_head", "超限额·部门负责人", 1, 700, 460, DEPT_LEADER)
    draft.node(prefix + "_finance", "财务审核", 1, 930, 460, "role:" + ids["roles"]["demo_finance"])
    draft.node(prefix + "_manager", "总经理审核", 1, 1160, 460, ids["users"]["demo_general_manager"])
    draft.node(prefix + "_end", "结束", 2, 1400, 300)
    limit_name = "500万元" if initiation else "100万元"
    draft.edge(prefix + "_gate", prefix + "_low_head", "金额不超过" + limit_name, "le@@" + field + "|" + threshold)
    draft.edge(prefix + "_gate", prefix + "_high_head", "金额超过" + limit_name, "gt@@" + field + "|" + threshold)
    draft.edge(prefix + "_low_head", prefix + "_end", "限额内审批完成")
    draft.edge(prefix + "_high_head", prefix + "_finance", "负责人同意")
    draft.edge(prefix + "_finance", prefix + "_manager", "财务同意")
    draft.edge(prefix + "_manager", prefix + "_end", "总经理同意")
    low = [prefix + "_low_head"]
    high = [prefix + "_high_head", prefix + "_finance", prefix + "_manager"]
    return draft.result([
        _preview("1元·限额内路线" if initiation else "零金额·限额内路线", "demo_hq_market_01",
                 low[:], {field: "1" if initiation else "0"}, ["demo_hq_market_lead"]),
        _preview("恰好" + limit_name + "·仅负责人", "demo_hq_market_01", low[:], {field: threshold}, ["demo_hq_market_lead"]),
        _preview("超过" + limit_name + "一分钱·三级审批", "demo_hq_market_01", high, {field: over}),
    ])


def _department_flow(ids):
    draft = _Draft("registration_department", "[演示模板]备案③发起部门差异化审批")
    draft.beginning("dept", "按发起部门", y=380)
    branches = [
        ("hq_market", "总部市场", "demo_hq_market", "demo_hq_market_01", 80),
        ("east_market", "华东市场", "demo_east_market", "demo_east_market_01", 230),
        ("south_market", "华南市场", "demo_south_market", "demo_south_market_01", 380),
        ("engineering", "总部工程", "demo_hq_engineering", "demo_hq_engineering_01", 530),
        ("other", "其他部门", None, "demo_hq_general_01", 680),
    ]
    for branch, label, _, _, y in branches:
        draft.node("dept_" + branch + "_head", label + "负责人审核", 1, 760, y, DEPT_LEADER)
    draft.node("dept_finance", "市场线路·财务审核", 1, 1080, 230, "role:" + ids["roles"]["demo_finance"])
    draft.node("dept_executive", "工程线路·管理层审核", 1, 1080, 530, "role:" + ids["roles"]["demo_executive"])
    draft.node("dept_specialist", "其他线路·投标专员审核", 1, 1080, 680, "role:" + ids["roles"]["demo_bid_specialist"])
    draft.node("dept_end", "结束", 2, 1390, 380)
    previews = []
    for branch, label, department, initiator, _ in branches:
        head = "dept_" + branch + "_head"
        condition = "eq@@initiatorDeptId|" + ids["departments"][department] if department else ""
        draft.edge("dept_gate", head, label if department else "其他部门（默认路线）", condition)
        next_node = "dept_finance" if "market" in branch else (
            "dept_executive" if branch == "engineering" else "dept_specialist")
        draft.edge(head, next_node, "负责人同意")
        previews.append(_preview(label + "发起·对应部门线路", initiator, [head, next_node]))
    for code in ("dept_finance", "dept_executive", "dept_specialist"):
        draft.edge(code, "dept_end", "审核完成")
    return draft.result(previews)


def _initiator_flow(ids):
    draft = _Draft("registration_initiator", "[演示模板]备案④主管发起转总经理")
    draft.beginning("person", "按特定发起人")
    draft.node("person_manager", "主管发起·总经理审核", 1, 900, 140, ids["users"]["demo_general_manager"])
    draft.node("person_head", "其他人员·部门负责人", 1, 900, 460, DEPT_LEADER)
    draft.node("person_end", "结束", 2, 1180, 300)
    draft.edge("person_gate", "person_manager", "总部市场主管发起",
               "eq@@initiator|" + ids["users"]["demo_hq_market_lead"])
    draft.edge("person_gate", "person_head", "其他发起人（默认路线）", "")
    draft.edge("person_manager", "person_end", "总经理同意")
    draft.edge("person_head", "person_end", "负责人同意")
    return draft.result([
        _preview("总部市场主管发起·避免主管自批", "demo_hq_market_lead", ["person_manager"],
                 expected_users=["demo_general_manager"]),
        _preview("总部市场经办人发起·交主管", "demo_hq_market_01", ["person_head"],
                 expected_users=["demo_hq_market_lead"]),
    ])


def build_demo_flows(context):
    """Return five fresh import definitions with reproducible, read-only preview cases.

    ``expectedNodeCodes`` lists manual approval nodes in visit order, excluding
    the automatically completed submission node. Optional ``expectedUsers`` lists
    the complete expected candidate usernames only when no role membership lookup
    is needed. Caller supplies real server-side user/department/role IDs; never
    send initiator or initiatorDeptId as caller-controlled business variables.

    Warm Flow 1.8.9 importDef allocates a fresh definition ID, forces draft status
    and replaces version with getNewVersion(flowCode); the seed version "1" does
    not identify or overwrite an existing baseline definition.
    """
    ids = _ids(context)
    return [_mode_flow(ids), _amount_flow(ids), _department_flow(ids),
            _initiator_flow(ids), _amount_flow(ids, initiation=True)]
