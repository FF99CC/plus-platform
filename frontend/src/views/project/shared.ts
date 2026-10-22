import type { ApplicationStatus, ApplicationType, Project, ProjectStage, TaskKind } from '@/api/project/types';

export const stageLabels: Record<ProjectStage, string> = {
  DRAFT: '备案草稿',
  REGISTRATION_REVIEW: '备案审批中',
  REGISTERED: '已备案',
  BID_REVIEW: '投标审批中',
  BID_PREPARATION: '投标准备',
  BID_WON: '已中标',
  BID_LOST: '未中标',
  INITIATION_REVIEW: '立项审批中',
  ESTABLISHED: '已立项',
  TERMINATED: '已终止'
};
export const applicationLabels: Record<ApplicationType, string> = {
  REGISTRATION: '项目备案',
  BID: '投标申请',
  INITIATION: '施工立项'
};
export const approvalLabels: Record<ApplicationStatus, string> = {
  SUBMITTED: '待审批',
  APPROVED: '已通过',
  RETURNED: '已退回'
};
export const taskLabels: Record<TaskKind, string> = {
  BID_DOCUMENT: '标书准备',
  CERTIFICATE: '借证记录',
  SEAL: '用章记录',
  GUARANTEE: '保证金记录',
  OTHER: '其他准备事项'
};
export const modeLabel = (mode: string) => (mode === 'DIRECT' ? '直接委托' : '投标');
export const stageType = (stage: ProjectStage): 'primary' | 'success' | 'warning' | 'info' | 'danger' => {
  if (stage === 'TERMINATED' || stage === 'BID_LOST') return 'info';
  if (stage.endsWith('REVIEW')) return 'warning';
  if (stage === 'ESTABLISHED' || stage === 'BID_WON') return 'success';
  return 'primary';
};
export const nextAction = (project: Project) => {
  const labels: Record<ProjectStage, string> = {
    DRAFT: '完善备案信息并提交',
    REGISTRATION_REVIEW: '等待备案审批',
    REGISTERED: project.acquisitionMode === 'TENDER' ? '填写投标申请' : '填写施工立项',
    BID_REVIEW: '等待投标审批',
    BID_PREPARATION: '准备投标并登记开标结果',
    BID_WON: '填写施工立项',
    BID_LOST: '保留未中标记录',
    INITIATION_REVIEW: '等待立项审批',
    ESTABLISHED: '立项办理完成',
    TERMINATED: '项目已终止'
  };
  return labels[project.stage] || '查看办理详情';
};
export const money = (value?: string | number) => {
  if (value === undefined || value === null || value === '') return '未填写';
  const [whole, fraction = ''] = String(value).split('.');
  // 十进制字符串直接分组，避免大金额转换为浮点数后丢失分位。
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')} 元`;
};
export const dateTime = (value?: string) =>
  value
    ? value
        .replace('T', ' ')
        .replace(/\.\d+.*$/, '')
        .slice(0, 19)
    : '—';
export const validMoney = (value: unknown, positive = false) =>
  /^\d{1,16}(\.\d{1,2})?$/.test(String(value)) && (!positive || Number(value) > 0);
export const approvalRule = '按当前流程分配审批人员；全部审批环节通过后，项目进入下一阶段。';

// 审批展示白名单：保留申请时的业务内容，不显示内部流程字段。
export const snapshotLabels: Record<string, string> = {
  name: '项目名称',
  customerName: '建设单位',
  location: '项目地点',
  acquisitionMode: '承接方式',
  contactName: '联系人',
  contactPhone: '联系电话',
  projectType: '项目类别',
  estimatedAmount: '预计金额',
  bidName: '投标名称',
  bidType: '投标类别',
  bidDeadline: '投标截止日期',
  managerName: '项目负责人',
  projectAmount: '立项金额',
  plannedStart: '计划开工日期',
  plannedEnd: '计划竣工日期'
};
export const snapshotEntries = (snapshot?: Record<string, unknown>) =>
  Object.entries(snapshotLabels)
    .filter(([key]) => snapshot?.[key] !== undefined && snapshot?.[key] !== null && snapshot?.[key] !== '')
    .map(([key, label]) => ({
      label,
      value:
        key === 'acquisitionMode'
          ? modeLabel(String(snapshot![key]))
          : key.endsWith('Amount')
            ? money(String(snapshot![key]))
            : String(snapshot![key])
    }));
