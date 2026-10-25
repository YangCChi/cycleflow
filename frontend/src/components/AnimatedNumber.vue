<script setup>
import { onUnmounted, ref, watch } from 'vue'
const props = defineProps({ value: { type: Number, default: 0 }, duration: { type: Number, default: 450 } })
const display = ref(props.value)
let frame
const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
watch(() => props.value, (to, from) => {
  cancelAnimationFrame(frame)
  if (reduced) { display.value = to; return }
  const begin = Number(from) || 0
  const started = performance.now()
  const step = now => {
    const k = Math.min(1, (now - started) / props.duration)
    const eased = 1 - Math.pow(1 - k, 3)
    display.value = Math.round(begin + (to - begin) * eased)
    if (k < 1) frame = requestAnimationFrame(step)
  }
  frame = requestAnimationFrame(step)
})
onUnmounted(() => cancelAnimationFrame(frame))
</script>

<template><span>{{ display }}</span></template>
