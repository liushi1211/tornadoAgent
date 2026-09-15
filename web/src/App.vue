<script setup lang="ts">
// 根组件：仅承载路由出口；登录页与主框架（MainLayout）由 router 决定。
</script>

<template>
  <router-view />
</template>

<style>
:root {
  --saa-bg: #f5f7fa;
  --saa-sidebar-bg: #1f2d3d;
  --saa-sidebar-text: #c0ccda;
}
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
}
body {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', Arial, sans-serif;
  background: var(--saa-bg);
  color: #303133;
}
* {
  box-sizing: border-box;
}
</style>
