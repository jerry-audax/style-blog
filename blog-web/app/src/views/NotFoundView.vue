<template>
  <section class="page-container missing-page">
    <p class="eyebrow">404 / NOT FOUND</p>
    <h1>这个页面暂时找不到。</h1>
    <p class="muted">可以返回博客继续阅读。</p>
    <div>
      <a href="/blog/" class="primary-btn">返回博客</a>
    </div>
  </section>
</template>
<style scoped>
.missing-page {
  padding-top: 80px;
  padding-bottom: 100px;
}

h1 {
  font-size: 32px;
}

.missing-page > div {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 28px;
}
</style>
