<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ArrowRight, ArrowUpRight, Bike, Check, CheckCircle2, CircleAlert, ChevronUp } from 'lucide-vue-next'
import RiderMap from './RiderMap.vue'

const state = ref(null)
const regions = ref([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const toast = ref('')
const selectedOffer = ref('')
const expanded = ref(false)
const appRef = ref(null)
const dragY = ref(0)
const dragging = ref(false)
const collapsedOffset = ref(0)
let toastTimer, startY = 0, startExpanded = false, moved = false

const wallet = computed(() => ((state.value?.walletCents || 0) / 100).toFixed(2))
const offers = computed(() => state.value?.offers || [])
const regionName = id => regions.value.find(region => region.id === id)?.name || '站点 ' + id
const sheetStyle = computed(() => ({
  transform: `translateY(${dragging.value ? dragY.value : (expanded.value ? 0 : collapsedOffset.value)}px)`,
  transition: dragging.value ? 'none' : 'transform .26s cubic-bezier(.2,.8,.2,1)',
}))

function notify(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 4000)
}

function measure() {
  const height = appRef.value?.clientHeight || window.innerHeight
  collapsedOffset.value = Math.round(height * 0.34)
}

function startDrag(event) {
  dragging.value = true; moved = false
  startY = event.clientY
  startExpanded = expanded.value
  dragY.value = startExpanded ? 0 : collapsedOffset.value
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function moveDrag(event) {
  if (!dragging.value) return
  const delta = event.clientY - startY
  if (Math.abs(delta) > 4) moved = true
  const base = startExpanded ? 0 : collapsedOffset.value
  dragY.value = Math.min(collapsedOffset.value, Math.max(0, base + delta))
}

function endDrag() {
  if (!dragging.value) return
  dragging.value = false
  if (!moved) expanded.value = !expanded.value
  else expanded.value = dragY.value < collapsedOffset.value * 0.45
}

async function api(path, method = 'GET') {
  const response = await fetch('/api' + path, { method })
  const result = await response.json()
  if (!response.ok) throw new Error(result.detail || result.message || '操作失败，请稍后重试')
  return result
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [riderState, snapshot] = await Promise.all([api('/rider/state'), api('/state')])
    state.value = riderState
    regions.value = snapshot.regions
  } catch (e) { error.value = e.message }
  finally { loading.value = false; measure() }
}

async function accept(offerId) {
  if (busy.value) return
  busy.value = true
  try {
    state.value = await api(`/rider/accept/${offerId}`, 'POST')
    selectedOffer.value = offerId
    expanded.value = true
    notify('接单成功，骑到目的站点后完成骑行')
  } catch (e) { notify(e.message) }
  finally { busy.value = false }
}

async function complete(rideId) {
  if (busy.value) return
  busy.value = true
  try {
    const before = state.value?.walletCents || 0
    state.value = await api(`/rider/complete/${rideId}`, 'POST')
    selectedOffer.value = ''
    notify(`骑行完成，奖励 ¥${((state.value.walletCents - before) / 100).toFixed(1)} 已到账`)
  } catch (e) { notify(e.message) }
  finally { busy.value = false }
}

function selectOffer(offer) {
  selectedOffer.value = offer.id
  expanded.value = false
}

onMounted(() => { load(); window.addEventListener('resize', measure) })
onUnmounted(() => { clearTimeout(toastTimer); window.removeEventListener('resize', measure) })
</script>

<template>
  <div ref="appRef" class="rider-app">
    <RiderMap :regions="regions" :offers="offers" :active-ride="state?.activeRide" :selected="selectedOffer" />
    <div class="rider-map-fade"></div>

    <header class="rider-top">
      <div class="rider-brand"><span class="rider-mark"><Bike :size="18"/></span><div><strong>交点</strong><small>骑行赚奖励</small></div></div>
      <a class="rider-console" href="/">控制台 <ArrowUpRight :size="13"/></a>
    </header>

    <div class="rider-wallet-chip"><strong>¥{{ wallet }}</strong><span>{{ state?.completedRides || 0 }} 次顺路调度</span></div>

    <section class="rider-sheet" :class="{ expanded }" :style="sheetStyle">
      <div class="rider-handle" @pointerdown="startDrag" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag">
        <span class="rider-grabber"></span>
      </div>

      <div class="rider-sheet-body">
        <div v-if="loading" class="rider-loading"><div class="rider-skeleton"></div><div class="rider-skeleton short"></div></div>
        <div v-else-if="error" class="rider-error"><CircleAlert :size="28"/><strong>暂时连不上调度服务</strong><p>{{ error }}</p><button class="rider-btn primary" @click="load">重新加载</button></div>

        <template v-else>
          <div class="rider-sheet-head"><div><h1>附近可接的调度悬赏</h1><p>把车骑到缺车点，完成后奖励到账</p></div><span>{{ offers.length }}</span></div>

          <article v-if="state?.activeRide" class="rider-active">
            <div class="rider-active-head"><span>进行中</span><strong>+¥{{ (state.activeRide.amountCents / 100).toFixed(1) }}</strong></div>
            <div class="rider-route"><strong>{{ regionName(state.activeRide.fromId) }}</strong><ArrowRight :size="14"/><strong>{{ regionName(state.activeRide.toId) }}</strong></div>
            <button class="rider-btn primary" :disabled="busy" @click="complete(state.activeRide.id)"><Check :size="15"/>{{ busy ? '处理中…' : '完成骑行并领取奖励' }}</button>
          </article>

          <div v-if="!offers.length" class="rider-empty"><CheckCircle2 :size="26"/><strong>当前供需已平衡</strong><p>稍后再来看看</p></div>
          <div v-else class="rider-offers">
            <article v-for="offer in offers" :key="offer.id" :class="['rider-offer', { selected: selectedOffer === offer.id }]" @click="selectOffer(offer)">
              <div class="rider-route"><strong>{{ regionName(offer.fromId) }}</strong><ArrowRight :size="14"/><strong>{{ regionName(offer.toId) }}</strong></div>
              <div class="rider-offer-foot"><span>{{ offer.km.toFixed(1) }} km</span><strong>+¥{{ (offer.amountCents / 100).toFixed(1) }}</strong></div>
              <button class="rider-btn primary" :disabled="busy || !!state.activeRide" @click.stop="accept(offer.id)">{{ state.activeRide ? '骑行中' : '接单' }}</button>
            </article>
          </div>

          <template v-if="state?.history.length">
            <div class="rider-section"><h2>最近完成</h2><span>{{ state.completedRides }} 次</span></div>
            <div class="rider-history">
              <div v-for="ride in state.history" :key="ride.id" class="rider-history-row">
                <span>{{ regionName(ride.fromId) }} <ArrowRight :size="12"/> {{ regionName(ride.toId) }}</span>
                <strong>+¥{{ (ride.amountCents / 100).toFixed(1) }}</strong>
              </div>
            </div>
          </template>
          <p class="rider-credit">地图数据 © 高德地图</p>
        </template>
      </div>

      <button class="rider-expand" :aria-label="expanded ? '收起菜单' : '展开菜单'" @click="expanded = !expanded"><ChevronUp :size="16" :style="{ transform: expanded ? 'rotate(180deg)' : 'none' }"/></button>
    </section>

    <div v-if="toast" class="rider-toast">{{ toast }}</div>
  </div>
</template>
