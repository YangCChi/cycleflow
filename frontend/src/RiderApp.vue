<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ArrowRight, ArrowUpRight, Bike, Check, CheckCircle2, CircleAlert } from 'lucide-vue-next'

const state = ref(null)
const regions = ref([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const toast = ref('')
let toastTimer

const regionName = id => regions.value.find(region => region.id === id)?.name || '站点 ' + id

const wallet = computed(() => ((state.value?.walletCents || 0) / 100).toFixed(2))

function notify(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 4000)
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
  finally { loading.value = false }
}

async function accept(offerId) {
  if (busy.value) return
  busy.value = true
  try {
    state.value = await api(`/rider/accept/${offerId}`, 'POST')
    notify('接单成功，到达目的地后点击「完成骑行」领取奖励')
  } catch (e) { notify(e.message) }
  finally { busy.value = false }
}

async function complete(rideId) {
  if (busy.value) return
  busy.value = true
  try {
    const before = state.value?.walletCents || 0
    state.value = await api(`/rider/complete/${rideId}`, 'POST')
    notify(`骑行完成，奖励 ¥${((state.value.walletCents - before) / 100).toFixed(1)} 已到账`)
  } catch (e) { notify(e.message) }
  finally { busy.value = false }
}

onMounted(load)
onUnmounted(() => clearTimeout(toastTimer))
</script>

<template>
  <div class="rider-shell">
    <header class="rider-top">
      <div class="rider-brand"><span class="rider-mark"><Bike :size="20"/></span><div><strong>交点</strong><small>骑行赚奖励</small></div></div>
      <a class="rider-console" href="/">运营控制台 <ArrowUpRight :size="14"/></a>
    </header>

    <div v-if="loading" class="rider-loading">
      <div class="rider-skeleton wallet"></div>
      <div class="rider-skeleton line"></div>
      <div class="rider-skeleton card"></div>
    </div>

    <div v-else-if="error" class="rider-error">
      <CircleAlert :size="32"/><strong>暂时连不上调度服务</strong><p>{{ error }}</p>
      <button class="rider-btn primary" @click="load">重新加载</button>
    </div>

    <main v-else>
      <section class="rider-wallet">
        <span class="rider-eyebrow">MY REWARDS</span>
        <strong>¥{{ wallet }}</strong>
        <p>累计完成 {{ state?.completedRides || 0 }} 次顺路调度</p>
      </section>

      <p class="rider-intro">从富余站点取车，骑到缺车站点还车，完成后奖励自动到账。</p>

      <article v-if="state?.activeRide" class="rider-active">
        <div class="rider-active-head"><span class="rider-eyebrow">RIDE IN PROGRESS</span><strong>+¥{{ (state.activeRide.amountCents / 100).toFixed(1) }}</strong></div>
        <div class="rider-route"><strong>{{ regionName(state.activeRide.fromId) }}</strong><span class="rider-route-arrow"><ArrowRight :size="14"/></span><strong>{{ regionName(state.activeRide.toId) }}</strong></div>
        <p>到达目的站点后还车，奖励立即到账。</p>
        <button class="rider-btn primary" :disabled="busy" @click="complete(state.activeRide.id)"><Check :size="16"/>{{ busy ? '处理中…' : '完成骑行并领取奖励' }}</button>
      </article>

      <div class="rider-section"><h2>附近可接的调度悬赏</h2><span>{{ state?.offers.length || 0 }}</span></div>

      <div v-if="!state?.offers.length" class="rider-empty">
        <CheckCircle2 :size="30"/><strong>当前供需已平衡</strong><p>没有需要顺路搬运的车辆，稍后再来看看。</p>
      </div>

      <div v-else class="rider-offers">
        <button v-for="offer in state.offers" :key="offer.id" class="rider-offer" :disabled="busy || !!state.activeRide" @click="accept(offer.id)">
          <div class="rider-route"><strong>{{ regionName(offer.fromId) }}</strong><span class="rider-route-arrow"><ArrowRight :size="14"/></span><strong>{{ regionName(offer.toId) }}</strong></div>
          <div class="rider-offer-foot"><span>{{ offer.km.toFixed(1) }} km</span><strong>+¥{{ (offer.amountCents / 100).toFixed(1) }}</strong></div>
        </button>
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
    </main>

    <footer class="rider-foot">交点 CycleFlow · 真实地图背景 / 模拟运营数据</footer>
    <div v-if="toast" class="rider-toast">{{ toast }}</div>
  </div>
</template>
