<template>
  <div class="warm-flow-designer-page">
    <el-alert v-if="!canQuery" type="warning" :closable="false" title="您没有查看流程配置的权限。" />
    <template v-else-if="ready">
      <div v-if="isProjectFlow" class="designer-toolbar">
        <el-button :type="mode === 'config' ? 'primary' : 'default'" @click="showConfig(false)">条件与审批人</el-button>
        <el-button :type="mode === 'graph' ? 'primary' : 'default'" @click="showGraph">
          流程图（新增步骤和分支）
        </el-button>
        <span v-if="mode === 'graph'" class="designer-help">
          新增步骤和分支后，请使用图中的“保存”按钮；保存后返回中文配置。
        </span>
      </div>
      <ProjectFlowConfig
        v-if="isProjectFlow && mode === 'config'"
        ref="configPanel"
        :definition-id="definitionId"
        :disabled="readOnly"
        @graph="showGraph"
        @dirty="value => (configDirty = value)"
      />
      <iframe
        v-else
        ref="iframe"
        :src="iframeUrl"
        title="流程图设计器"
        frameborder="0"
        class="warm-flow-designer-page__iframe"
      ></iframe>
    </template>
  </div>
</template>
<script setup name="WarmFlow" lang="ts">
import { ElMessageBox } from 'element-plus';
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import { getInfo } from '@/api/workflow/definition';
import auth from '@/plugins/auth';
import tab from '@/plugins/tab';
import { getToken } from '@/utils/auth';
import ProjectFlowConfig from './components/ProjectFlowConfig.vue';
const route = useRoute();
const baseUrl = import.meta.env.VITE_APP_BASE_API;
const canQuery = auth.hasPermi('workflow:definition:query');
const canEdit = auth.hasPermi('workflow:definition:edit');
const iframeUrl = ref('');
const iframe = ref<HTMLIFrameElement>();
const configPanel = ref<InstanceType<typeof ProjectFlowConfig>>();
const definitionId = ref('');
const isProjectFlow = ref(false);
const readOnly = ref(true);
const ready = ref(false);
const mode = ref<'config' | 'graph'>('config');
const configDirty = ref(false);
function refreshIframe() {
  const params = new URLSearchParams({
    id: definitionId.value,
    onlyDesignShow: 'true',
    disabled: String(readOnly.value),
    Authorization: 'Bearer ' + getToken(),
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  });
  iframeUrl.value = `${baseUrl}/warm-flow-ui/index.html?${params}`;
}
async function confirmGraphLeave() {
  if (readOnly.value) return true;
  try {
    await ElMessageBox.confirm('请先使用流程图内的“保存”按钮。直接离开将放弃图中未保存的修改。', '离开流程图', {
      confirmButtonText: '放弃未保存修改并离开',
      cancelButtonText: '返回流程图',
      type: 'warning'
    });
    return true;
  } catch {
    return false;
  }
}
async function showGraph() {
  if (mode.value === 'graph') return;
  if (configPanel.value && !(await configPanel.value.beforeLeave())) return;
  configDirty.value = false;
  refreshIframe();
  mode.value = 'graph';
}
async function showConfig(fromDesigner: boolean) {
  if (mode.value === 'config') return;
  if (!fromDesigner && !(await confirmGraphLeave())) return;
  mode.value = 'config';
}
function onDesignerMessage(event: MessageEvent) {
  if (
    !iframeUrl.value ||
    event.source !== iframe.value?.contentWindow ||
    event.origin !== new URL(iframeUrl.value, window.location.href).origin
  )
    return;
  if (event.data?.method === 'close') {
    if (isProjectFlow.value) showConfig(true);
    else tab.closeOpenPage({ path: '/workflow/processDefinition', query: { activeName: route.query.activeName } });
  }
}
async function open(id: unknown, disabled: unknown) {
  if (!canQuery) return;
  ready.value = false;
  definitionId.value = typeof id === 'string' ? id : '';
  readOnly.value = disabled === 'true' || disabled === true || !canEdit;
  isProjectFlow.value = false;
  if (definitionId.value) {
    const { data } = await getInfo(definitionId.value);
    isProjectFlow.value = data.flowCode?.startsWith('pm_') === true;
    readOnly.value = readOnly.value || Number(data.isPublish) !== 0;
  }
  mode.value = isProjectFlow.value ? 'config' : 'graph';
  refreshIframe();
  ready.value = true;
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (configDirty.value || (isProjectFlow.value && mode.value === 'graph' && !readOnly.value)) {
    event.preventDefault();
    event.returnValue = '';
  }
}
onBeforeRouteLeave(async () => {
  if (!isProjectFlow.value) return true;
  if (mode.value === 'config') return (await configPanel.value?.beforeLeave()) ?? true;
  return await confirmGraphLeave();
});
onMounted(() => {
  window.addEventListener('message', onDesignerMessage);
  window.addEventListener('beforeunload', beforeUnload);
  open(route.query.definitionId, route.query.disabled);
});
onBeforeUnmount(() => {
  window.removeEventListener('message', onDesignerMessage);
  window.removeEventListener('beforeunload', beforeUnload);
});
defineExpose({ open });
</script>
<style lang="scss" scoped>
.warm-flow-designer-page {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: calc(100vh - 123px);
  overflow: hidden;
}
.warm-flow-designer-page__iframe {
  display: block;
  width: 100%;
  flex: 1;
  min-height: 0;
}
.designer-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--el-border-color);
}
.designer-help {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
