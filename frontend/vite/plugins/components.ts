import { resolve } from 'path';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import Components from 'unplugin-vue-components/vite';

export default () => {
  return Components({
    resolvers: [
      // 自动导入 Element Plus 组件
      ElementPlusResolver({
        importStyle: false
      })
    ],
    dts: resolve(import.meta.dirname, '../../src/types/components.d.ts')
  });
};
