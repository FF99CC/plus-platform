<template>
  <el-dialog
    v-model="visible"
    :title="titles[action]"
    width="620px"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
  >
    <el-alert
      v-if="action === 'task'"
      title="准备事项为办理记录；记录完成不代表已完成证书调配、实际盖章或银行收付款。"
      type="info"
      :closable="false"
      show-icon
    />
    <p v-if="action === 'bid' || action === 'initiation'" class="project-note">
      {{ approvalRule }}。提交前请核对，退回后可修改并重新申请。
    </p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="save">
      <template v-if="action === 'bid'">
        <el-form-item label="投标名称" prop="bidName"><el-input v-model="form.bidName" maxlength="120" /></el-form-item>
        <el-form-item label="投标类别" prop="bidType">
          <el-input v-model="form.bidType" maxlength="60" placeholder="例如：公开招标、邀请招标" />
        </el-form-item>
        <el-form-item label="投标截止日期" prop="bidDeadline">
          <el-date-picker
            v-model="form.bidDeadline"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择截止日期"
            style="width: 100%"
          />
        </el-form-item>
      </template>
      <template v-if="action === 'initiation'">
        <el-form-item label="项目负责人" prop="managerName">
          <el-input v-model="form.managerName" maxlength="60" placeholder="填写施工项目负责人姓名" />
        </el-form-item>
        <el-form-item label="立项金额（元）" prop="projectAmount">
          <el-input
            v-model="form.projectAmount"
            inputmode="decimal"
            maxlength="19"
            placeholder="填写大于0的金额，最多2位小数"
          />
        </el-form-item>
        <el-form-item label="计划开工日期" prop="plannedStart">
          <el-date-picker v-model="form.plannedStart" value-format="YYYY-MM-DD" type="date" style="width: 100%" />
        </el-form-item>
        <el-form-item label="计划竣工日期" prop="plannedEnd">
          <el-date-picker v-model="form.plannedEnd" value-format="YYYY-MM-DD" type="date" style="width: 100%" />
        </el-form-item>
      </template>
      <template v-if="action === 'opening'">
        <el-form-item label="开标结果" prop="bidResult">
          <el-radio-group v-model="form.bidResult">
            <el-radio value="WON">中标</el-radio>
            <el-radio value="LOST">未中标</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="开标日期" prop="openingDate">
          <el-date-picker v-model="form.openingDate" value-format="YYYY-MM-DD" type="date" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="form.bidResult === 'WON'" label="中标金额（元）" prop="bidAmount">
          <el-input
            v-model="form.bidAmount"
            inputmode="decimal"
            maxlength="19"
            placeholder="填写大于0的金额，最多2位小数"
          />
        </el-form-item>
        <el-form-item label="结果说明（选填）" prop="bidRemark">
          <el-input v-model="form.bidRemark" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
        <p class="project-note">保存后进入下一阶段；未中标的项目保留记录，不能办理施工立项。</p>
      </template>
      <template v-if="action === 'task'">
        <el-form-item label="事项名称" prop="title">
          <el-input v-model="form.title" maxlength="120" placeholder="例如：准备投标文件" />
        </el-form-item>
        <el-form-item label="事项类别" prop="kind">
          <el-select v-model="form.kind" style="width: 100%">
            <el-option v-for="(label, value) in taskLabels" :key="value" :value="value" :label="label" />
          </el-select>
        </el-form-item>
        <el-form-item label="办理人（选填）" prop="assigneeName">
          <el-input v-model="form.assigneeName" maxlength="60" />
        </el-form-item>
        <el-form-item label="计划完成日期（选填）" prop="dueDate">
          <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="办理说明（选填）" prop="note">
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
      </template>
      <el-form-item v-if="action === 'followup'" label="跟进内容" prop="message">
        <el-input
          v-model="form.message"
          type="textarea"
          :rows="5"
          maxlength="1000"
          show-word-limit
          placeholder="记录沟通进展、待办事项或重要情况"
        />
      </el-form-item>
      <template v-if="action === 'terminate'">
        <el-alert title="终止后不再办理后续申请，已有记录将保留。" type="warning" :closable="false" show-icon />
        <el-form-item label="终止原因" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="请说明终止原因"
          />
        </el-form-item>
      </template>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button :type="action === 'terminate' ? 'danger' : 'primary'" :loading="saving" @click="save">
        {{ action === 'bid' || action === 'initiation' ? '提交审批' : '确认保存' }}
      </el-button>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus';
