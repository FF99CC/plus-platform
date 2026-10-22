import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from ops.local_config import ConfigError
from ops.project_setup import provision

class ProvisioningTests(unittest.TestCase):
    def test_already_configured_startup_does_not_require_initial_admin_password(self):
        with patch('ops.project_setup.sql',return_value='pm_registration\npm_bid\npm_initiation'), \
             patch('ops.project_setup.LocalApi') as factory:
            provision(Path('/tmp/isolated'),{})
            factory.assert_not_called()

    def test_existing_unpublished_edit_is_not_overwritten_or_auto_published(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); (root/'ops/workflows').mkdir(parents=True)
            (root/'ops/workflows/pm_bid.json').write_text(json.dumps({'flowCode':'pm_bid','flowName':'投标申请审批'}))
            api=Mock(); api.login.return_value=api
            api.request.side_effect=[{'data':{'rows':[]}}, {'data':{'rows':[{'flowCode':'pm_bid','id':'12'}]}}]
            with patch('ops.project_setup.sql',return_value=''),patch('ops.project_setup.LocalApi',return_value=api):
                with self.assertRaises(ConfigError): provision(root,{})
            api.import_flow.assert_not_called()
            self.assertEqual(api.request.call_count,2)
