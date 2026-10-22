<template>
  <div v-loading="loading" class="project-flow-config">
    <div class="config-intro">
      <div>
        <h3>条件与审批人</h3>
        <p>选择业务条件和审批人。需要新增审批步骤、增加分支或调整顺序时，请打开流程图。</p>
      </div>
    </div>
    <el-alert
      v-if="readOnly"
      type="info"
      :closable="false"
      title="当前流程仅供查看。修改已发布或历史流程，请先复制为新版本草稿。"
    />
    <template v-if="definition">
      <section class="config-section">
        <h3>审批步骤</h3>
        <p class="muted">提交申请步骤由系统自动完成。以下为提交后的人工审批，人员还需具备项目审批权限。</p>
        <el-empty v-if="!nodes.length" description="暂无可配置的人工审批步骤，请到流程图添加。" />
        <article v-for="(item, index) in nodes" :key="item.node.nodeCode" class="config-card">
          <h4>{{ index + 1 }}. {{ item.node.nodeName || '审批步骤' }}</h4>
          <el-alert
            v-if="item.assignees === null"
            type="info"
            :closable="false"
            title="此步骤使用高级审批人规则，已原样保留。请在流程图中维护。"
          />
          <div v-else>
            <div v-for="(assignee, personIndex) in item.assignees" :key="personIndex" class="assignee-row">
              <el-select
                v-model="assignee.type"
                :disabled="readOnly"
                aria-label="审批人类型"
                @change="
                  assignee.value = '';
                  changedNode(item, 'assignees');
                "
              >
                <el-option v-for="type in assigneeTypes" :key="type.value" :label="type.label" :value="type.value" />
              </el-select>
              <HandlerPicker
                v-if="!['initiator', 'leader'].includes(assignee.type)"
                v-model="assignee.value"
                :type="assignee.type"
                :disabled="readOnly"
                @update:model-value="changedNode(item, 'assignees')"
              />
              <span v-else class="muted">
                {{ assignee.type === 'leader' ? '由本次发起人的部门决定' : '本次提交申请的人员' }}
              </span>
              <el-button
                v-if="!readOnly"
                type="danger"
                plain
                @click="
                  item.assignees.splice(personIndex, 1);
                  changedNode(item, 'assignees');
                "
              >
                移除
              </el-button>
            </div>
            <el-button
              v-if="!readOnly"
              link
              type="primary"
              @click="
                item.assignees.push({ type: 'user', value: '' });
                changedNode(item, 'assignees');
              "
            >
              ＋ 添加审批人
            </el-button>
          </div>
          <div class="approval-rule">
            <span>多人审批方式</span>
            <el-select
              v-if="item.simpleRatio"
              v-model="item.ratio"
              :disabled="readOnly"
              @change="changedNode(item, 'ratio')"
            >
              <el-option label="任一人通过" value="0" />
              <el-option label="所有人通过" value="100" />
            </el-select>
            <span v-else class="muted">使用高级会签或票签规则，已保留，请到流程图维护。</span>
          </div>
          <p v-if="item.hasAdvanced" class="muted">此步骤另有高级处理设置，已原样保留；如需修改，请到流程图维护。</p>
        </article>
      </section>
      <section class="config-section">
        <h3>分支条件</h3>
        <el-alert
          v-if="!edges.length"
          type="info"
          :closable="false"
          title="当前没有可配置的条件分支。请到流程图增加分支，再回来选择中文条件。"
        />
        <article v-for="item in edges" :key="`${item.nodeCode}-${item.edgeIndex}`" class="config-card">
          <h4>
            {{ item.fromName }} → {{ item.toName }}
            <span v-if="item.name">· {{ item.name }}</span>
          </h4>
          <el-alert
            v-if="!item.conditionGateway"
            type="warning"
            :closable="false"
            title="此连线不由条件网关判断，现有配置已原样保留。需要按业务条件分支时，请到流程图改用互斥或包含网关；并行分支会同时进入各路线。"
          />
          <el-alert
            v-else-if="item.condition === null"
            type="info"
            :closable="false"
            title="此分支使用高级条件，已原样保留。请在流程图中维护。"
          />
          <template v-else>
            <el-radio-group v-model="item.enabled" :disabled="readOnly" @change="changedEdge(item)">
              <el-radio :value="false">无条件</el-radio>
              <el-radio :value="true">满足条件时进入</el-radio>
            </el-radio-group>
            <div v-if="item.enabled" class="condition-row">
              <el-select
                v-model="item.condition.field"
                placeholder="选择业务条件"
                :disabled="readOnly"
                aria-label="条件字段"
                @change="conditionFieldChanged(item)"
              >
                <el-option v-for="field in conditionFields" :key="field.key" :label="field.label" :value="field.key" />
              </el-select>
              <el-select
                v-model="item.condition.operator"
                placeholder="比较方式"
                :disabled="readOnly"
                aria-label="比较方式"
                @change="changedEdge(item)"
              >
                <el-option
                  v-for="operator in allowedOperators(item.condition.field)"
                  :key="operator.value"
                  :label="operator.label"
                  :value="operator.value"
                />
              </el-select>
              <el-select
                v-if="item.condition.field === 'acquisitionMode'"
                v-model="item.condition.value"
                :disabled="readOnly"
                placeholder="选择承接方式"
                @change="changedEdge(item)"
              >
                <el-option label="直接委托" value="DIRECT" />
                <el-option label="投标" value="TENDER" />
              </el-select>
              <HandlerPicker
                v-else-if="['initiator', 'initiatorDeptId'].includes(item.condition.field)"
                v-model="item.condition.value"
                :type="item.condition.field === 'initiator' ? 'user' : 'dept'"
                raw
                :disabled="readOnly"
                @update:model-value="changedEdge(item)"
              />
              <el-input
                v-else
                v-model="item.condition.value"
                :disabled="readOnly || !item.condition.field"
                :placeholder="isAmount(item.condition.field) ? '请输入金额，单位：元' : '请输入条件值'"
                @input="changedEdge(item)"
              />
            </div>
          </template>
        </article>
      </section>
      <div class="config-actions">
        <el-button v-if="!readOnly" type="primary" :loading="saving" :disabled="!dirty" @click="save">
          保存配置
        </el-button>
        <el-button v-if="dirty" :disabled="saving" @click="discard">放弃本次修改</el-button>
        <span class="muted">
          {{ dirty ? '有未保存的修改；保存后再预览路线。' : '修改仅影响当前草稿，发布后用于新申请。' }}
        </span>
      </div>
      <section v-if="canPreview" class="config-section preview-section">
        <h3>审批路线预览</h3>
        <p class="muted">选择发起人和模拟申请资料，查看已保存流程的审批路线。此操作不会发起申请。</p>
        <el-form label-position="top" class="preview-fields" :disabled="dirty || saving">
          <el-form-item label="发起人（必选）">
            <HandlerPicker v-model="preview.initiatorId" type="user" raw :disabled="dirty || saving" />
          </el-form-item>
          <el-form-item label="承接方式">
            <el-select v-model="preview.variables.acquisitionMode">
              <el-option label="直接委托" value="DIRECT" />
              <el-option label="投标" value="TENDER" />
            </el-select>
          </el-form-item>
          <el-form-item v-for="field in previewFields" :key="field.key" :label="field.label">
            <el-input
              v-model="preview.variables[field.key]"
              :placeholder="field.kind === 'amount' ? '元，未填写按 0' : '未填写按空值'"
            />
          </el-form-item>
        </el-form>
        <el-button
          type="primary"
          plain
          :disabled="dirty || saving || !preview.initiatorId"
          :loading="previewing"
          @click="runPreview"
        >
          预览已保存的审批路线
        </el-button>
        <el-alert v-if="dirty" type="warning" :closable="false" title="请先保存配置，再预览新的审批路线。" />
        <div v-if="previewResult" class="preview-result">
          <el-alert
            v-for="(warning, index) in previewResult.warnings"
            :key="index"
            type="warning"
            :closable="false"
            :title="warning"
          />
          <el-alert
            v-if="!previewResult.complete"
            type="warning"
            :closable="false"
            title="此预览未能确认完整路线，请先处理提示后再发布流程。"
          />
          <el-timeline>
            <el-timeline-item
              v-for="(step, index) in previewResult.steps"
              :key="`${step.nodeCode}-${index}`"
              :type="step.nodeType === 1 ? 'primary' : 'info'"
            >
              <h4>{{ step.nodeName || nodeTypeName(step.nodeType) }}</h4>
              <p v-if="step.description">{{ step.description }}</p>
              <p v-if="step.assignees?.length">
                审批人：{{ step.assignees.map(person => person.userName || '姓名暂不可用').join('、') }}
              </p>
              <p v-else-if="step.nodeType === 1 && !step.description" class="muted">审批人尚未确认，请查看提示。</p>
            </el-timeline-item>
          </el-timeline>
        </div>
      </section>
    </template>
  </div>
