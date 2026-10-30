<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ArrowRight, ArrowUpRight, Bike, Check, CheckCircle2, CircleAlert, ChevronUp } from 'lucide-vue-next'
import RiderMap from './RiderMap.vue'

const state = ref(null)
const regions = ref([])
const walletInfo = ref(null)
const token = ref(localStorage.getItem('cycleflow_rider_token') || '')
const user = ref(JSON.parse(localStorage.getItem('cycleflow_rider_user') || 'null'))
const loginName = ref('rider01'), loginPassword = ref('123456'), loginError = ref('')
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
const balance = computed(() => ((walletInfo.value?.balanceCents || 0) / 100).toFixed(2))
const walletTypeNames = { REWARD: '奖励到账', TOPUP: '充值', WITHDRAW: '提现' }
const offers = computed(() => state.value?.offers || [])
const loggedIn = computed(() => !!token.value)
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

async function api(path, method = 'GET', body) {
  const headers = {}
  if (body) headers['Content-Type'] = 'application/json'
  if (token.value) headers['X-Rider-Token'] = token.value
  const response = await fetch('/api' + path, { method, headers, body: body ? JSON.stringify(body) : undefined })
  const result = await response.json()
  if (!response.ok) {
    const error = new Error(result.detail || result.message || '操作失败，请稍后重试')
    error.status = response.status
    throw error
  }
  return result
}

function clearSession() {
  token.value = ''
  user.value = null
  state.value = null
  walletInfo.value = null
  localStorage.removeItem('cycleflow_rider_token')
  localStorage.removeItem('cycleflow_rider_user')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const snapshot = await api('/state')
    regions.value = snapshot.regions
    state.value = token.value ? await api('/rider/state') : null
    walletInfo.value = token.value ? await api('/rider/wallet') : null
  } catch (e) {
    if (e.status === 401) clearSession()
    else error.value = e.message
  } finally {
    loading.value = false
    measure()
    if (!token.value) expanded.value = true
  }
}

async function login() {
  if (busy.value) return
  busy.value = true
  loginError.value = ''
  try {
    const response = await fetch('/api/rider/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: loginName.value.trim(), password: loginPassword.value }),
    })
    const result = await response.json()
    if (!response.ok) throw new Error(result.detail || result.message || '登录失败')
    token.value = result.token
    user.value = result.user
    localStorage.setItem('cycleflow_rider_token', result.token)
    localStorage.setItem('cycleflow_rider_user', JSON.stringify(result.user))
    await load()
    expanded.value = false
    notify('欢迎回来，' + result.user.nickname)
  } catch (e) { loginError.value = e.message }
  finally { busy.value = false }
}

async function walletAction(kind) {
  if (busy.value) return
  busy.value = true
  try {
    const cents = kind === 'topup' ? 1000 : 500
    walletInfo.value = await api(`/rider/wallet/${kind}`, 'POST', { amountCents: cents })
    if (state.value) state.value.walletCents = walletInfo.value.balanceCents
    notify(kind === 'topup' ? '充值 ¥10 已到账' : '提现 ¥5 已提交')
  } catch (e) { notify(e.message) }
  finally { busy.value = false }
}

async function logout() {
  try { await api('/rider/logout', 'POST') } catch { /* ignore */ }
  clearSession()
  expanded.value = true
  notify('已退出登录')
}

