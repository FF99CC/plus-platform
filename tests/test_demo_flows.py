"""Pure graph checks; these tests never contact the application or publish a flow."""

import copy
import json
import re
import unittest
from decimal import Decimal

from ops.demo_flows import build_demo_flows


DEPARTMENTS = ("demo_hq_market", "demo_hq_engineering", "demo_east_market", "demo_south_market")
USERS = ("demo_hq_market_lead", "demo_hq_market_01", "demo_hq_engineering_01",
         "demo_east_market_01", "demo_south_market_01", "demo_general_manager", "demo_hq_general_01")
ROLES = ("demo_finance", "demo_executive", "demo_bid_specialist")
USER_DEPARTMENTS = {"demo_hq_market_lead": "demo_hq_market", "demo_hq_market_01": "demo_hq_market",
                    "demo_hq_engineering_01": "demo_hq_engineering", "demo_east_market_01": "demo_east_market",
                    "demo_south_market_01": "demo_south_market"}


def context(offset=0):
    return {group: {key: str(offset + base + index) for index, key in enumerate(keys)}
            for group, keys, base in (("departments", DEPARTMENTS, 100), ("users", USERS, 200),
                                      ("roles", ROLES, 300))}


def route(definition, variables):
    """Independent evaluator for the small, documented condition subset in these fixtures."""
    nodes = {node["nodeCode"]: node for node in definition["nodeList"]}
    current = next(node for node in nodes.values() if int(node["nodeType"]) == 0)
    manual, seen = [], set()
    while True:
        code = current["nodeCode"]
        if code in seen:
            raise AssertionError("cycle: " + code)
        seen.add(code)
        if int(current["nodeType"]) == 2:
            return manual
        if int(current["nodeType"]) == 1 and current["permissionFlag"] != "${initiator}":
            manual.append(code)
        edges = current["skipList"]
        conditional, default = [], []
        for edge in edges:
            condition = edge.get("skipCondition")
            if not condition:
                default.append(edge)
                continue
            operator, field, expected = re.fullmatch(r"(eq|le|gt)@@([a-zA-Z]+)\|(.+)", condition).groups()
            actual = variables[field]
            matched = str(actual) == expected if operator == "eq" else (
                Decimal(str(actual)) <= Decimal(expected) if operator == "le" else Decimal(str(actual)) > Decimal(expected))
            if matched:
                conditional.append(edge)
        candidates = conditional or default
        if len(candidates) != 1:
            raise AssertionError("ambiguous or missing route: " + code)
        current = nodes[candidates[0]["nextNodeCode"]]