</template>
<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, ref, watch } from 'vue';
import {
  queryDesign,
  saveDesign,
  previewProjectRoute,
  type DesignDefinition,
  type DesignNode,
  type RoutePreview
} from '@/api/workflow/definition/designer';
import auth from '@/plugins/auth';
import HandlerPicker from './HandlerPicker.vue';
import {
  allowedOperators,
  canEditBranchCondition,
  simpleApprovalRatio,
  applyProjectChanges,
  assigneeTypes,
  conditionFields,
  parseAssignees,
  parseCondition,
  serializeAssignees,
  serializeCondition,
  submissionNodeCodes,
  type Assignee,
  type Condition
} from './projectFlowModel';
const props = defineProps<{ definitionId: string; disabled: boolean }>();
const emit = defineEmits<{ graph: []; dirty: [value: boolean] }>();
interface NodeDraft {
  node: DesignNode;
  assignees: Assignee[] | null;
  ratio: string;
  simpleRatio: boolean;
  assigneesChanged: boolean;
  ratioChanged: boolean;
  hasAdvanced: boolean;
}
interface EdgeDraft {
  nodeCode: string;
  edgeIndex: number;
  fromName: string;
  toName: string;
  name?: string;
  enabled: boolean;
  conditionGateway: boolean;
  condition: Condition | null;
  changed: boolean;
}
const definition = ref<DesignDefinition>();
const nodes = ref<NodeDraft[]>([]);
const edges = ref<EdgeDraft[]>([]);
const loading = ref(false);
const saving = ref(false);
const dirty = ref(false);
const previewing = ref(false);
let previewGeneration = 0;
const previewResult = ref<RoutePreview>();
const readOnly = computed(() => props.disabled || Number(definition.value?.isPublish) !== 0);
const canPreview = auth.hasPermiAnd(['workflow:definition:query', 'workflow:definition:edit']);
const previewFields = computed(() =>
  conditionFields.filter(
    field =>
      ['amount', 'text'].includes(field.kind) &&
      edges.value.some(edge => edge.conditionGateway && edge.enabled && edge.condition?.field === field.key)
  )
);
const preview = ref({
  initiatorId: '',
  variables: {
    acquisitionMode: 'DIRECT',
    projectType: '',
    estimatedAmount: '',
    bidType: '',
    bidAmount: '',
    projectAmount: ''
  } as Record<string, string>
});
function setDirty(value: boolean) {
  dirty.value = value;
  emit('dirty', value);
  if (value) {
    previewGeneration++;
    previewResult.value = undefined;
  }
}
function initialize(data: DesignDefinition) {
  definition.value = data;
  const submitted = submissionNodeCodes(data);
  nodes.value = data.nodeList
    .filter(node => node.nodeType === 1 && !submitted.has(node.nodeCode))
    .map(node => ({
      node,
      assignees: parseAssignees(node.permissionFlag),
      ratio: simpleApprovalRatio(node.nodeRatio) || '',
      simpleRatio: simpleApprovalRatio(node.nodeRatio) !== null,
      assigneesChanged: false,
      ratioChanged: false,
      hasAdvanced: Boolean(node.listenerPath)
    }));
  edges.value = data.nodeList.flatMap(node => {
    const forward = (node.skipList || []).filter(edge => edge.skipType !== 'REJECT');
    return (node.skipList || []).flatMap((edge, edgeIndex) =>
      edge.skipType === 'REJECT' || (!edge.skipCondition && forward.length < 2)
        ? []
        : [
            {
              nodeCode: node.nodeCode,
              edgeIndex,
              fromName: node.nodeName || nodeTypeName(node.nodeType),
              toName: data.nodeList.find(target => target.nodeCode === edge.nextNodeCode)?.nodeName || '下一步骤',
              name: edge.skipName,
              enabled: Boolean(edge.skipCondition),
              conditionGateway: canEditBranchCondition(node.nodeType),
              condition: parseCondition(edge.skipCondition),
              changed: false
            }
          ]
    );
  });
  setDirty(false);
  previewResult.value = undefined;
}
async function reload() {
  loading.value = true;
  try {
    const { data } = await queryDesign(props.definitionId);
    initialize(data);
  } finally {
    loading.value = false;
  }
}
function changedNode(item: NodeDraft, field: 'assignees' | 'ratio') {
  if (readOnly.value) return;
  if (field === 'ratio') item.ratioChanged = true;
  else item.assigneesChanged = true;
  setDirty(true);
}
function changedEdge(item: EdgeDraft) {
  if (readOnly.value || !item.conditionGateway) return;
  item.changed = true;
  setDirty(true);
}
function conditionFieldChanged(item: EdgeDraft) {
  if (!item.condition) return;
  item.condition.operator = 'eq';
  item.condition.value = '';
  changedEdge(item);
}
function isAmount(field: string) {
  return conditionFields.some(item => item.key === field && item.kind === 'amount');
}
function nodeTypeName(type: number) {
  return ['开始', '人工审批', '结束', '条件分支', '并行分支', '包含分支'][type] || '流程步骤';
}
async function save(): Promise<boolean> {
  if (readOnly.value || !definition.value) return false;
  if (!dirty.value) return true;
  saving.value = true;
  try {
    const nodeChanges = nodes.value
      .filter(item => item.assigneesChanged || item.ratioChanged)
      .map(item => ({
        nodeCode: item.node.nodeCode,
        ...(item.assigneesChanged ? { permissionFlag: serializeAssignees(item.assignees || []) } : {}),
        ...(item.ratioChanged ? { nodeRatio: item.ratio } : {})
      }));
    const edgeChanges = edges.value
      .filter(item => item.changed)
      .map(item => ({
        nodeCode: item.nodeCode,
        edgeIndex: item.edgeIndex,
        skipCondition: item.enabled && item.condition ? serializeCondition(item.condition) : ''
      }));
    const { data: latest } = await queryDesign(props.definitionId);
    if (Number(latest.isPublish) !== 0 || JSON.stringify(latest) !== JSON.stringify(definition.value)) {
      ElMessage.warning('流程已被修改或发布，请放弃本次修改并重新加载后再配置。');
      return false;
    }
    await saveDesign(applyProjectChanges(definition.value, nodeChanges, edgeChanges));
    await reload();
    ElMessage.success('配置已保存，可预览审批路线');
    return true;
  } catch (error) {
    if (error instanceof Error) ElMessage.error(error.message);
    return false;
  } finally {
    saving.value = false;
  }
}
async function discard() {
  try {
    await ElMessageBox.confirm('放弃本次未保存的修改并重新加载？', '放弃修改', { type: 'warning' });
    await reload();
  } catch {
    /* Keep the user's draft on cancellation. */
  }
}
async function beforeLeave(): Promise<boolean> {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm('有未保存的配置。是否保存后继续？', '未保存的修改', {
      confirmButtonText: '保存后继续',
      cancelButtonText: '放弃修改',
      distinguishCancelAndClose: true,
      type: 'warning'
    });
    return await save();
  } catch (action) {
    if (action === 'cancel') {
      await reload();
      return true;
    }
    return false;
  }
}
async function runPreview() {
  if (dirty.value || !preview.value.initiatorId) return;
  const variables: Record<string, string | number> = { ...preview.value.variables };
  for (const field of conditionFields.filter(item => item.kind === 'amount')) {
    const value = String(variables[field.key] || '0');
    if (!/^\d+(\.\d+)?$/.test(value)) {
      ElMessage.warning('预览金额必须是大于等于零的数字，单位为元');
      return;
    }
    variables[field.key] = value;
  }
  const generation = ++previewGeneration;
  previewing.value = true;
  previewResult.value = undefined;
  try {
    const { data } = await previewProjectRoute({
      definitionId: props.definitionId,
      initiatorId: preview.value.initiatorId,
      variables
    });
    if (!dirty.value && generation === previewGeneration) previewResult.value = data;
  } finally {
    previewing.value = false;
  }
}
watch(
  preview,
  () => {
    previewGeneration++;
    previewResult.value = undefined;
  },
  { deep: true }
);
onMounted(reload);
defineExpose({ beforeLeave, reload });
</script>
<style scoped>
.project-flow-config {
  overflow-y: auto;
  padding: 20px 24px 40px;
  flex: 1;
  min-height: 0;
}
.config-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 16px;
}
h3,
h4 {
  margin: 0 0 12px;
}
p {
  margin: 8px 0;
  line-height: 1.7;
}
.config-section {
  margin-top: 28px;
}
.config-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 18px;
  margin-top: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.assignee-row,
.condition-row,
.approval-rule,
.config-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
  flex-wrap: wrap;
}
.assignee-row > .el-select,
.approval-rule > .el-select {
  width: 210px;
}
.assignee-row > .handler-picker {
  flex: 1;
  max-width: 420px;
}
.condition-row > * {
  flex: 1;
  min-width: 180px;
}
.condition-row > :nth-child(2) {
  flex: 0 0 150px;
  min-width: 120px;
}
.config-actions {
  padding: 16px 0;
  border-bottom: 1px solid var(--el-border-color);
}
.preview-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: 0 20px;
}
.preview-result {
  margin-top: 20px;
}
.el-alert {
  margin: 10px 0;
}
.el-timeline {
  margin-top: 24px;
}
@media (max-width: 760px) {
  .project-flow-config {
    padding: 16px;
  }
  .config-intro {
    align-items: flex-start;
    flex-direction: column;
  }
  .preview-fields {
    grid-template-columns: 1fr;
  }
  .condition-row > *,
  .assignee-row > * {
    width: 100%;
    flex: auto;
  }
}
</style>
