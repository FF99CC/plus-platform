import vue from '@vitejs/plugin-vue';
import createAutoImport from './auto-import';
import { viteCheckTransitionPlugin } from './check-transition';
import createComponents from './components';
import createCompression from './compression';
import createSetupExtend from './setup-extend';
import createSvgIconsPlugin from './svg-icon';
import createUnoCss from './unocss';

export default (viteEnv: any, isBuild = false): [] => {
  const vitePlugins: any = [];
  vitePlugins.push(vue());
  vitePlugins.push(createUnoCss());
  vitePlugins.push(createAutoImport());
  vitePlugins.push(createComponents());
  vitePlugins.push(createCompression(viteEnv));
  vitePlugins.push(createSvgIconsPlugin());
  vitePlugins.push(createSetupExtend());
  vitePlugins.push(viteCheckTransitionPlugin());
  return vitePlugins;
};