class DemoFlowTests(unittest.TestCase):
    def setUp(self):
        self.context = context()
        self.flows = {flow["key"]: flow for flow in build_demo_flows(self.context)}

    def test_five_independent_drafts_have_complete_editable_graphs(self):
        self.assertEqual(set(self.flows), {"registration_mode", "registration_amount", "registration_department",
                                          "registration_initiator", "initiation_amount"})
        for key, item in self.flows.items():
            with self.subTest(key=key):
                definition = item["definition"]
                self.assertTrue(item["title"].startswith("[演示模板]"))
                self.assertEqual(definition["flowCode"], "pm_initiation" if key == "initiation_amount" else "pm_registration")
                self.assertEqual(definition["category"], "800000000000000200")
                self.assertEqual(definition["flowName"], item["title"])
                self.assertEqual(json.loads(definition["ext"]), {"autoPass": False})
                self.assertEqual(definition["isPublish"], 0)
                self.assertEqual(definition["activityStatus"], 1)
                self.assertNotIn("id", definition)
                nodes = definition["nodeList"]
                codes = {node["nodeCode"] for node in nodes}
                self.assertEqual(len(codes), len(nodes))
                self.assertEqual(len({node["coordinate"] for node in nodes}), len(nodes))
                self.assertEqual(sum(int(n["nodeType"]) == 0 for n in nodes), 1)
                self.assertEqual(sum(int(n["nodeType"]) == 2 for n in nodes), 1)
                self.assertEqual(sum(n.get("permissionFlag") == "${initiator}" for n in nodes), 1)
                for node in nodes:
                    self.assertNotIn("id", node)
                    self.assertRegex(node["coordinate"], r"^\d+,\d+\|\d+,\d+$")
                    for edge in node["skipList"]:
                        self.assertNotIn("id", edge)
                        self.assertEqual(edge["nowNodeCode"], node["nodeCode"])
                        self.assertIn(edge["nextNodeCode"], codes)
                        self.assertTrue(edge["skipName"])
                        self.assertRegex(edge["coordinate"], r"^\d+,\d+(;\d+,\d+)+$")
                        if edge.get("skipCondition"):
                            self.assertEqual(int(node["nodeType"]), 3)
                            self.assertRegex(edge["skipCondition"], r"^(eq|le|gt)@@(acquisitionMode|estimatedAmount|projectAmount|initiatorDeptId|initiator)\|[^|@]+$")
                    if int(node["nodeType"]) == 1 and node["permissionFlag"] != "${initiator}":
                        self.assertRegex(node["permissionFlag"], r"^(\d+|role:\d+|#\{@spelRuleComponent\.selectDeptLeaderById\(#initiatorDeptId\)\})$")

    def test_preview_samples_follow_the_documented_routes(self):
        for key, item in self.flows.items():
            covered = set()
            for preview in item["previews"]:
                with self.subTest(flow=key, sample=preview["name"]):
                    self.assertIn(preview["initiator"], self.context["users"])
                    variables = dict(preview["variables"], initiator=self.context["users"][preview["initiator"]])
                    department_key = USER_DEPARTMENTS.get(preview["initiator"])
                    variables["initiatorDeptId"] = self.context["departments"][department_key] if department_key else "999"
                    actual = route(item["definition"], variables)
                    self.assertEqual(actual, preview["expectedNodeCodes"])
                    covered.update(actual)
                    for username in preview.get("expectedUsers", []):
                        self.assertIn(username, self.context["users"])
            manual = {n["nodeCode"] for n in item["definition"]["nodeList"]
                      if int(n["nodeType"]) == 1 and n.get("permissionFlag") != "${initiator}"}
            self.assertEqual(covered, manual)

    def test_amount_routes_include_exact_boundary_and_one_cent_above_in_yuan(self):
        for key, field, threshold in (("registration_amount", "estimatedAmount", "1000000"),
                                      ("initiation_amount", "projectAmount", "5000000")):
            item = self.flows[key]
            previews = {Decimal(str(p["variables"][field])): p for p in item["previews"]}
            self.assertIn(Decimal("1") if key == "initiation_amount" else Decimal("0"), previews)
            if key == "initiation_amount":
                self.assertTrue(all(value > 0 for value in previews))
            low = previews[Decimal(threshold)]
            high = previews[Decimal(threshold) + Decimal("0.01")]
            self.assertEqual(len(low["expectedNodeCodes"]), 1)
            self.assertEqual(len(high["expectedNodeCodes"]), 3)
            conditions = [e["skipCondition"] for n in item["definition"]["nodeList"] for e in n["skipList"] if e.get("skipCondition")]
            self.assertIn("le@@" + field + "|" + threshold, conditions)
            self.assertIn("gt@@" + field + "|" + threshold, conditions)

    def test_department_and_specific_initiator_use_context_ids_with_one_default(self):
        for key in ("registration_department", "registration_initiator"):
            gateway = next(n for n in self.flows[key]["definition"]["nodeList"] if int(n["nodeType"]) == 3)
            self.assertEqual(sum(not e.get("skipCondition") for e in gateway["skipList"]), 1)
            self.assertFalse(gateway["skipList"][-1].get("skipCondition"))
            conditions = {e.get("skipCondition") for e in gateway["skipList"]}
            if key == "registration_department":
                self.assertTrue({"eq@@initiatorDeptId|" + ident for ident in self.context["departments"].values()} <= conditions)
            else:
                self.assertIn("eq@@initiator|" + self.context["users"]["demo_hq_market_lead"], conditions)
                lead = next(p for p in self.flows[key]["previews"] if p["initiator"] == "demo_hq_market_lead")
                self.assertEqual(lead["expectedUsers"], ["demo_general_manager"])

    def test_repeated_builds_do_not_mutate_input_or_share_graphs(self):
        original = copy.deepcopy(self.context)
        first = build_demo_flows(self.context)
        first[0]["definition"]["nodeList"][0]["nodeName"] = "changed"
        second = build_demo_flows(self.context)
        self.assertEqual(self.context, original)
        self.assertEqual(second[0]["definition"]["nodeList"][0]["nodeName"], "开始")
        shifted = build_demo_flows(context(1000))
        self.assertNotEqual(json.dumps(second), json.dumps(shifted))

    def test_fixed_people_and_role_candidates_are_resolved_from_context(self):
        candidates = {node.get("permissionFlag") for item in self.flows.values()
                      for node in item["definition"]["nodeList"] if node.get("permissionFlag")}
        expected_roles = {"role:" + ident for ident in self.context["roles"].values()}
        self.assertTrue(expected_roles <= candidates)
        allowed = expected_roles | set(self.context["users"].values()) | {
            "${initiator}", "#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}"}
        self.assertTrue(candidates <= allowed)
        self.assertIn(self.context["users"]["demo_general_manager"], candidates)
        for item in self.flows.values():
            self.assertIsNone(item["definition"]["listenerPath"])
            for node in item["definition"]["nodeList"]:
                self.assertIsNone(node["listenerPath"])

    def test_missing_or_invalid_required_ids_fail_before_returning_partial_drafts(self):
        for group, key in (("departments", "demo_hq_market"), ("users", "demo_hq_general_01"),
                           ("roles", "demo_finance")):
            malformed = copy.deepcopy(self.context)
            del malformed[group][key]
            with self.subTest(group=group, key=key), self.assertRaisesRegex(ValueError, key):
                build_demo_flows(malformed)
        malformed = copy.deepcopy(self.context)
        malformed["users"]["demo_general_manager"] = "1|spoof"
        with self.assertRaisesRegex(ValueError, "demo_general_manager"):
            build_demo_flows(malformed)


if __name__ == "__main__":
    unittest.main()
