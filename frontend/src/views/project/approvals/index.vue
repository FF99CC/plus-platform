<template>
  <div class="project-page">
    <header class="project-heading">
      <div>
        <h1>我的审批</h1>
        <p>核对项目背景和本次提交内容，给出通过或退回意见。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </header>
    <section class="project-panel">
      <p class="project-note">{{ approvalRule }}</p>
      <el-tabs v-model="status" @tab-change="search">
        <el-tab-pane label="待审批" name="SUBMITTED" />
        <el-tab-pane label="已通过" name="APPROVED" />
        <el-tab-pane label="已退回" name="RETURNED" />
      </el-tabs>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="applications" row-key="id" empty-text="暂无此类审批申请">
        <el-table-column prop="projectName" label="项目名称" min-width="230" />
        <el-table-column label="申请事项" min-width="140">
          <template #default="{ row }">{{ applicationLabels[row.type as ApplicationType] }}</template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="180">
          <template #default="{ row }">{{ dateTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="当前审批环节" min-width="160">
          <template #default="{ row }">
            {{ row.currentTasks?.map((task: { nodeName: string }) => task.nodeName).join('、') || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'RETURNED' ? 'danger' : row.status === 'APPROVED' ? 'success' : 'warning'">
              {{ approvalLabels[row.status as ApplicationStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="comment" label="审批意见" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="inspect(row as ProjectApplication)">
              {{ row.canHandle ? '查看并审批' : '查看申请' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="project-pagination">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </section>
    <el-drawer
      v-model="drawerVisible"
      title="申请审批"
      size="min(760px, 100%)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      destroy-on-close
    >
      <template v-if="selected">
        <h2>{{ selected.projectName }}</h2>
        <p>{{ applicationLabels[selected.type] }} · {{ approvalLabels[selected.status] }}</p>
        <p class="project-note">提交于 {{ dateTime(selected.submittedAt) }}</p>
        <p v-if="selected.currentTasks?.length" class="project-note">
          当前审批环节：{{ selected.currentTasks.map(task => task.nodeName).join('、') }}
        </p>
        <h3>本次提交内容</h3>
        <p class="project-note">以下内容保留提交时的信息，供本次审核使用。</p>
        <el-descriptions :column="1" border>
          <el-descriptions-item
            v-for="entry in snapshotEntries(selected.snapshot)"
            :key="entry.label"
            :label="entry.label"
          >
            {{ entry.value }}
          </el-descriptions-item>
        </el-descriptions>
        <el-empty
          v-if="!snapshotEntries(selected.snapshot).length"
          description="申请内容暂不可展示，请核对项目详情"
          :image-size="60"
        />
        <h3>项目背景</h3>
        <div v-loading="contextLoading">
          <el-alert v-if="contextError" :title="contextError" type="error" :closable="false" show-icon />
          <el-descriptions v-if="context" :column="1" border>
            <el-descriptions-item label="建设单位">{{ context.customerName }}</el-descriptions-item>
            <el-descriptions-item label="项目地点">{{ context.location }}</el-descriptions-item>
            <el-descriptions-item label="承接方式">{{ modeLabel(context.acquisitionMode) }}</el-descriptions-item>
            <el-descriptions-item label="经办人">{{ context.ownerName }}</el-descriptions-item>
            <el-descriptions-item label="当前阶段">{{ stageLabels[context.stage] }}</el-descriptions-item>
            <el-descriptions-item v-if="context.bidResult" label="开标结果">
              {{ context.bidResult === 'WON' ? '中标' : '未中标' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="context.bidAmount" label="中标金额">
              {{ money(context.bidAmount) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
        <el-button
          class="detail-link"
          link
          type="primary"
          @click="router.push(`/project/detail/${selected.projectId}`)"
        >
          查看项目全部记录
        </el-button>
        <template v-if="selected.canHandle && checkPermi(['project:approval:handle'])">
          <h3>审批意见</h3>
          <el-input
            v-model="comment"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="退回时必须填写意见，说明需要补充或修改的内容"
            :disabled="saving"
          />
          <p class="project-note">{{ approvalRule }}</p>
        </template>
        <p v-else-if="selected.status === 'SUBMITTED'" class="project-note">
          当前环节由流程指定人员办理，请等待审批结果。
        </p>
        <template v-else>
          <h3>审批结果</h3>
          <p>{{ selected.comment || '未填写审批意见' }}</p>
          <p class="project-note">{{ dateTime(selected.finishedAt) }}</p>
        </template>
        <el-alert v-if="decisionError" :title="decisionError" type="error" :closable="false" show-icon />
      </template>
      <template #footer>
        <div class="project-actions">
          <el-button :disabled="saving" @click="drawerVisible = false">关闭</el-button>
          <template v-if="selected?.canHandle && checkPermi(['project:approval:handle'])">
            <el-button
              type="danger"
              plain
              :loading="saving"
              :disabled="contextLoading || !context"
              @click="decision('RETURN')"
            >
              退回修改
            </el-button>
            <el-button
              type="primary"
              :loading="saving"
              :disabled="contextLoading || !context"
              @click="decision('APPROVE')"
            >
              审批通过
            </el-button>
          </template>
        </div>
      </template>
    </el-drawer>
  </div>
</template>
<script setup name="ProjectApprovals" lang="ts">
import type { ApplicationStatus, ApplicationType, ProjectApplication, ProjectDetail } from '@/api/project/types';
import { decideApproval, getProject, listApprovals } from '@/api/project';
import { checkPermi } from '@/utils/permission';
import {
  applicationLabels,
  approvalLabels,
  approvalRule,
  dateTime,
  modeLabel,
  money,
  snapshotEntries,
  stageLabels
} from '../shared';
import '../project.scss';
const router = useRouter();
const applications = ref<ProjectApplication[]>([]);
const status = ref<ApplicationStatus>('SUBMITTED');
const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const loading = ref(false);
const error = ref('');
const drawerVisible = ref(false);
const selected = ref<ProjectApplication>();
const context = ref<ProjectDetail>();
const contextLoading = ref(false);
const contextError = ref('');
const comment = ref('');
const saving = ref(false);
const decisionError = ref('');
let listNumber = 0;
let contextNumber = 0;
async function load() {
  const current = ++listNumber;
  loading.value = true;
  error.value = '';
  try {
    const { data } = await listApprovals({ status: status.value, pageNum: pageNum.value, pageSize: pageSize.value });
    if (current === listNumber) {
      applications.value = data?.rows || [];
      total.value = data?.total || 0;
    }
  } catch (cause) {
    if (current === listNumber) {
      applications.value = [];
      total.value = 0;
      error.value = cause instanceof Error ? cause.message : '审批申请加载失败';
    }
  } finally {
    if (current === listNumber) loading.value = false;
  }
}
function search() {
  pageNum.value = 1;
  load();
}
async function inspect(application: ProjectApplication) {
  const current = ++contextNumber;
  selected.value = application;
  comment.value = '';
  decisionError.value = '';
  context.value = undefined;
  contextError.value = '';
  drawerVisible.value = true;
  contextLoading.value = true;
  try {
    const { data } = await getProject(application.projectId);
    if (current === contextNumber) context.value = data;
  } catch (cause) {
    if (current === contextNumber)
      contextError.value = cause instanceof Error ? cause.message : '项目背景加载失败，请关闭后重试';
  } finally {
    if (current === contextNumber) contextLoading.value = false;
  }
}
async function decision(value: 'APPROVE' | 'RETURN') {
  if (!selected.value || saving.value || !context.value) return;
  if (value === 'RETURN' && !comment.value.trim()) {
    decisionError.value = '请填写退回意见，说明需要修改的内容';
    return;
  }
  try {
    await ElMessageBox.confirm(
      value === 'APPROVE'
        ? '确认通过当前审批环节？如有后续审批，将继续交给下一位办理人。'
        : '确认退回本次申请？经办人可按意见修改后重新提交。',
      '确认审批结果',
      {
        confirmButtonText: value === 'APPROVE' ? '确认通过' : '确认退回',
        cancelButtonText: '取消',
        type: value === 'APPROVE' ? 'info' : 'warning'
      }
    );
  } catch {
    return;
  }
  saving.value = true;
  decisionError.value = '';
  try {
    await decideApproval(selected.value.id, value, comment.value.trim());
    drawerVisible.value = false;
    ElMessage.success(value === 'APPROVE' ? '本环节审批已提交，后续按流程继续办理' : '申请已退回');
    await load();
  } catch (cause) {
    decisionError.value = cause instanceof Error ? cause.message : '审批失败，请刷新后确认申请状态';
  } finally {
    saving.value = false;
  }
}
onMounted(load);
onActivated(load);
</script>
<style scoped>
.detail-link {
  margin: 18px 0;
}
h3 {
  margin-top: 26px;
}
</style>
