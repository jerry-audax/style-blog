<template>
  <component :is="icons[name] || PhCode" :size="size" aria-hidden="true"/>
</template>
<script setup>
import {
  PhCode,
  PhArrowRight,
  PhMoon,
  PhSun,
  PhList,
  PhX,
  PhRobot,
  PhBookOpen,
  PhUser,
  PhStack,
  PhArrowUp,
  PhCheck,
  PhCamera,
  PhCaretDown,
} from '@phosphor-icons/vue'

defineProps({name: String, size: {type: [Number, String], default: 20}})
const icons = {
  code: PhCode,
  right: PhArrowRight,
  moon: PhMoon,
  sun: PhSun,
  menu: PhList,
  close: PhX,
  robot: PhRobot,
  book: PhBookOpen,
  user: PhUser,
  stack: PhStack,
  up: PhArrowUp,
  check: PhCheck,
  camera: PhCamera,
  down: PhCaretDown,
}
</script>
