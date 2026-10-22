<template>
  <el-dialog
    v-model="visible"
    :title="project ? '修改备案信息' : '新建项目备案'"
    width="680px"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @closed="formRef?.clearValidate()"
  >
    <p class="project-note">先保存草稿，核对信息后再提交备案审批。金额单位为元。</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="save">
      <div class="project-form-grid">
        <el-form-item label="项目名称" prop="name" class="full">
          <el-input v-model="form.name" maxlength="120" placeholder="填写项目的完整名称" />
        </el-form-item>
        <el-form-item label="建设单位" prop="customerName">
          <el-input v-model="form.customerName" maxlength="120" placeholder="委托或建设单位名称" />
        </el-form-item>
        <el-form-item label="承接方式" prop="acquisitionMode">
          <el-select v-model="form.acquisitionMode">
            <el-option label="直接委托" value="DIRECT" />
            <el-option label="投标" value="TENDER" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目地点" prop="location" class="full">
          <el-input v-model="form.location" maxlength="250" placeholder="项目所在地区或地址" />
        </el-form-item>
        <el-form-item label="联系人（选填）" prop="contactName">
          <el-input v-model="form.contactName" maxlength="60" />
        </el-form-item>
        <el-form-item label="联系电话（选填）" prop="contactPhone">
          <el-input v-model="form.contactPhone" maxlength="30" />
        </el-form-item>
        <el-form-item label="项目类别（选填）" prop="projectType">
          <el-input v-model="form.projectType" maxlength="60" placeholder="例如：房建、市政" />
        </el-form-item>
        <el-form-item label="预计金额（元，选填）" prop="estimatedAmount">
          <el-input v-model="form.estimatedAmount" inputmode="decimal" maxlength="19" placeholder="例如：1000000.00" />
        </el-form-item>
      </div>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus';
import type { Project, ProjectDetail, ProjectForm } from '@/api/project/types';
import { createProject, updateProject } from '@/api/project';
import { validMoney } from '../shared';

const props = defineProps<{ project?: Project }>();
const visible = defineModel<boolean>({ default: false });
const emit = defineEmits<{ saved: [project: ProjectDetail] }>();
const formRef = ref<FormInstance>();
const saving = ref(false);
const error = ref('');
const emptyForm = (): ProjectForm => ({
  name: '',
  customerName: '',
  location: '',
  acquisitionMode: 'DIRECT',
  contactName: '',
  contactPhone: '',
  projectType: '',
  estimatedAmount: ''
});
const form = ref<ProjectForm>(emptyForm());
const required = (label: string) => ({ required: true, whitespace: true, message: `请填写${label}`, trigger: 'blur' });
const rules: FormRules = {
  name: [required('项目名称')],
  customerName: [required('建设单位')],
  location: [required('项目地点')],
  estimatedAmount: [
    {
      validator: (_rule, value, callback) =>
        !value || validMoney(value) ? callback() : callback(new Error('请填写非负金额，最多16位整数、2位小数')),
      trigger: 'blur'
    }
  ]
};
watch(visible, open => {
  if (!open) return;
  error.value = '';
  const source = props.project;
  form.value = source
    ? {
        name: source.name,
        customerName: source.customerName,
        location: source.location,
        acquisitionMode: source.acquisitionMode,
        contactName: source.contactName || '',
        contactPhone: source.contactPhone || '',
        projectType: source.projectType || '',
        estimatedAmount: source.estimatedAmount ?? ''
      }
    : emptyForm();
});
async function save() {
  if (saving.value || !(await formRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  error.value = '';
  try {
    const data = Object.fromEntries(
      Object.entries(form.value).map(([key, value]) => [key, typeof value === 'string' ? value.trim() : value])
    ) as unknown as ProjectForm;
    if (data.estimatedAmount === '') delete data.estimatedAmount;
    const result = props.project ? await updateProject(props.project.id, data) : await createProject(data);
    if (!result.data) throw new Error('保存结果为空，请刷新后确认');
    emit('saved', result.data);
    visible.value = false;
    ElMessage.success('备案草稿已保存');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试';
  } finally {
    saving.value = false;
  }
}
</script>
