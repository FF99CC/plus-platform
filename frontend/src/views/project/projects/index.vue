<template>
  <div class="project-page">
    <header class="project-heading">
      <div>
        <h1>项目办理</h1>
        <p>从项目备案开始，办理投标或直接委托项目的施工立项。</p>
      </div>
      <el-button v-hasPermi="['project:project:add']" type="primary" @click="createVisible = true">
        新建项目备案
      </el-button>
    </header>
    <section class="project-panel">
      <el-form class="project-filters" @submit.prevent="search">
        <el-input
          v-model="query.search"
          placeholder="搜索项目名称、建设单位"
          clearable
          aria-label="搜索项目"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="query.stage" placeholder="全部阶段" clearable aria-label="项目阶段" @change="search">
          <el-option v-for="(label, value) in stageLabels" :key="value" :value="value" :label="label" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        <el-button :disabled="loading" @click="reset">重置</el-button>
      </el-form>
      <el-alert v-if="error" class="project-error" :title="error" type="error" :closable="false" show-icon>
        <el-button link @click="load">重新加载</el-button>
      </el-alert>
      <el-table v-loading="loading" :data="projects" empty-text="暂无项目，点击“新建项目备案”开始办理" row-key="id">
        <el-table-column label="项目名称" min-width="210">
          <template #default="{ row }">
            <el-button link type="primary" @click="openProject(row.id)">{{ row.name }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="建设单位" min-width="170" show-overflow-tooltip />
        <el-table-column label="承接方式" width="110">
          <template #default="{ row }">{{ modeLabel(row.acquisitionMode) }}</template>
        </el-table-column>
        <el-table-column label="办理阶段" width="130">
          <template #default="{ row }">
            <el-tag :type="stageType(row.stage)">{{ stageLabels[row.stage as ProjectStage] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下一步" min-width="210">
          <template #default="{ row }">{{ nextAction(row as Project) }}</template>
        </el-table-column>
        <el-table-column prop="ownerName" label="经办人" width="110" />
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ dateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openProject(row.id)">查看办理</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="project-pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </section>
    <p class="project-note">{{ approvalRule }}。仅显示当前账号有权查看的项目。</p>
    <ProjectForm v-model="createVisible" @saved="created" />
  </div>
</template>
<script setup name="ProjectProjects" lang="ts">
import type { Project, ProjectDetail, ProjectQuery, ProjectStage } from '@/api/project/types';
import { listProjects } from '@/api/project';
import ProjectForm from '../components/ProjectForm.vue';
import { approvalRule, dateTime, modeLabel, nextAction, stageLabels, stageType } from '../shared';
import '../project.scss';
const router = useRouter();
const projects = ref<Project[]>([]);
const query = reactive<ProjectQuery>({ pageNum: 1, pageSize: 20, search: '', stage: '' });
const total = ref(0);
const loading = ref(false);
const error = ref('');
const createVisible = ref(false);
let requestNumber = 0;
async function load() {
  const current = ++requestNumber;
  loading.value = true;
  error.value = '';
  try {
    const { data } = await listProjects({ ...query, search: query.search?.trim() });
    if (current === requestNumber) {
      projects.value = data?.rows || [];
      total.value = data?.total || 0;
    }
  } catch (cause) {
    if (current === requestNumber) {
      projects.value = [];
      total.value = 0;
      error.value = cause instanceof Error ? cause.message : '项目加载失败，请重试';
    }
  } finally {
    if (current === requestNumber) loading.value = false;
  }
}
function search() {
  query.pageNum = 1;
  load();
}
function reset() {
  query.search = '';
  query.stage = '';
  search();
}
function openProject(id: string) {
  router.push(`/project/detail/${id}`);
}
function created(project: ProjectDetail) {
  load();
  openProject(project.id);
}
onMounted(load);
onActivated(load);
</script>
