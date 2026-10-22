"""Optional fictional demo data, created through normal APIs; never run during startup."""
import json
import secrets
from pathlib import Path
from urllib.parse import urlencode

from .local_api import LocalApi
from .local_config import ConfigError, private_write


STAFF_MARKER = '演示数据 v1；虚构人员，可在页面调整'
DEPARTMENT_MARKER = 'demo:v1:'
MENU_PREFIX = '800000000000000'
CLERK_MENUS = [MENU_PREFIX + value for value in ('100', '101', '111', '112', '113', '114')]
APPROVER_MENUS = CLERK_MENUS + [MENU_PREFIX + value for value in ('102', '115')]
ROLES = {
    'demo_clerk': '演示·项目经办人',
    'demo_department_lead': '演示·部门负责人',
    'demo_finance': '演示·集团财务复核',
    'demo_executive': '演示·集团总经理',
    'demo_bid_specialist': '演示·集团投标专员',
}


def organization():
    """Three companies, five departments each, and 49 fictional people."""
    departments = [{'key': 'demo_group', 'name': '[演示]启明建设集团',
                    'parent': None, 'leader': 'demo_general_manager'}]
    users = [{'userName': 'demo_general_manager', 'nickName': '陈启明（集团总经理）',
              'department': 'demo_group', 'roles': ['demo_executive']}]
    companies = [('hq', '总部公司', '周远'), ('east', '华东公司', '林川'), ('south', '华南公司', '吴帆')]
    sections = [('market', '市场部'), ('engineering', '工程部'), ('bid', '投标部'),
                ('finance', '财务部'), ('general', '综合部')]
    # These names are fiction; accounts and organization keys are stable across installations.
    surnames = ('沈', '陆', '宋', '唐', '许', '江', '苏', '顾', '叶', '方', '温', '徐', '程', '丁', '罗')
    for company_index, (company, label, director) in enumerate(companies):
        company_key = 'demo_' + company
        director_account = company_key + '_director'
        departments.append({'key': company_key, 'name': label, 'parent': 'demo_group',
                            'leader': director_account})
        users.append({'userName': director_account, 'nickName': director + '（公司负责人）',
                      'department': company_key, 'roles': ['demo_department_lead']})
        for section_index, (section, section_label) in enumerate(sections):
            key = company_key + '_' + section
            departments.append({'key': key, 'name': label.removesuffix('公司') + '·' + section_label, 'parent': company_key,
                                'leader': key + '_lead'})
            surname = surnames[company_index * len(sections) + section_index]
            leader_role = 'demo_department_lead'
            if company == 'hq' and section in ('finance', 'bid'):
                leader_role = 'demo_finance' if section == 'finance' else 'demo_bid_specialist'
            for suffix, name, role in (
                ('lead', surname + '知远（' + section_label + '负责人）', leader_role),
                ('01', surname + '晓宁', 'demo_clerk'),
                ('02', surname + '文安', 'demo_clerk'),
            ):
                users.append({'userName': key + '_' + suffix, 'nickName': name,
                              'department': key, 'roles': [role]})
    return departments, users


