import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from ops.demo_setup import DemoSetup, organization, STAFF_MARKER
from ops.local_config import ConfigError


class DemoSetupTests(unittest.TestCase):
    def test_organization_is_complete_and_independent_of_legacy_roles(self):
        departments, users = organization()
        self.assertEqual(len(departments), 19)
        self.assertEqual(len(users), 49)
        keys = {item['key'] for item in departments}
        accounts = {item['userName'] for item in users}
        self.assertEqual(len(accounts), 49)
        for department in departments:
            self.assertIn(department['leader'], accounts)
            if department['parent']:
                self.assertIn(department['parent'], keys)
        for user in users:
            self.assertIn(user['department'], keys)
            self.assertTrue(all(key.startswith('demo_') for key in user['roles']))

    def test_reuse_preserves_manually_edited_name_and_password(self):
        with tempfile.TemporaryDirectory() as directory:
            setup = DemoSetup(Path(directory), {}, api=Mock())
            setup.state['users']['demo_hq_market_01'] = '123'
            rows = [{'userId': '123', 'userName': 'demo_hq_market_01',
                     'nickName': '手工修改', 'remark': STAFF_MARKER}]
            result = setup.existing('users', 'demo_hq_market_01', rows, 'userId',
                                    lambda row: row['userName'] == 'demo_hq_market_01')
            self.assertEqual(result, rows[0])
            setup.api.request.assert_not_called()

    def test_unowned_account_collision_is_not_adopted_or_reset(self):
        with tempfile.TemporaryDirectory() as directory:
            setup = DemoSetup(Path(directory), {}, api=Mock())
            with self.assertRaises(ConfigError):
                setup.existing('users', 'demo_hq_market_01',
                               [{'userId': '123', 'userName': 'demo_hq_market_01'}],
                               'userId', lambda row: row['userName'] == 'demo_hq_market_01')
            setup.api.request.assert_not_called()

    def test_deleted_managed_record_is_not_silently_recreated(self):
        with tempfile.TemporaryDirectory() as directory:
            setup = DemoSetup(Path(directory), {}, api=Mock())
            setup.state['users']['demo_hq_market_01'] = '123'
            with self.assertRaises(ConfigError):
                setup.existing('users', 'demo_hq_market_01', [], 'userId', lambda row: True)

    def test_state_keeps_password_before_requests_and_is_private(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            setup = DemoSetup(root, {}, api=Mock())
            saved = json.loads(setup.path.read_text())
            self.assertEqual(len(saved['password']), 20)
            self.assertEqual(setup.path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(DemoSetup(root, {}, api=Mock()).state['password'], saved['password'])
            setup.api.request.assert_not_called()

    def test_demo_group_uses_existing_root_as_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            root = {'deptId': '10', 'parentId': '0', 'deptName': '原有组织'}
            group = {'deptId': '11', 'parentId': '10', 'deptName': '[演示]启明建设集团',
                     'deptCategory': 'demo:v1:demo_group'}
            api.request.side_effect = [{'data': [root]}, {'code': 200}, {'data': [root, group]}]
            setup = DemoSetup(Path(directory), {}, api=api)
            setup.departments(organization()[0][:1])
            self.assertEqual(api.request.call_args_list[1].args[2]['parentId'], '10')
            self.assertEqual(setup.state['departments']['demo_group'], '11')

    def test_imported_template_is_remembered_and_never_published(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            api = Mock()
            setup = DemoSetup(Path(directory), {}, api=api)
            template = {'key': 'example', 'definition': {
                'flowCode': 'pm_registration', 'flowName': '[演示模板]示例'}}
            row = dict(template['definition'], id='99')
            setup.pages = Mock(side_effect=[[], [], [], [row]])
            with patch('ops.demo_flows.build_demo_flows', return_value=[template]):
                setup.flows()
            self.assertEqual(setup.state['flows']['example'], '99')
            api.import_flow.assert_called_once_with(template['definition'])
            api.request.assert_not_called()

    def test_pending_user_recovers_after_lost_response_without_resetting_password(self):
        with tempfile.TemporaryDirectory() as directory:
            setup = DemoSetup(Path(directory), {}, api=Mock())
            setup.state['pendingUser'] = 'demo_hq_market_01'
            setup.recover_pending_user([{'userId': '123', 'userName': 'demo_hq_market_01',
                                         'remark': STAFF_MARKER}])
            self.assertEqual(setup.state['users']['demo_hq_market_01'], '123')
            self.assertNotIn('pendingUser', setup.state)
            setup.api.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
