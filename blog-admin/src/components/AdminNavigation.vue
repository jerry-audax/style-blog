<template>
  <nav aria-label="管理导航" class="sidebar-nav">
    <section v-for="group in adminNavigation" :key="group.label" class="nav-group">
      <p class="nav-group-label">{{ group.label }}</p>
      <router-link v-for="item in group.items" :key="item.to" :to="item.to" class="nav-item"
                   active-class="nav-item--active" @click="$emit('navigate')">
        <el-icon aria-hidden="true"><component :is="icons[item.icon]"/></el-icon>
        <span>{{ item.label }}</span>
      </router-link>
    </section>
  </nav>
</template>
<script setup>
import {Odometer, Document, Collection, PriceTag, ChatLineSquare, Picture, Headset, VideoCamera, User, Operation} from '@element-plus/icons-vue'
import {adminNavigation} from '../config/navigation'
defineEmits(['navigate'])
const icons = {Odometer, Document, Collection, PriceTag, ChatLineSquare, Picture, Headset, VideoCamera, User, Operation}
</script>
