<template>
  <div class="project-page">
    <header class="project-heading">
      <div>
        <h1>{{ user.nickname || '同事' }}，欢迎回来</h1>
        <p>查看项目办理进展，继续下一步工作。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新工作台</el-button>
    </header>
    <div class="overview-grid">
      <section v-if="canList" class="project-panel overview">
        <span>可查看项目</span>
        <strong>{{ projectTotal === null ? '—' : projectTotal }}</strong>
        <el-button link type="primary" @click="router.push('/project/projects')">查看项目办理</el-button>
      </section>
      <section v-if="canReview" class="project-panel overview">
        <span>待我审批</span>
        <strong>{{ approvalTotal === null ? '—' : approvalTotal }}</strong>
        <el-button link type="primary" @click="router.push('/project/approvals')">进入审批</el-button>
      </section>
      <section class="project-panel start-card">
        <h2>开始办理</h2>
        <p class="project-note">
          新项目先备案。直接委托项目通过备案后可申请立项；投标项目先申请投标、登记开标结果，中标后申请立项。
        </p>
        <el-button v-hasPermi="['project:project:add']" type="primary" @click="createVisible = true">
          新建项目备案
        </el-button>
        <p v-if="!canList && !canReview" class="project-note">当前账号可通过左侧菜单维护有权管理的基础设置。</p>
      </section>
    </div>
    <section v-if="canList" class="project-panel">
      <div class="project-heading">
        <h2>最近项目与下一步</h2>
        <el-button link type="primary" @click="router.push('/project/projects')">全部项目</el-button>
      </div>
      <el-alert v-if="projectError" :title="projectError" type="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="projects" empty-text="暂无项目，新建一份项目备案开始办理">
        <el-table-column label="项目名称" min-width="220">
          <template #default="{ row }">
            <el-button link type="primary" @click="openProject(row.id)">{{ row.name }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="建设单位" min-width="180" />
        <el-table-column label="办理阶段" width="140">
          <template #default="{ row }">
            <el-tag :type="stageType(row.stage)">{{ stageLabels[row.stage as ProjectStage] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下一步" min-width="220">
          <template #default="{ row }">{{ nextAction(row as Project) }}</template>
        </el-table-column>
      </el-table>
    </section>
    <section v-if="canReview" class="project-panel">
      <div class="project-heading">
        <h2>待审批申请</h2>
        <el-button link type="primary" @click="router.push('/project/approvals')">查看全部审批</el-button>
      </div>
      <el-alert v-if="approvalError" :title="approvalError" type="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="approvals" empty-text="暂无待审批申请">
        <el-table-column prop="projectName" label="项目名称" min-width="220" />
        <el-table-column label="申请事项" min-width="150">
          <template #default="{ row }">{{ applicationLabels[row.type as ApplicationType] }}</template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="180">
          <template #default="{ row }">{{ dateTime(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default>
            <el-button link type="primary" @click="router.push('/project/approvals')">查看审批</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <p class="project-note">{{ approvalRule }}。本工作台仅展示当前账号有权查看的业务数据。</p>
    <ProjectForm v-model="createVisible" @saved="created" />
  </div>
</template>
<script setup name="Index" lang="ts">
import type { ApplicationType, Project, ProjectApplication, ProjectDetail, ProjectStage } from '@/api/project/types';
import { listApprovals, listProjects } from '@/api/project';
import { useUserStore } from '@/store/modules/user';
import { checkPermi } from '@/utils/permission';
import ProjectForm from '@/views/project/components/ProjectForm.vue';
import { applicationLabels, approvalRule, dateTime, nextAction, stageLabels, stageType } from '@/views/project/shared';
import '@/views/project/project.scss';
const router = useRouter();
const user = useUserStore();
const canList = computed(() => checkPermi(['project:project:list']));
const canReview = computed(() => checkPermi(['project:approval:list']));
const projects = ref<Project[]>([]);
const approvals = ref<ProjectApplication[]>([]);
const projectTotal = ref<number | null>(null);
const approvalTotal = ref<number | null>(null);
const projectError = ref('');
const approvalError = ref('');
const loading = ref(false);
const createVisible = ref(false);
async function load() {
  if (loading.value) return;
  loading.value = true;
  projectError.value = '';
  approvalError.value = '';
  await Promise.all([
    canList.value
      ? listProjects({ pageNum: 1, pageSize: 6 })
          .then(({ data }) => {
            projects.value = data?.rows || [];
            projectTotal.value = data?.total ?? 0;
          })
          .catch(cause => {
            projects.value = [];
            projectTotal.value = null;
            projectError.value = cause instanceof Error ? cause.message : '项目数据加载失败';
          })
      : Promise.resolve(),
    canReview.value
      ? listApprovals({ pageNum: 1, pageSize: 5, status: 'SUBMITTED' })
          .then(({ data }) => {
            approvals.value = data?.rows || [];
            approvalTotal.value = data?.total ?? 0;
          })
          .catch(cause => {
            approvals.value = [];
            approvalTotal.value = null;
            approvalError.value = cause instanceof Error ? cause.message : '审批数据加载失败';
          })
      : Promise.resolve()
  ]);
  loading.value = false;
}
function openProject(id: string) {
  router.push('/project/detail/' + id);
}
function created(project: ProjectDetail) {
  openProject(project.id);
}
onMounted(load);
onActivated(load);
</script>
<style scoped>
.overview-grid {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) minmax(150px, 1fr) minmax(300px, 2fr);
  gap: 18px;
}
.overview {
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  gap: 16px;
}
.overview strong {
  font-size: 38px;
  font-weight: 650;
  color: var(--el-color-primary);
}
@media (max-width: 900px) {
  .overview-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .start-card {
    grid-column: 1 / -1;
  }
}
@media (max-width: 500px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
