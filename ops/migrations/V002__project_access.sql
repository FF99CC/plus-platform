-- Demo roles and a focused navigation surface; source modules are retained.
CREATE TABLE IF NOT EXISTS local_menu_snapshot (
 menu_id BIGINT PRIMARY KEY, menu_name VARCHAR(50), visible CHAR(1), order_num INT
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS local_demo_role_snapshot (
 user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, PRIMARY KEY(user_id,role_id)
) ENGINE=InnoDB;
START TRANSACTION;
INSERT IGNORE INTO local_menu_snapshot SELECT menu_id,menu_name,visible,order_num FROM sys_menu;
INSERT IGNORE INTO local_demo_role_snapshot SELECT user_id,role_id FROM sys_user_role
 WHERE user_id IN (1761100000000000003,1761100000000000004);
INSERT INTO sys_role(role_id,role_name,role_key,role_sort,data_scope,status,remark,create_time)
 VALUES (800000000000000001,'项目业务员','project_clerk',10,'5','0','本机演示：本人项目办理',NOW()),
 (800000000000000002,'审批负责人','project_reviewer',11,'1','0','本机演示：项目审核，实际岗位制度待确认',NOW());
DELETE FROM sys_user_role WHERE user_id IN (1761100000000000003,1761100000000000004);
INSERT INTO sys_user_role(user_id,role_id) VALUES
 (1761100000000000003,800000000000000001),(1761100000000000004,800000000000000002);
UPDATE sys_user SET nick_name='业务员（演示）' WHERE user_id=1761100000000000003;
UPDATE sys_user SET nick_name='审批负责人（演示）' WHERE user_id=1761100000000000004;
-- Hide navigation only. Existing permission definitions and code remain available to administrators.
UPDATE sys_menu SET visible='1' WHERE parent_id=0 AND path NOT IN ('system','workflow');
UPDATE sys_menu SET menu_name='管理设置',order_num=90 WHERE parent_id=0 AND path='system';
UPDATE sys_menu SET menu_name='审批设置',order_num=91 WHERE parent_id=0 AND path='workflow';
UPDATE sys_menu SET visible='1' WHERE parent_id=1761400000000011616 AND path NOT IN ('processDefinition','category');
UPDATE sys_menu SET visible='1' WHERE parent_id=1761400000000000001 AND path IN ('client','oss','notice');
UPDATE sys_menu SET menu_name='人员账号' WHERE perms='system:user:list';
UPDATE sys_menu SET menu_name='部门组织' WHERE perms='system:dept:list';
UPDATE sys_menu SET menu_name='角色权限' WHERE perms='system:role:list';
UPDATE sys_menu SET menu_name='选项维护' WHERE perms='system:dict:list';
UPDATE sys_menu SET menu_name='系统选项' WHERE perms='system:config:list';
UPDATE sys_menu SET menu_name='审批流程' WHERE perms='workflow:definition:list';
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_time) VALUES
 (800000000000000100,'项目工作',0,0,'project',NULL,'N','N','M','0','0','','example',NOW()),
 (800000000000000101,'项目办理',800000000000000100,1,'projects','project/projects/index','N','N','C','0','0','project:project:list','table',NOW()),
 (800000000000000102,'待我审批',800000000000000100,2,'approvals','project/approvals/index','N','N','C','0','0','project:approval:list','edit',NOW()),
 (800000000000000111,'查看项目',800000000000000101,1,'#',NULL,'N','N','F','1','0','project:project:query','#',NOW()),
 (800000000000000112,'新建备案',800000000000000101,2,'#',NULL,'N','N','F','1','0','project:project:add','#',NOW()),
 (800000000000000113,'办理项目',800000000000000101,3,'#',NULL,'N','N','F','1','0','project:project:edit','#',NOW()),
 (800000000000000114,'提交申请',800000000000000101,4,'#',NULL,'N','N','F','1','0','project:project:submit','#',NOW()),
 (800000000000000115,'审批申请',800000000000000102,1,'#',NULL,'N','N','F','1','0','project:approval:handle','#',NOW());
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 800000000000000001,menu_id FROM sys_menu
 WHERE menu_id IN (800000000000000100,800000000000000101,800000000000000111,800000000000000112,800000000000000113,800000000000000114);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 800000000000000002,menu_id FROM sys_menu
 WHERE menu_id IN (800000000000000100,800000000000000101,800000000000000102,800000000000000111,800000000000000115);
INSERT INTO flow_category(category_id,parent_id,ancestors,category_name,order_num,del_flag,create_time) VALUES (800000000000000200,0,'0','项目审批',0,'0',NOW());
COMMIT;
