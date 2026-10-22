import { parse, compileScript } from '@vue/compiler-sfc';
import { ElSelect, ElOption } from 'element-plus/es';
import { expect, it, vi } from 'vitest';
import { createRenderer, defineComponent, h, nextTick, ssrContextKey, compile } from 'vue';
import HandlerPicker from './HandlerPicker.vue';
import source from './HandlerPicker.vue?raw';
const { descriptor } = parse(source);
HandlerPicker.render = compile(descriptor.template!.content, {
  bindingMetadata: compileScript(descriptor, { id: 'picker-test' }).bindings,
  prefixIdentifiers: true
});

const { feedback } = vi.hoisted(() => ({ feedback: vi.fn() }));
vi.mock('@/api/workflow/definition/designer', () => ({ handlerNames: feedback, queryHandlers: vi.fn() }));
vi.mock('element-plus/es', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    // Remote ElSelect caches its first selected label. Its public label slot is reactive.
    ElSelect: defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('selected', slots.label?.({ label: '正在读取名称…' }) || '正在读取名称…')
    }),
    ElOption: defineComponent({ setup: () => () => null })
  };
});
interface HostNode {
  text: string;
  children: HostNode[];
}
const renderer = createRenderer<HostNode, HostNode>({
  createElement: () => ({ text: '', children: [] }),
  createText: text => ({ text, children: [] }),
  createComment: () => ({ text: '', children: [] }),
  setText: (node, text) => {
    node.text = text;
  },
  setElementText: (node, text) => {
    node.text = text;
  },
  insert: (child, parent) => {
    parent.children.push(child);
  },
  remove: () => {},
  parentNode: () => null,
  nextSibling: () => null,
  patchProp: () => {}
});
const text = (node: HostNode): string => node.text + node.children.map(text).join('');
it('updates the visible selected name when feedback resolves after the remote label was cached', async () => {
  let resolveFeedback!: (value: unknown) => void;
  feedback.mockReturnValue(
    new Promise(resolve => {
      resolveFeedback = resolve;
    })
  );
  const host: HostNode = { text: '', children: [] };
  const app = renderer.createApp(
    defineComponent({ setup: () => () => h(HandlerPicker, { modelValue: 'role:800000000000000002', type: 'role' }) })
  );
  app.component('el-select', ElSelect);
  app.component('el-option', ElOption);
  app.provide(ssrContextKey, { modules: new Set() });
  app.mount(host);
  expect(text(host)).toContain('正在读取名称');
  resolveFeedback({ data: [{ storageId: 'role:800000000000000002', handlerName: '审批负责人' }] });
  await Promise.resolve();
  await nextTick();
  expect(text(host)).toContain('审批负责人');
  expect(text(host)).not.toContain('800000000000000002');
  app.unmount();
});