async function accept(offerId) {
  if (busy.value) return
  busy.value = true
  try {
    state.value = await api(`/rider/accept/${offerId}`, 'POST')
    selectedOffer.value = offerId
    expanded.value = true
    notify('接单成功，骑到目的站点后完成骑行')
  } catch (e) { if (e.status === 401) clearSession(); else notify(e.message) }
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
  } catch (e) { if (e.status === 401) clearSession(); else notify(e.message) }
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
      <div class="rider-brand"><span class="rider-mark"><Bike :size="18"/></span><div><strong>交点</strong><small>{{ user ? user.nickname : '骑行赚奖励' }}</small></div></div>
      <div class="rider-top-actions"><button v-if="loggedIn" class="rider-console" @click="logout">退出</button><a class="rider-console" href="/">控制台 <ArrowUpRight :size="13"/></a></div>
    </header>

    <div class="rider-wallet-chip"><template v-if="loggedIn"><strong>¥{{ wallet }}</strong><span>{{ state?.completedRides || 0 }} 次顺路调度</span></template><template v-else><strong>未登录</strong><span>登录后接单赚奖励</span></template></div>

    <section class="rider-sheet" :class="{ expanded }" :style="sheetStyle">
      <div class="rider-handle" @pointerdown="startDrag" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag">
        <span class="rider-grabber"></span>
      </div>

      <div class="rider-sheet-body">
        <div v-if="loading" class="rider-loading"><div class="rider-skeleton"></div><div class="rider-skeleton short"></div></div>
        <div v-else-if="error" class="rider-error"><CircleAlert :size="28"/><strong>暂时连不上调度服务</strong><p>{{ error }}</p><button class="rider-btn primary" @click="load">重新加载</button></div>

        <div v-else-if="!loggedIn" class="rider-login">
          <h1>登录后接单赚奖励</h1>
          <p>演示账户 rider01–rider04，密码统一为 123456。</p>
          <label>账号<input v-model="loginName" autocomplete="username" placeholder="rider01"/></label>
          <label>密码<input v-model="loginPassword" type="password" autocomplete="current-password" placeholder="123456" @keyup.enter="login"/></label>
          <p v-if="loginError" class="rider-login-error">{{ loginError }}</p>
          <button class="rider-btn primary" :disabled="busy" @click="login">{{ busy ? '登录中…' : '登录' }}</button>
          <div class="rider-demo-accounts"><button v-for="name in ['rider01','rider02','rider03','rider04']" :key="name" @click="loginName = name; loginPassword = '123456'">{{ name }}</button></div>
        </div>

        <template v-else>
          <article class="rider-wallet-panel">
            <div><span>可用余额</span><strong>¥{{ balance }}</strong></div>
            <div class="rider-wallet-actions"><button class="rider-btn" :disabled="busy" @click="walletAction('topup')">充值 ¥10</button><button class="rider-btn" :disabled="busy" @click="walletAction('withdraw')">提现 ¥5</button></div>
          </article>
          <div v-if="!state?.activeRide" class="rider-sheet-head"><div><h1>附近可接的调度悬赏</h1><p>把车骑到缺车点，完成后奖励到账</p></div><span>{{ offers.length }}</span></div>

          <article v-if="state?.activeRide" class="rider-active">
            <div class="rider-active-head"><span>进行中</span><strong>+¥{{ (state.activeRide.amountCents / 100).toFixed(1) }}</strong></div>
            <div class="rider-route"><strong>{{ regionName(state.activeRide.fromId) }}</strong><ArrowRight :size="14"/><strong>{{ regionName(state.activeRide.toId) }}</strong></div>
            <button class="rider-btn primary" :disabled="busy" @click="complete(state.activeRide.id)"><Check :size="15"/>{{ busy ? '处理中…' : '完成骑行并领取奖励' }}</button>
          </article>

          <div v-if="!state?.activeRide && !offers.length" class="rider-empty"><CheckCircle2 :size="26"/><strong>当前供需已平衡</strong><p>稍后再来看看</p></div>
          <div v-else-if="!state?.activeRide" class="rider-offers">
            <article v-for="offer in offers" :key="offer.id" :class="['rider-offer', { selected: selectedOffer === offer.id }]" @click="selectOffer(offer)">
              <div class="rider-route"><strong>{{ regionName(offer.fromId) }}</strong><ArrowRight :size="14"/><strong>{{ regionName(offer.toId) }}</strong></div>
              <div class="rider-offer-foot"><span>{{ offer.km.toFixed(1) }} km</span><strong>+¥{{ (offer.amountCents / 100).toFixed(1) }}</strong></div>
              <button class="rider-btn primary" :disabled="busy || !!state.activeRide" @click.stop="accept(offer.id)">{{ state.activeRide ? '骑行中' : '接单' }}</button>
            </article>
          </div>

          <template v-if="walletInfo?.entries?.length">
            <div class="rider-section"><h2>钱包流水</h2><span>余额 ¥{{ balance }}</span></div>
            <div class="rider-history">
              <div v-for="entry in walletInfo.entries" :key="entry.id" class="rider-history-row">
                <span>{{ walletTypeNames[entry.type] || entry.type }}<template v-if="entry.note"> · {{ entry.note }}</template></span>
                <strong :class="{ negative: entry.type === 'WITHDRAW' }">{{ entry.type === 'WITHDRAW' ? '-' : '+' }}¥{{ (entry.amountCents / 100).toFixed(1) }}</strong>
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
