<template>
  <div ref="container" class="warm-flow-designer-page">
    <el-collapse v-if="isProjectFlow" class="warm-flow-designer-page__guide">
      <el-collapse-item title="项目审批配置说明" name="guide">
        <p>可配置串行审批、条件分支和并行审批，在节点中选择办理人、角色、部门或岗位。所选人员还需有项目审批权限。</p>
        <p>修改已发布流程请先创建新版本草稿；新版本发布后用于新申请，进行中的审批继续使用原版本。</p>
        <p>提交后至少保留一个人工审批节点，不支持直接自动结束。退回会结束本次申请，由经办人修改后重新提交。</p>
        <el-table :data="projectVariables" size="small" border>
          <el-table-column prop="key" label="条件名" min-width="160" />
          <el-table-column prop="description" label="含义与取值" min-width="260" />
        </el-table>
        <p>条件变量取自本次提交的项目资料，金额单位为元；未填写的可选文字按空字符串、金额按 0 处理。</p>
        <p>
          示例：选中分支连线，将条件名设为 acquisitionMode，条件方式选“等于”，值填 TENDER（不加引号）；金额分支可将
          estimatedAmount 设为“大于等于”1000000，另一分支设为“小于”1000000。
        </p>
      </el-collapse-item>
    </el-collapse>
    <iframe ref="iframe" :src="iframeUrl" frameborder="0" class="warm-flow-designer-page__iframe"></iframe>
  </div>
</template>

<script setup name="WarmFlow" lang="ts">
import { onBeforeUnmount, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { getInfo } from '@/api/workflow/definition';
import tab from '@/plugins/tab';
import { getToken } from '@/utils/auth';

const route = useRoute();
// definitionId为需要查询的流程定义id，
// disabled为是否可编辑, 例如：查看的时候不可编辑，不可保存
const iframeUrl = ref('');
const baseUrl = import.meta.env.VITE_APP_BASE_API;
const iframe = ref<HTMLIFrameElement>();
const isProjectFlow = ref(false);
const projectVariables = [
  { key: 'applicationType', description: '申请类型：REGISTRATION（备案）、BID（投标）、INITIATION（立项）' },
  { key: 'acquisitionMode', description: '承接方式：DIRECT（直接委托）、TENDER（投标）' },
  { key: 'projectType', description: '项目类型' },
  { key: 'estimatedAmount', description: '预计金额（元）' },
  { key: 'bidType', description: '投标类型' },
  { key: 'bidAmount', description: '中标金额（元）' },
  { key: 'projectAmount', description: '立项金额（元）' }
];

const onDesignerMessage = (event: MessageEvent) => {
  if (!iframeUrl.value || event.source !== iframe.value?.contentWindow) {
    return;
  }
  if (event.origin !== new URL(iframeUrl.value, window.location.href).origin) {
    return;
  }
  if (event.data?.method === 'close') {
    close();
  }
};
const open = async (definitionId: unknown, disabled: unknown) => {
  const id = typeof definitionId === 'string' ? definitionId : '';
  const params = new URLSearchParams({
    id,
    onlyDesignShow: 'true',
    disabled: String(disabled === 'true' || disabled === true),
    Authorization: 'Bearer ' + getToken(),
    clientid: import.meta.env.VITE_APP_CLIENT_ID
  });
  iframeUrl.value = `${baseUrl}/warm-flow-ui/index.html?${params}`;
  isProjectFlow.value = false;
  if (id) {
    const { data } = await getInfo(id);
    isProjectFlow.value = data.flowCode?.startsWith('pm_') === true;
  }
};
/** 关闭按钮 */
function close() {
  const obj = {
    path: '/workflow/processDefinition',
    query: { activeName: route.query.activeName }
  };
  tab.closeOpenPage(obj);
}

onMounted(() => {
  window.addEventListener('message', onDesignerMessage);
  open(route.query.definitionId, route.query.disabled);
});

onBeforeUnmount(() => {
  window.removeEventListener('message', onDesignerMessage);
});
/**
 * 对外暴露子组件方法
 */
defineExpose({
  open
});
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

.warm-flow-designer-page__guide {
  padding: 0 16px;
  background: var(--el-bg-color);
  flex-shrink: 0;
}

.warm-flow-designer-page__guide :deep(.el-collapse-item__wrap) {
  max-height: 40vh;
  overflow-y: auto;
}

.warm-flow-designer-page__guide p {
  margin: 4px 0;
}
</style>
