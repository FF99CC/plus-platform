<template>
  <el-select
    :model-value="modelValue || undefined"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :placeholder="placeholder"
    :disabled="disabled"
    class="handler-picker"
    @visible-change="visible => visible && search('')"
    @update:model-value="value => emit('update:modelValue', value || '')"
  >
    <template #label>{{ selectedName }}</template>
    <el-option
      v-for="item in options"
      :key="item.storageId"
      :label="item.handlerName || '已选择（名称暂不可用）'"
      :value="optionValue(item.storageId)"
    >
      <span>{{ item.handlerName || '名称暂不可用' }}</span>
      <span v-if="item.groupName" class="handler-picker__group">{{ item.groupName }}</span>
    </el-option>
  </el-select>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { handlerNames, queryHandlers, type HandlerOption } from '@/api/workflow/definition/designer';
import { assigneeTypes } from './projectFlowModel';
const props = withDefaults(
  defineProps<{ modelValue: string; type: string; raw?: boolean; disabled?: boolean; placeholder?: string }>(),
  { placeholder: '输入名称搜索并选择' }
);
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const options = ref<HandlerOption[]>([]);
const loading = ref(false);
// ElSelect intentionally does not refresh cached selected labels when remote option labels change.
// Its public label slot keeps feedback names reactive without recreating the focused selector.
const selectedName = computed(
  () =>
    options.value.find(item => optionValue(item.storageId) === props.modelValue)?.handlerName ||
    '已选择（名称暂不可用）'
);
let requestSequence = 0;
function optionValue(value: string) {
  return props.raw ? value.replace(/^(dept|role|post):/, '') : value;
}
function storageValue() {
  return props.raw && props.type !== 'user' && props.modelValue
    ? `${props.type}:${props.modelValue}`
    : props.modelValue;
}
async function search(name: string) {
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const { data } = await queryHandlers({
      handlerType: assigneeTypes.find(item => item.value === props.type)?.handlerType || '用户',
      handlerName: name,
      pageNum: 1,
      pageSize: 50
    });
    if (sequence !== requestSequence) return;
    const selected = options.value.find(item => optionValue(item.storageId) === props.modelValue);
    options.value = [
      ...(selected ? [selected] : []),
      ...(data.handlerAuths?.rows || []).filter(item => item.storageId !== selected?.storageId)
    ];
  } catch {
    // The request layer displays the error; retain the existing selection.
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}
watch(
  () => props.type,
  () => {
    options.value = [];
    requestSequence++;
    loading.value = false;
  }
);
watch(
  () => [props.modelValue, props.type],
  async () => {
    const selectedId = storageValue();
    if (!selectedId || options.value.some(item => item.storageId === selectedId)) return;
    options.value.push({ storageId: selectedId, handlerName: '正在读取名称…' });
    try {
      const { data } = await handlerNames([selectedId]);
      if (selectedId === storageValue()) {
        const selected = data.find(item => item.storageId === selectedId);
        options.value = options.value.map(item =>
          item.storageId === selectedId
            ? { storageId: selectedId, handlerName: selected?.handlerName || '已选择（名称暂不可用）' }
            : item
        );
      }
    } catch {
      options.value = options.value.map(item =>
        item.storageId === selectedId ? { ...item, handlerName: '已选择（名称暂不可用）' } : item
      );
    }
  },
  { immediate: true }
);
</script>
<style scoped>
.handler-picker {
  width: 100%;
  min-width: 180px;
}
.handler-picker__group {
  color: var(--el-text-color-secondary);
  margin-left: 16px;
  font-size: 12px;
}
</style>