import type { BidForm, InitiationForm, OpeningForm, PreparationForm, ProjectDetail } from '@/api/project/types';
import {
  addFollowUp,
  addPreparation,
  recordOpening,
  submitBid,
  submitInitiation,
  terminateProject
} from '@/api/project';
import { approvalRule, taskLabels, validMoney } from '../shared';
export type Action = 'bid' | 'initiation' | 'opening' | 'task' | 'followup' | 'terminate';
const props = defineProps<{ project: ProjectDetail; action: Action }>();
const visible = defineModel<boolean>({ default: false });
const emit = defineEmits<{ saved: [project: ProjectDetail] }>();
const titles: Record<Action, string> = {
  bid: '申请投标',
  initiation: '申请施工立项',
  opening: '登记开标结果',
  task: '添加准备事项',
  followup: '记录项目跟进',
  terminate: '终止项目'
};
const formRef = ref<FormInstance>();
const saving = ref(false);
const error = ref('');
const form = ref<Record<string, any>>({});
const required = (label: string) => ({ required: true, whitespace: true, message: `请填写${label}`, trigger: 'blur' });
const amountRule = {
  validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) =>
    validMoney(value, true) ? callback() : callback(new Error('金额须大于0，最多16位整数、2位小数')),
  trigger: 'blur'
};
const rules = computed<FormRules>(() => {
  if (props.action === 'bid')
    return {
      bidName: [required('投标名称')],
      bidType: [required('投标类别')],
      bidDeadline: [required('投标截止日期')]
    };
  if (props.action === 'initiation')
    return {
      managerName: [required('项目负责人')],
      projectAmount: [amountRule],
      plannedStart: [required('计划开工日期')],
      plannedEnd: [
        required('计划竣工日期'),
        {
          validator: (_rule, value, callback) =>
            !value || !form.value.plannedStart || value >= form.value.plannedStart
              ? callback()
              : callback(new Error('竣工日期不能早于开工日期')),
          trigger: 'change'
        }
      ]
    };
  if (props.action === 'opening')
    return {
      openingDate: [required('开标日期')],
      ...(form.value.bidResult === 'WON' ? { bidAmount: [amountRule] } : {})
    };
  if (props.action === 'task') return { title: [required('事项名称')] };
  return props.action === 'followup' ? { message: [required('跟进内容')] } : { reason: [required('终止原因')] };
});
watch(visible, open => {
  if (!open) return;
  const p = props.project;
  error.value = '';
  form.value = {
    bidName: p.bidName || p.name,
    bidType: p.bidType || '',
    bidDeadline: p.bidDeadline || '',
    managerName: p.managerName || '',
    projectAmount: p.projectAmount ?? p.bidAmount ?? '',
    plannedStart: p.plannedStart || '',
    plannedEnd: p.plannedEnd || '',
    openingDate: '',
    bidResult: 'WON',
    bidAmount: '',
    bidRemark: '',
    title: '',
    kind: 'BID_DOCUMENT',
    assigneeName: '',
    dueDate: '',
    note: '',
    message: '',
    reason: ''
  };
  nextTick(() => formRef.value?.clearValidate());
});
async function save() {
  if (saving.value || !(await formRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  error.value = '';
  try {
    const id = props.project.id;
    const f = Object.fromEntries(
      Object.entries(form.value).map(([key, value]) => [key, typeof value === 'string' ? value.trim() : value])
    );
    let result;
    // 每种申请只发送业务所需字段，不把页面状态传给服务端。
    if (props.action === 'bid')
      result = await submitBid(id, { bidName: f.bidName, bidType: f.bidType, bidDeadline: f.bidDeadline } as BidForm);
    else if (props.action === 'initiation')
      result = await submitInitiation(id, {
        managerName: f.managerName,
        projectAmount: f.projectAmount,
        plannedStart: f.plannedStart,
        plannedEnd: f.plannedEnd
      } as InitiationForm);
    else if (props.action === 'opening')
      result = await recordOpening(id, {
        bidResult: f.bidResult,
        openingDate: f.openingDate,
        ...(f.bidResult === 'WON' ? { bidAmount: f.bidAmount } : {}),
        bidRemark: f.bidRemark
      } as OpeningForm);
    else if (props.action === 'task')
      result = await addPreparation(id, {
        title: f.title,
        kind: f.kind,
        assigneeName: f.assigneeName || undefined,
        dueDate: f.dueDate || undefined,
        note: f.note
      } as PreparationForm);
    else if (props.action === 'followup') result = await addFollowUp(id, String(f.message));
    else result = await terminateProject(id, String(f.reason));
    if (!result.data) throw new Error('保存结果为空，请刷新后确认');
    emit('saved', result.data);
    visible.value = false;
    ElMessage.success(props.action === 'bid' || props.action === 'initiation' ? '申请已提交' : '记录已保存');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试';
  } finally {
    saving.value = false;
  }
}
</script>
