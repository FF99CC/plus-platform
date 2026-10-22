<template>
  <span class="app-logo" :style="maskStyle" aria-hidden="true" />
</template>

<script setup lang="ts">
const logoUrl = import.meta.env.VITE_APP_LOGO_URL;
const maskStyle = {
  maskImage: `url("${logoUrl}")`,
  WebkitMaskImage: `url("${logoUrl}")`
};
</script>

<style scoped>
.app-logo {
  display: inline-block;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  background-color: currentColor;
  mask-position: center;
  mask-repeat: no-repeat;
  mask-size: contain;
  -webkit-mask-position: center;
  -webkit-mask-repeat: no-repeat;
  -webkit-mask-size: contain;
}
</style>