class DemoSetup:
    def __init__(self, root, config, api=None):
        self.root, self.config = Path(root), config
        runtime = self.root / '.runtime'
        runtime.mkdir(exist_ok=True, mode=0o700)
        runtime.chmod(0o700)
        self.path = runtime / 'demo-data.json'
        if self.path.exists():
            self.state = json.loads(self.path.read_text(encoding='utf-8'))
            if self.state.get('version') != 1:
                raise ConfigError('演示数据记录版本不匹配，请保留 .runtime/demo-data.json。')
        else:
            self.state = {'version': 1, 'password': secrets.token_urlsafe(15),
                          'departments': {}, 'users': {}, 'roles': {}, 'flows': {}, 'leaders': []}
        self.save()  # Persist the initial password before the first account can be created.
        self.api = api or LocalApi(self.root, config).login('admin', 'ADMIN_PASSWORD')

    def save(self):
        private_write(self.path, json.dumps(self.state, ensure_ascii=False, indent=2) + '\n')

    def remember(self, kind, key, identifier):
        self.state[kind][key] = str(identifier)
        self.save()

    def existing(self, kind, key, rows, id_field, matches):
        identifier = self.state[kind].get(key)
        if identifier:
            found = [row for row in rows if str(row[id_field]) == identifier]
            if len(found) != 1:
                raise ConfigError('演示记录已被删除或不可访问，请核对后继续：' + key)
            return found[0]  # Preserve later manual name, permission and password changes.
        found = [row for row in rows if matches(row)]
        if len(found) > 1:
            raise ConfigError('演示记录名称不唯一：' + key)
        if not found:
            return None
        row = found[0]
        owned = (row.get('remark') == STAFF_MARKER if kind in ('users', 'roles')
                 else row.get('deptCategory') == DEPARTMENT_MARKER + key if kind == 'departments'
                 else row.get('flowName', '').startswith('[演示模板]'))
        if not owned:
            raise ConfigError('同名记录不是本演示创建，未覆盖：' + key)
        if kind == 'users':
            # Without the manifest we cannot establish that its saved password belongs to this account.
            raise ConfigError('演示账号已存在但缺少本地记录，请恢复 .runtime/demo-data.json；未重置密码：' + key)
        self.remember(kind, key, row[id_field])
        return row

    def pages(self, path, **filters):
        rows, page = [], 1
        while True:
            result = self.api.request(path + '?' + urlencode(dict(filters, pageNum=page, pageSize=100)))['data']
            batch = result['rows']
            rows.extend(batch)
            if not batch or len(rows) >= int(result['total']):
                return rows
            page += 1

    def roles(self):
        rows = self.pages('/system/role/list')
        for index, (key, name) in enumerate(ROLES.items()):
            if self.existing('roles', key, rows, 'roleId', lambda row: row['roleKey'] == key):
                continue
            self.api.request('/system/role', 'POST', {
                'roleName': name, 'roleKey': key, 'roleSort': 100 + index,
                'dataScope': '5' if key == 'demo_clerk' else '1',
                'menuCheckStrictly': True, 'deptCheckStrictly': True,
                'status': '0', 'remark': STAFF_MARKER, 'deptIds': [],
                'menuIds': CLERK_MENUS if key == 'demo_clerk' else APPROVER_MENUS,
            })
            rows = self.pages('/system/role/list')
            if not self.existing('roles', key, rows, 'roleId', lambda row: row['roleKey'] == key):
                raise ConfigError('创建演示角色后未找到：' + key)

    def departments(self, definitions):
        rows = self.api.request('/system/dept/list')['data']
        roots = [row for row in rows if str(row.get('parentId')) == '0']
        if len(roots) != 1:
            raise ConfigError('需要一个明确的现有组织根节点，未创建或修改部门。')
        for index, department in enumerate(definitions):
            key = department['key']
            parent_id = (self.state['departments'][department['parent']] if department['parent']
                         else str(roots[0]['deptId']))
            def matches(row):
                return (row.get('deptCategory') == DEPARTMENT_MARKER + key or
                        (str(row.get('parentId')) == parent_id and row['deptName'] == department['name']))
            if self.existing('departments', key, rows, 'deptId', matches):
                continue
            self.api.request('/system/dept', 'POST', {
                'parentId': parent_id, 'deptName': department['name'], 'orderNum': 100 + index,
                'deptCategory': DEPARTMENT_MARKER + key, 'status': '0',
            })
            rows = self.api.request('/system/dept/list')['data']
            if not self.existing('departments', key, rows, 'deptId', matches):
                raise ConfigError('创建演示部门后未找到：' + key)

    def users(self, definitions):
        rows = self.pages('/system/user/list')
        for user in definitions:
            key = user['userName']
            if self.existing('users', key, rows, 'userId', lambda row: row['userName'] == key):
                continue
            # Record the intended operation first. A lost response can then be recovered without resetting passwords.
            self.state['pendingUser'] = key
            self.save()
            self.api.request('/system/user', 'POST', {
                'userName': key, 'nickName': user['nickName'],
                'deptId': self.state['departments'][user['department']],
                'password': self.state['password'], 'status': '0', 'userType': 'sys_user',
                'roleIds': [self.state['roles'][role] for role in user['roles']],
                'postIds': [], 'remark': STAFF_MARKER,
            })
            rows = self.pages('/system/user/list')
            self.recover_pending_user(rows)

    def recover_pending_user(self, rows):
        key = self.state.get('pendingUser')
        if not key:
            return
        matches = [row for row in rows if row['userName'] == key]
        if len(matches) == 1 and matches[0].get('remark') == STAFF_MARKER:
            self.remember('users', key, matches[0]['userId'])
            self.state.pop('pendingUser')
            self.save()
        elif matches:
            raise ConfigError('待恢复演示账号与记录冲突：' + key)

    def leaders(self, departments):
        for department in departments:
            key = department['key']
            if key in self.state['leaders']:
                continue
            identifier = self.state['departments'][key]
            row = self.api.request('/system/dept/' + identifier)['data']
            # Fill only the initially empty leader; respect a person set manually during an interrupted setup.
            if not row.get('leader'):
                body = {field: row.get(field) for field in (
                    'deptId', 'parentId', 'deptName', 'deptCategory', 'orderNum', 'status')}
                body['leader'] = self.state['users'][department['leader']]
                self.api.request('/system/dept', 'PUT', body)
            self.state['leaders'].append(key)
            self.save()

    def flows(self):
        from .demo_flows import build_demo_flows
        for template in build_demo_flows(self.state):
            definition = template['definition']
            key, code = template['key'], definition['flowCode']
            def all_versions():
                return (self.pages('/workflow/definition/list', flowCode=code) +
                        self.pages('/workflow/definition/unPublishList', flowCode=code))
            def matches(row):
                return row['flowCode'] == code and row['flowName'] == definition['flowName']
            if self.existing('flows', key, all_versions(), 'id', matches):
                continue
            self.api.import_flow(definition)
            if not self.existing('flows', key, all_versions(), 'id', matches):
                raise ConfigError('导入演示流程后未找到：' + key)

    def run(self):
        departments, users = organization()
        self.roles()
        self.departments(departments)
        self.recover_pending_user(self.pages('/system/user/list'))
        self.users(users)
        self.leaders(departments)
        self.flows()
        print('演示数据已就绪：1 个集团、3 家公司、15 个部门、49 个账号、5 个流程草稿。')
        print('保留现有已发布流程；在审批设置中选择演示模板，预览后再发布。')
        print('查看演示账号初始密码：python3 dev.py credentials --demo')


def show_credentials(root):
    path = Path(root) / '.runtime/demo-data.json'
    if not path.exists():
        raise ConfigError('尚未初始化演示数据，请先运行 python3 dev.py demo。')
    state = json.loads(path.read_text(encoding='utf-8'))
    print('以下是演示账号的初始密码；页面改密后以新密码为准：' + state['password'])
    for user in organization()[1]:
        if user['userName'] in state['users']:
            print('%-30s %s' % (user['userName'], user['nickName']))
