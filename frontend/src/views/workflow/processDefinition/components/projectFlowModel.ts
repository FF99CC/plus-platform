import type { DesignDefinition } from '@/api/workflow/definition/designer';

export const conditionFields = [
  { key: 'acquisitionMode', label: '承接方式', kind: 'mode' },
  { key: 'initiatorDeptId', label: '发起部门', kind: 'dept' },
  { key: 'initiator', label: '发起人', kind: 'user' },
  { key: 'estimatedAmount', label: '预计金额（元）', kind: 'amount' },
  { key: 'bidAmount', label: '中标金额（元）', kind: 'amount' },
  { key: 'projectAmount', label: '立项金额（元）', kind: 'amount' },
  { key: 'projectType', label: '项目类型', kind: 'text' },
  { key: 'bidType', label: '投标类型', kind: 'text' }
] as const;
export const operators = [
  { value: 'eq', label: '等于' },
  { value: 'ne', label: '不等于' },
  { value: 'gt', label: '大于' },
  { value: 'ge', label: '大于等于' },
  { value: 'lt', label: '小于' },
  { value: 'le', label: '小于等于' },
  { value: 'like', label: '包含' },
  { value: 'notLike', label: '不包含' }
];
export interface Condition {
  field: string;
  operator: string;
  value: string;
}
export interface Assignee {
  type: string;
  value: string;
}
export const initiatorFlag = '${initiator}';
export const deptLeaderFlag = '#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}';
export const assigneeTypes = [
  { value: 'user', label: '指定人员', handlerType: '用户' },
  { value: 'role', label: '指定角色', handlerType: '角色' },
  { value: 'dept', label: '部门成员', handlerType: '部门' },
  { value: 'post', label: '指定岗位', handlerType: '岗位' },
  { value: 'leader', label: '发起部门负责人', handlerType: '' },
  { value: 'initiator', label: '发起人', handlerType: '' }
];
export function allowedOperators(field: string) {
  const kind = conditionFields.find(item => item.key === field)?.kind;
  return operators.filter(
    item =>
      ['eq', 'ne'].includes(item.value) ||
      (kind === 'amount'
        ? ['gt', 'ge', 'lt', 'le'].includes(item.value)
        : kind === 'text' && ['like', 'notLike'].includes(item.value))
  );
}
export function serializeCondition(condition: Condition): string {
  const field = conditionFields.find(item => item.key === condition.field);
  if (!field || !allowedOperators(condition.field).some(item => item.value === condition.operator))
    throw new Error('请选择条件和比较方式');
  if (!condition.value || /[|@]/.test(condition.value)) throw new Error('请填写有效的条件值');
  if (field.kind === 'amount' && !/^\d+(\.\d+)?$/.test(condition.value))
    throw new Error('金额必须是大于等于零的数字，单位为元');
  if (field.kind === 'mode' && !['DIRECT', 'TENDER'].includes(condition.value)) throw new Error('请选择承接方式');
  if (['user', 'dept'].includes(field.kind) && !/^\d+$/.test(condition.value)) throw new Error('请选择人员或部门');
  return `${condition.operator}@@${condition.field}|${condition.value}`;
}
export function parseCondition(value?: string): Condition | null {
  if (!value) return { field: '', operator: 'eq', value: '' };
  const match = /^([a-zA-Z]+)@@([a-zA-Z]+)\|([^|]+)$/.exec(value);
  if (!match) return null;
  const result = { operator: match[1], field: match[2], value: match[3] };
  try {
    serializeCondition(result);
    return result;
  } catch {
    return null;
  }
}
export function parseAssignees(value?: string): Assignee[] | null {
  if (!value) return [];
  const result: Assignee[] = [];
  for (const token of value.split('@@')) {
    if (token === initiatorFlag) result.push({ type: 'initiator', value: '' });
    else if (token === deptLeaderFlag) result.push({ type: 'leader', value: '' });
    else if (/^\d+$/.test(token)) result.push({ type: 'user', value: token });
    else {
      const match = /^(role|dept|post):(\d+)$/.exec(token);
      if (!match) return null;
      result.push({ type: match[1], value: token });
    }
  }
  return result;
}
export function serializeAssignees(assignees: Assignee[]): string {
  if (!assignees.length) throw new Error('请至少配置一个审批人');
  return assignees
    .map(item => {
      if (item.type === 'initiator') return initiatorFlag;
      if (item.type === 'leader') return deptLeaderFlag;
      if (!item.value || !parseAssignees(item.value)?.some(parsed => parsed.type === item.type))
        throw new Error('请选择审批人');
      return item.value;
    })
    .join('@@');
}
/** The engine completes the first human task during submission. Never expose it as an approval. */
export function submissionNodeCodes(definition: DesignDefinition): Set<string> {
  const found = new Set<string>();
  const seen = new Set<string>();
  const visit = (code: string) => {
    if (seen.has(code)) return;
    seen.add(code);
    const node = definition.nodeList.find(item => item.nodeCode === code);
    if (!node || node.nodeType === 2) return;
    if (node.nodeType === 1) {
      found.add(code);
      return;
    }
    node.skipList?.filter(edge => edge.skipType !== 'REJECT').forEach(edge => visit(edge.nextNodeCode));
  };
  definition.nodeList.filter(node => node.nodeType === 0).forEach(node => visit(node.nodeCode));
  return found;
}
export interface NodeChange {
  nodeCode: string;
  permissionFlag?: string;
  nodeRatio?: string;
}
export interface EdgeChange {
  nodeCode: string;
  edgeIndex: number;
  skipCondition: string;
}
export function applyProjectChanges(
  definition: DesignDefinition,
  nodes: NodeChange[],
  edges: EdgeChange[]
): DesignDefinition {
  if (Number(definition.isPublish) !== 0 && (nodes.length || edges.length))
    throw new Error('当前流程不能修改，请先复制为新版本草稿');
  const protectedCodes = submissionNodeCodes(definition);
  const copy: DesignDefinition = JSON.parse(JSON.stringify(definition));
  for (const patch of nodes) {
    const node = copy.nodeList.find(item => item.nodeCode === patch.nodeCode);
    if (!node || node.nodeType !== 1 || protectedCodes.has(node.nodeCode)) throw new Error('提交申请节点不能修改');
    if (patch.permissionFlag !== undefined) node.permissionFlag = patch.permissionFlag;
    if (patch.nodeRatio !== undefined) node.nodeRatio = patch.nodeRatio;
  }
  for (const patch of edges) {
    const edge = copy.nodeList.find(item => item.nodeCode === patch.nodeCode)?.skipList?.[patch.edgeIndex];
    if (!edge) throw new Error('流程连线已变化，请重新加载');
    edge.skipCondition = patch.skipCondition;
  }
  return copy;
}

export const canEditBranchCondition = (nodeType: number): boolean => [3, 5].includes(nodeType);
/** Display decimal database ratios without rewriting their stored representation. */
export function simpleApprovalRatio(value?: string): string | null {
  if (!value) return '0';
  if (!/^\d+(\.\d+)?$/.test(value)) return null;
  const numeric = Number(value);
  return numeric === 0 ? '0' : numeric === 100 ? '100' : null;
}
