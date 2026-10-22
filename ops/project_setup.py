"""Publish missing demo approval definitions through the application's normal API."""
import json
from pathlib import Path
from .local_api import LocalApi
from .local_config import ConfigError
from .migrations import sql


def provision(root,config):
    # A later password change in the UI must not prevent an already configured app from starting.
    existing=set(sql(root,config,"SELECT flow_code FROM flow_definition WHERE is_publish=1 AND del_flag='0';").splitlines())
    required={'pm_registration','pm_bid','pm_initiation'}
    if required.issubset(existing):
        print('项目审批流程已配置，保留现有定义与账号密码。')
        return
    api=LocalApi(root,config).login('admin','ADMIN_PASSWORD')
    for path in sorted((Path(root)/'ops/workflows').glob('pm_*.json')):
        definition=json.loads(path.read_text(encoding='utf-8'))
        code=definition['flowCode']
        published=api.request('/workflow/definition/list?flowCode='+code)['data']['rows']
        if any(row['flowCode']==code for row in published):
            print('保留现有审批流程：'+definition['flowName']); continue
        drafts=api.request('/workflow/definition/unPublishList?flowCode='+code)['data']['rows']
        drafts=[row for row in drafts if row['flowCode']==code]
        if drafts:
            raise ConfigError('发现尚未发布的现有流程 '+code+'，请在审批设置中核对并发布；不会覆盖人工修改。')
        api.import_flow(definition)
        drafts=api.request('/workflow/definition/unPublishList?flowCode='+code)['data']['rows']
        drafts=[row for row in drafts if row['flowCode']==code]
        if len(drafts)!=1: raise ConfigError('导入后流程数量不唯一：'+code)
        api.request('/workflow/definition/publish/'+str(drafts[0]['id']),'PUT')
        print('已发布演示审批流程：'+definition['flowName'])
