<template>
  <div v-loading="loading" class="project-page">
    <div class="project-actions">
      <el-button @click="router.push('/project/projects')">返回项目列表</el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="project">
      <header class="project-heading">
        <div>
          <h1>{{ project.name }}</h1>
          <p>
            {{ project.customerName }} · {{ modeLabel(project.acquisitionMode) }} · 经办人：{{
              project.ownerName || '—'
            }}
          </p>
        </div>
        <el-tag size="large" :type="stageType(project.stage)">{{ stageLabels[project.stage] }}</el-tag>
      </header>
      <section class="project-panel next-panel">
        <h2>{{ nextAction(project) }}</h2>
        <p class="project-note">{{ stageHint }}</p>
        <div class="project-actions">
          <el-button v-if="project.stage === 'DRAFT' && project.canEdit && canEdit" @click="editVisible = true">
            修改备案信息
          </el-button>
          <el-button
            v-if="project.stage === 'DRAFT' && canSubmit"
            type="primary"
            :loading="saving"
            @click="registration"
          >
            提交备案审批
          </el-button>
          <el-button
            v-if="project.stage === 'REGISTERED' && project.acquisitionMode === 'TENDER' && canSubmit"
            type="primary"
            @click="openAction('bid')"
          >
            填写投标申请
          </el-button>
          <el-button v-if="canInitiate && canSubmit" type="primary" @click="openAction('initiation')">
            填写施工立项
          </el-button>
          <el-button v-if="project.stage === 'BID_PREPARATION' && canEdit" @click="openAction('task')">
            添加准备事项
          </el-button>
          <el-button
            v-if="project.stage === 'BID_PREPARATION' && canEdit"
            type="primary"
            @click="openAction('opening')"
          >
            登记开标结果
          </el-button>
          <el-button v-if="canFollowUp" @click="openAction('followup')">记录跟进</el-button>
          <el-button v-if="canTerminate" type="danger" plain @click="openAction('terminate')">终止项目</el-button>
          <el-button
            v-if="inReview && checkPermi(['project:approval:list'])"
            type="primary"
            @click="router.push('/project/approvals')"
          >
            前往我的审批
          </el-button>
        </div>
        <p class="project-note">{{ approvalRule }}</p>
      </section>
      <section class="project-panel">
        <h2>办理进度</h2>
        <el-steps
          :active="progress"
          :process-status="project.stage === 'TERMINATED' || project.stage === 'BID_LOST' ? 'error' : 'process'"
          finish-status="success"
          align-center
        >
          <el-step title="项目备案" :description="project.stage === 'DRAFT' ? '待提交' : ''" />
          <el-step v-if="project.acquisitionMode === 'TENDER'" title="投标申请" />
          <el-step
            v-if="project.acquisitionMode === 'TENDER'"
            title="投标准备与开标"
            :description="project.stage === 'BID_LOST' ? '未中标' : ''"
          />
          <el-step title="施工立项" />
        </el-steps>
        <el-alert
          v-if="project.stage === 'TERMINATED'"
          :title="`终止原因：${project.terminationReason || '请查看办理记录'}`"
          type="warning"
          :closable="false"
        />
      </section>
      <section class="project-panel">
        <h2>项目信息</h2>
        <el-descriptions :column="descriptionColumns" border>
          <el-descriptions-item label="项目名称">{{ project.name }}</el-descriptions-item>
          <el-descriptions-item label="建设单位">{{ project.customerName }}</el-descriptions-item>
          <el-descriptions-item label="项目地点">{{ project.location }}</el-descriptions-item>
          <el-descriptions-item label="承接方式">{{ modeLabel(project.acquisitionMode) }}</el-descriptions-item>
          <el-descriptions-item label="联系人">{{ project.contactName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ project.contactPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="项目类别">{{ project.projectType || '—' }}</el-descriptions-item>
          <el-descriptions-item label="预计金额">{{ money(project.estimatedAmount) }}</el-descriptions-item>
          <template v-if="project.bidName">
            <el-descriptions-item label="投标名称">{{ project.bidName }}</el-descriptions-item>
            <el-descriptions-item label="投标类别">{{ project.bidType }}</el-descriptions-item>
            <el-descriptions-item label="投标截止日期">{{ project.bidDeadline }}</el-descriptions-item>
          </template>
          <template v-if="project.bidResult">
            <el-descriptions-item label="开标结果">
              {{ project.bidResult === 'WON' ? '中标' : '未中标' }}
            </el-descriptions-item>
            <el-descriptions-item label="开标日期">{{ project.openingDate }}</el-descriptions-item>
            <el-descriptions-item v-if="project.bidResult === 'WON'" label="中标金额">
              {{ money(project.bidAmount) }}
            </el-descriptions-item>
            <el-descriptions-item label="结果说明">{{ project.bidRemark || '—' }}</el-descriptions-item>
          </template>
          <template v-if="project.managerName">
            <el-descriptions-item label="项目负责人">{{ project.managerName }}</el-descriptions-item>
            <el-descriptions-item label="立项金额">{{ money(project.projectAmount) }}</el-descriptions-item>
            <el-descriptions-item label="计划开工日期">{{ project.plannedStart }}</el-descriptions-item>
            <el-descriptions-item label="计划竣工日期">{{ project.plannedEnd }}</el-descriptions-item>
          </template>
        </el-descriptions>
      </section>
      <section
        v-if="project.acquisitionMode === 'TENDER' && (project.tasks?.length || hasEnteredBidPreparation)"
        class="project-panel"
      >
        <h2>投标准备事项</h2>
        <el-alert
          title="以下为可选准备事项及记录。标记完成不代表证书调配、实际盖章或银行收付款已完成。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-table :data="project.tasks || []" empty-text="尚无准备事项，可按实际需要添加，不影响登记开标结果">
          <el-table-column prop="title" label="事项名称" min-width="160" />
          <el-table-column label="类别" min-width="110">
            <template #default="{ row }">{{ taskLabels[row.kind as TaskKind] }}</template>
          </el-table-column>
          <el-table-column prop="assigneeName" label="办理人" width="110" />
          <el-table-column prop="dueDate" label="计划完成" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'DONE' ? 'success' : 'warning'">
                {{ row.status === 'DONE' ? '记录完成' : '待办理' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column v-if="project.stage === 'BID_PREPARATION' && canEdit" label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="saving" @click="changeTask(row as ProjectTask)">
                {{ row.status === 'DONE' ? '改为待办理' : '填写完成记录' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <section class="project-panel">
        <h2>申请与审批记录</h2>
        <el-empty v-if="!project.applications?.length" description="尚未提交申请" :image-size="70" />
        <article v-for="application in project.applications" :key="application.id" class="application-record">
          <div class="project-actions">
            <strong>{{ applicationLabels[application.type] }}</strong>
            <el-tag
              :type="
                application.status === 'APPROVED' ? 'success' : application.status === 'RETURNED' ? 'danger' : 'warning'
              "
            >
              {{ approvalLabels[application.status] }}
            </el-tag>
            <span class="project-note">{{ dateTime(application.submittedAt) }} 提交</span>
          </div>
          <p v-if="application.comment">审批意见：{{ application.comment }}</p>
          <p v-if="application.finishedAt" class="project-note">审批时间：{{ dateTime(application.finishedAt) }}</p>
          <el-collapse>
            <el-collapse-item title="查看本次提交内容" :name="application.id">
              <el-descriptions :column="descriptionColumns" border>
                <el-descriptions-item
                  v-for="entry in snapshotEntries(application.snapshot)"
                  :key="entry.label"
                  :label="entry.label"
                >
                  {{ entry.value }}
                </el-descriptions-item>
              </el-descriptions>
              <p v-if="!snapshotEntries(application.snapshot).length" class="project-note">暂无可展示的申请内容</p>
            </el-collapse-item>
          </el-collapse>
        </article>
      </section>
      <section class="project-panel">
        <h2>办理与跟进记录</h2>
        <el-empty v-if="!project.events?.length" description="暂无办理记录" :image-size="70" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="event in project.events"
            :key="event.id"
            :timestamp="dateTime(event.createdAt)"
            placement="top"
          >
            <strong>{{ event.actorName || '系统' }}</strong>
            <p class="event-message">{{ event.message }}</p>
          </el-timeline-item>
        </el-timeline>
      </section>
      <ProjectForm v-model="editVisible" :project="project" @saved="updated" />
      <ActionForm v-model="actionVisible" :project="project" :action="action" @saved="updated" />
    </template>
    <el-empty v-else-if="!loading && !error" description="未找到项目" />
  </div>
</template>
<script setup name="ProjectDetail" lang="ts">
import type { ProjectDetail, ProjectTask, TaskKind } from '@/api/project/types';
import { getProject, submitRegistration, updatePreparation } from '@/api/project';
import { useUserStore } from '@/store/modules/user';
import { checkPermi } from '@/utils/permission';
import ActionForm, { type Action } from '../components/ActionForm.vue';
import ProjectForm from '../components/ProjectForm.vue';
import {
  applicationLabels,
  approvalLabels,
  approvalRule,
  dateTime,
  modeLabel,
  money,
  nextAction,
  snapshotEntries,
  stageLabels,
  stageType,
  taskLabels
} from '../shared';
import '../project.scss';
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const project = ref<ProjectDetail>();
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const editVisible = ref(false);
const actionVisible = ref(false);
const action = ref<Action>('bid');
const descriptionColumns = computed(() => (windowWidth.value < 700 ? 1 : 2));
const { width: windowWidth } = useWindowSize();
const isOwner = computed(() => project.value && String(project.value.ownerId) === String(userStore.userId));
const canEdit = computed(() => isOwner.value && checkPermi(['project:project:edit']));
const canSubmit = computed(() => isOwner.value && project.value?.canSubmit && checkPermi(['project:project:submit']));
const inReview = computed(() => project.value?.stage.endsWith('_REVIEW'));
const canInitiate = computed(
  () =>
    project.value?.stage === 'BID_WON' ||
    (project.value?.stage === 'REGISTERED' && project.value.acquisitionMode === 'DIRECT')
);
const canFollowUp = computed(
  () =>
    canEdit.value &&
    project.value &&
    !['DRAFT', 'REGISTRATION_REVIEW', 'TERMINATED', 'ESTABLISHED'].includes(project.value.stage)
);
const canTerminate = computed(
  () =>
    canEdit.value && project.value && !inReview.value && !['TERMINATED', 'ESTABLISHED'].includes(project.value.stage)
);
const hasEnteredBidPreparation = computed(
  () =>
    project.value &&
    ['BID_PREPARATION', 'BID_WON', 'BID_LOST', 'INITIATION_REVIEW', 'ESTABLISHED'].includes(project.value.stage)
);
const progress = computed(() => {
  if (!project.value) return 0;
  const stage = project.value.stage;
  if (stage === 'DRAFT' || stage === 'REGISTRATION_REVIEW' || stage === 'TERMINATED') return 0;
  if (project.value.acquisitionMode === 'DIRECT') return stage === 'ESTABLISHED' ? 2 : 1;
  if (stage === 'REGISTERED' || stage === 'BID_REVIEW') return 1;
  if (stage === 'BID_PREPARATION' || stage === 'BID_LOST') return 2;
  return stage === 'ESTABLISHED' ? 4 : 3;
});
const stageHint = computed(() => {
  if (!project.value) return '';
  if (inReview.value) return '申请已提交审批负责人审核，请等待结果；退回意见会显示在下方审批记录中。';
  if (project.value.stage === 'DRAFT') return '核对项目名称、建设单位、承接方式和地点，保存草稿后提交备案。';
  if (project.value.stage === 'BID_PREPARATION')
    return '按需要记录标书、借证、用章或保证金准备情况；准备事项为选填，开标后登记结果。';
  if (project.value.stage === 'BID_LOST') return '本次未中标，项目记录会保留，可记录后续跟进或终止项目。';
  if (project.value.stage === 'ESTABLISHED') return '施工立项审批已通过，本阶段办理完成。';
  if (project.value.stage === 'TERMINATED') return '项目已终止，历史申请、审批和跟进记录继续保留。';
  return canEdit.value || canSubmit.value
    ? '按下一步要求填写申请，审批通过后进入后续阶段。'
    : '当前账号可查看项目和审批记录；由项目经办人办理后续事项。';
});
let requestNumber = 0;
async function load() {
  const current = ++requestNumber;
  loading.value = true;
  error.value = '';
  try {
    const { data } = await getProject(String(route.params.id));
    if (current === requestNumber) project.value = data;
  } catch (cause) {
    if (current === requestNumber) {
      project.value = undefined;
      error.value = cause instanceof Error ? cause.message : '项目加载失败，请重试';
    }
  } finally {
    if (current === requestNumber) loading.value = false;
  }
}
function updated(value: ProjectDetail) {
  project.value = value;
  error.value = '';
}
function openAction(value: Action) {
  action.value = value;
  actionVisible.value = true;
}
async function registration() {
  if (!project.value || saving.value) return;
  try {
    await ElMessageBox.confirm('确认提交项目备案？提交后等待审批，审批期间不能修改备案信息。', '提交备案', {
      confirmButtonText: '确认提交',
      cancelButtonText: '再核对一下',
      type: 'info'
    });
  } catch {
    return;
  }
  saving.value = true;
  error.value = '';
  try {
    const { data } = await submitRegistration(project.value.id);
    if (data) updated(data);
    ElMessage.success('备案申请已提交');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '提交失败，请重试';
  } finally {
    saving.value = false;
  }
}
async function changeTask(task: ProjectTask) {
  if (!project.value || saving.value) return;
  let note: string;
  try {
    const result = await ElMessageBox.prompt(
      '填写本次准备事项办理说明。记录完成不代表实际盖章、证书调配或银行收付款。',
      task.status === 'DONE' ? '改为待办理' : '填写完成记录',
      {
        inputType: 'textarea',
        inputValue: task.note || '',
        confirmButtonText: '保存记录',
        cancelButtonText: '取消',
        inputValidator: value => !value || value.length <= 1000 || '说明最多1000字'
      }
    );
    note = result.value || '';
  } catch {
    return;
  }
  saving.value = true;
  error.value = '';
  try {
    const { data } = await updatePreparation(project.value.id, task.id, {
      status: task.status === 'DONE' ? 'TODO' : 'DONE',
      note
    });
    if (data) updated(data);
    ElMessage.success('准备事项已更新');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '更新失败，请重试';
  } finally {
    saving.value = false;
  }
}
watch(() => route.params.id, load, { immediate: true });
</script>
<style scoped>
.next-panel {
  border-left: 4px solid var(--el-color-primary);
}
.application-record + .application-record {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.event-message {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
}
</style>
