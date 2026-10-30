<script setup>
import { computed, ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { Bike, LayoutDashboard, MapPin, ChartNoAxesCombined, Route, FlaskConical, ArrowUpRight, ArrowRight, ChevronRight, Search, SlidersHorizontal, RefreshCw, Play, Pause, Download, X, Check, Clock3, Truck, CircleAlert, Info, PanelLeftClose, Menu, Activity, Settings2, CheckCircle2, History, ChevronDown, Database } from 'lucide-vue-next'
import RegionMap from './components/RegionMap.vue'
import AnimatedNumber from './components/AnimatedNumber.vue'

const tabs = [
  { id: 'overview', label: '运营总览', icon: LayoutDashboard, en: 'OVERVIEW' },
  { id: 'regions', label: '停车区域', icon: MapPin, en: 'REGIONS' },
  { id: 'forecast', label: '需求预测', icon: ChartNoAxesCombined, en: 'FORECAST' },
  { id: 'dispatch', label: '智能调度', icon: Route, en: 'DISPATCH' },
  { id: 'records', label: '任务记录', icon: History, en: 'RECORDS' },
  { id: 'data', label: '数据查询', icon: Database, en: 'DATA' },
  { id: 'experiments', label: '仿真实验', icon: FlaskConical, en: 'EXPERIMENT' },
]
const hashId = () => location.hash.replace(/^#\/?/, '')
const current = ref(tabs.some(t => t.id === hashId()) ? hashId() : 'overview')
const page = computed(() => tabs.find(t => t.id === current.value))
const data = ref(null), loading = ref(true), busy = ref(false), error = ref(''), toast = ref(''), selected = ref(1), search = ref(''), filter = ref('all'), mobileNav = ref(false)
const forecasting = ref(false), forecast = ref([]), summary = ref(null), seed = ref(42), runs = ref(5), experimenting = ref(false), playing = ref(false), matrixRefreshing = ref(false)
const taskFilter = ref('all'), taskSearch = ref(''), showAllAlerts = ref(false)
const mapDetail = ref(false), priorityOpen = ref(false)
const expandedTask = ref(null)
const forecastDetail = ref(false)
const experimentSettings = ref(false)
const planCompare = ref(null), comparing = ref(false)
const regionListOpen = ref(false)
const focusedTask = ref(null)
const plannerReady = ref(true)
const adminToken = ref(localStorage.getItem('cycleflow_admin_token') || '')
const adminUser = ref(JSON.parse(localStorage.getItem('cycleflow_admin_user') || 'null'))
const adminName = ref('operator'), adminPassword = ref('123456'), adminError = ref(''), adminBusy = ref(false)
const adminRoleNames = { ADMIN: '管理员', OPERATOR: '运营', VIEWER: '只读' }
const canOperate = computed(() => !adminUser.value || adminUser.value.role !== 'VIEWER')
const riderRecords = ref(null)
const appUsers = ref([]), userSearch = ref(''), dataLoading = ref(false)
const filteredUsers = computed(() => appUsers.value.filter(user => !userSearch.value || `${user.nickname}${user.username}${user.phone}`.toLowerCase().includes(userSearch.value.toLowerCase())))
const activeRiderCount = computed(() => (riderRecords.value?.rides || []).filter(ride => ride.status === 'ACTIVE').length)
const userById = id => appUsers.value.find(user => user.id === id)?.nickname || '游客'
const playSpeed = ref(1)
const followTruck = ref(false)
const editDialog = ref(null), resetDialog = ref(null), infoDialog = ref(null), demoDialog = ref(null), edit = ref({}), pendingScenario = ref('morning'), formError = ref('')
const scenarioNames = { morning: '工作日早高峰', evening: '工作日晚高峰', weekend: '周末出行' }
const typeNames = { gate: '校门接驳', metro: '轨道接驳', residential: '居住区域', commercial: '商业区域' }
const statusNames = { PLANNED: '待执行', RUNNING: '运输中', COMPLETED: '已完成', CANCELLED: '已取消' }
const strategyNames = { none: '不调度', threshold: '阈值就近调度', predictive: '预测优先调度' }
const regions = computed(() => data.value?.regions || [])
const tasks = computed(() => data.value?.tasks || [])
const activeTasks = computed(() => tasks.value.filter(t => ['PLANNED', 'RUNNING'].includes(t.status)))
const alerts = computed(() => [...regions.value].filter(r => r.shortage > 0).sort((a,b) => b.shortage - a.shortage))
const visibleAlerts = computed(() => showAllAlerts.value ? alerts.value : alerts.value.slice(0, 3))
const shortage = computed(() => alerts.value.reduce((sum,r) => sum + r.shortage, 0))
const selectedRegion = computed(() => regions.value.find(r => r.id === selected.value) || regions.value[0])
const filteredRegions = computed(() => regions.value.filter(r => r.name.includes(search.value) && (filter.value === 'all' || (filter.value === 'shortage' ? r.shortage > 0 : r.kind === filter.value))))
const taskStats = computed(() => {
  const stats = { total: tasks.value.length, planned: 0, running: 0, completed: 0, cancelled: 0, moved: 0, distance: 0 }
  for (const task of tasks.value) {
    const key = task.status.toLowerCase()
    if (key in stats) stats[key]++
    if (task.status === 'COMPLETED') { stats.moved += task.quantity; stats.distance += task.distance }
  }
  return { ...stats, pending: stats.planned + stats.running }
})
const filteredTasks = computed(() => {
  const keyword = taskSearch.value.trim().toLowerCase()
  return tasks.value.filter(task => (taskFilter.value === 'all' || task.status === taskFilter.value)
    && (!keyword || task.id.toLowerCase().includes(keyword) || regionName(task.sourceId).toLowerCase().includes(keyword) || regionName(task.destinationId).toLowerCase().includes(keyword)))
})
const time = minute => `${String(Math.floor(minute / 60) % 24).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`
const regionName = id => regions.value.find(r => r.id === id)?.name || '未知区域'
function pickRegion(id) { selected.value = id; mapDetail.value = true }
function toggleTask(id) { expandedTask.value = expandedTask.value === id ? null : id }
const tripLegs = task => task.tripId ? tasks.value.filter(t => t.tripId === task.tripId).sort((a,b) => a.sequence - b.sequence) : [task]
const orderedTasks = computed(() => {
  const groups = new Map()
  for (const task of tasks.value) {
    const key = task.tripId || task.id
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(task)
  }
  return [...groups.values()]
    .sort((a, b) => (a[0].createdMinute - b[0].createdMinute) || (a[0].vehicle - b[0].vehicle) || (a[0].sequence - b[0].sequence))
    .flatMap(legs => [...legs].sort((a, b) => a.sequence - b.sequence))
})
let interval, toastTimer, forecastRequest = 0

async function api(path, method = 'GET', body) {
  const headers = {}
  if (body) headers['Content-Type'] = 'application/json'
  if (adminToken.value) headers['X-Admin-Token'] = adminToken.value
  const response = await fetch('/api' + path, { method, headers, body: body ? JSON.stringify(body) : undefined })
  const result = await response.json()
  if (response.status === 401) clearAdminSession()
  if (!response.ok) throw new Error(result.detail || result.message || '操作失败，请稍后重试')
  return result
}

function clearAdminSession() {
  adminToken.value = ''
  adminUser.value = null
  localStorage.removeItem('cycleflow_admin_token')
  localStorage.removeItem('cycleflow_admin_user')
}

async function loginAdmin() {
  if (adminBusy.value) return
  adminBusy.value = true
  adminError.value = ''
  try {
    const response = await fetch('/api/admin/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: adminName.value.trim(), password: adminPassword.value }),
    })
    const result = await response.json()
    if (!response.ok) throw new Error(result.detail || result.message || '登录失败')
    adminToken.value = result.token
    adminUser.value = result.user
    localStorage.setItem('cycleflow_admin_token', result.token)
    localStorage.setItem('cycleflow_admin_user', JSON.stringify(result.user))
    await load()
  } catch (e) { adminError.value = e.message }
  finally { adminBusy.value = false }
}

async function logoutAdmin() {
  try { await api('/admin/logout', 'POST') } catch { /* ignore */ }
  clearAdminSession()
  loading.value = false
}
function notify(message) { toast.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.value = '', 4500) }
async function load() {
  loading.value = true; error.value = ''
  if (!adminToken.value) { loading.value = false; return }
  try { adminUser.value = await api('/admin/me') } catch { loading.value = false; return }
  try { data.value = await api('/state') } catch { error.value = '暂时无法连接调度服务，请确认后端已启动后重试。' }
  finally { loading.value = false }
}
async function refreshMatrix() {
  if (matrixRefreshing.value) return
  matrixRefreshing.value = true
  try {
    const status = await api('/road-matrix/refresh', 'POST')
    if (status.loading) notify('道路矩阵正在后台更新，请稍后刷新页面查看')
    else if (!status.configured) notify('未配置高德 Web 服务 Key，继续使用网格估算')
    else if (status.entries >= status.total) notify(`道路矩阵已更新：${status.entries}/${status.total} 条`)
    else notify(`道路矩阵未完整更新：${status.entries}/${status.total} 条`)
    data.value = await api('/state')
  } catch (e) { notify(e.message) }
  finally { matrixRefreshing.value = false }
}
async function checkPlanner() {
  try { plannerReady.value = (await api('/health')).planner !== false } catch { plannerReady.value = true }
}

async function comparePlans() {
  if (comparing.value) return
  comparing.value = true
  try { planCompare.value = await api('/dispatch/compare/summary') } catch (e) { notify(e.message) }
  finally { comparing.value = false }
}
const rowDelta = row => !row?.ortoolsKm || !row.greedyKm
  ? null
  : Math.round((row.ortoolsKm - row.greedyKm) / row.greedyKm * 1000) / 10
const chartMax = () => Math.max(1, ...(planCompare.value || []).map(row => Math.max(row.greedyKmMax || 0, row.ortoolsKmMax || 0)))
const chartY = value => 200 - (value / chartMax()) * 150
function downloadCompareCsv() {
  if (!planCompare.value?.length) return
  const head = ['scenario', 'samples', 'legs', 'greedy_km', 'greedy_km_min', 'greedy_km_max', 'greedy_minutes', 'ortools_km', 'ortools_km_min', 'ortools_km_max', 'ortools_minutes', 'km_delta_percent']
  const lines = planCompare.value.map(row => [row.scenario, row.samples, row.legs, row.greedyKm, row.greedyKmMin, row.greedyKmMax,
    row.greedyMinutes, row.ortoolsKm ?? '', row.ortoolsKmMin ?? '', row.ortoolsKmMax ?? '', row.ortoolsMinutes ?? '', rowDelta(row) ?? ''].join(','))
  const blob = new Blob([head.join(',') + '\n' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = 'cycleflow-plan-comparison.csv'
  link.click()
  URL.revokeObjectURL(link.href)
}

async function mutate(path, body, message, method = 'POST') {
  if (!canOperate.value) { notify('只读账号不能执行该操作'); return false }
  if (busy.value) return false
  busy.value = true
  try { data.value = await api(path, method, body); summary.value = null; if (message) notify(message); return true }
  catch (e) { notify(e.message); return false }
  finally { busy.value = false }
}
function navigate(id) { current.value = id; location.hash = id; mobileNav.value = false }
function onHash() { const id = hashId(); if (tabs.some(t => t.id === id)) current.value = id }
async function loadData() {
  dataLoading.value = true
  try {
    const [users, records] = await Promise.all([api('/data/users'), api('/rider/records')])
    appUsers.value = users
    riderRecords.value = records
  } catch { /* keep last */ }
  finally { dataLoading.value = false }
}

async function loadRiderRecords() {
  try { riderRecords.value = await api('/rider/records') } catch { /* keep last */ }
}

async function generate() { if (await mutate('/dispatch/generate', null, '已生成调度方案，车辆与停车容量已检查')) navigate('dispatch') }
async function advance(step = 5) {
  if (!canOperate.value) { notify('只读账号不能推进时间'); return }
  await mutate(`/simulation/advance?minutes=${step}`); if (!tasks.value.some(t => t.status === 'RUNNING')) playing.value = false }
function togglePlay() {
  if (!canOperate.value) return notify('只读账号不能播放调度过程')
  if (!tasks.value.some(t => t.status === 'RUNNING')) return notify('请先在调度中心开始一个任务')
  playing.value = !playing.value
}
function requestReset(scenario = data.value.scenario) { pendingScenario.value = scenario; playing.value = false; resetDialog.value.showModal() }
function changeScenario(scenario) { if (!scenario || scenario === data.value.scenario) return; demoDialog.value.close(); requestReset(scenario) }
function openExperimentsFromDemo() { demoDialog.value.close(); navigate('experiments') }
async function reset() { if (await mutate('/simulation/reset', { scenario: pendingScenario.value }, '演示场景已重置，初始库存恢复为 500 辆')) resetDialog.value.close() }
async function openEdit(r) { formError.value = ''; edit.value = { id: r.id, name: r.name, capacity: r.capacity, safetyStock: r.safetyStock }; editDialog.value.showModal(); await nextTick(); editDialog.value.querySelector('input')?.focus() }
async function saveEdit() {
  formError.value = ''
  if (await mutate('/regions/' + edit.value.id, edit.value, '停车区域已保存', 'PATCH')) editDialog.value.close()
  else formError.value = toast.value
}
async function loadForecast() {
  if (!data.value) return
  const request = ++forecastRequest; forecasting.value = true
  try { const result = await api(`/regions/${selected.value}/forecast`); if (request === forecastRequest) forecast.value = result }
  catch (e) { if (request === forecastRequest) { forecast.value = []; notify(e.message) } }
  finally { if (request === forecastRequest) forecasting.value = false }
}
async function runExperiment() {
  if (!Number.isInteger(Number(seed.value)) || Number(seed.value) < 0 || Number(seed.value) > 999999) return notify('随机种子请输入 0～999999 的整数')
  if (runs.value < 1 || runs.value > 10) return notify('重复次数须在 1～10 之间')
  if (Number(seed.value) + runs.value - 1 > 999999) return notify('种子范围超出 0～999999，请降低起始种子或重复次数')
  experimenting.value = true
  try {
    summary.value = await api(`/experiments/summary?seed=${seed.value}&runs=${runs.value}`)
    notify(runs.value > 1 ? `已用 ${runs.value} 个连续种子完成对比，展示均值与范围` : '三种策略已完成相同请求序列的独立回放')
  } catch (e) { notify(e.message) } finally { experimenting.value = false }
}
function saveCsv(rows, filename) {
  const blob = new Blob(['\ufeff' + rows.map(row => row.map(cell => `"${String(cell).replaceAll('"','""')}"`).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = filename; a.click(); URL.revokeObjectURL(url)
}
function downloadCsv() {
  const rows = [
    ['策略','平均借车成功率(%)','最低成功率(%)','最高成功率(%)','平均缺车分钟','平均搬运里程(km)','平均搬运量(辆)'],
    ...summary.value.results.map(r => [strategyNames[r.strategy],r.serviceRateAvg,r.serviceRateMin,r.serviceRateMax,r.shortageMinutesAvg,r.distanceAvg,r.movedAvg]),
    [],
    ['逐种子明细'],
    ['种子','不调度成功率(%)','阈值就近成功率(%)','预测优先成功率(%)','不调度缺车分钟','阈值缺车分钟','预测缺车分钟']
  ]
  for (const run of summary.value.runs) {
    const by = Object.fromEntries(run.results.map(r => [r.strategy, r]))
    rows.push([run.seed,by.none.serviceRate,by.threshold.serviceRate,by.predictive.serviceRate,by.none.shortageMinutes,by.threshold.shortageMinutes,by.predictive.shortageMinutes])
  }
  saveCsv(rows, `交点-仿真实验-${summary.value.scenario}-seed${summary.value.baseSeed}-runs${summary.value.runCount}.csv`)
}
function downloadTasksCsv() {
  if (!filteredTasks.value.length) return notify('当前没有可导出的任务记录')
  const rows = [
    ['任务编号','行程','第几单','状态','取车区域','送车区域','数量(辆)','运输车','里程(km)','时长(分钟)','最晚送达','创建时间','开始时间','完成时间','生成原因'],
    ...filteredTasks.value.map(t => [t.id,t.tripId || '',t.sequence,statusNames[t.status],regionName(t.sourceId),regionName(t.destinationId),t.quantity,'0'+t.vehicle,t.distance,t.duration,t.deadlineMinute != null ? time(t.deadlineMinute) : '',time(t.createdMinute),t.startedMinute != null ? time(t.startedMinute) : '',t.completedMinute != null ? time(t.completedMinute) : '',t.reason || ''])
  ]
  saveCsv(rows, `交点-任务记录-${data.value?.scenario || 'demo'}.csv`)
}
const forecastMax = computed(() => Math.max(20, ...forecast.value.flatMap(p => [p.outflow, p.inflow])) )
const plotLine = key => forecast.value.map((p,i) => `${65+i*85},${255-p[key]/forecastMax.value*200}`).join(' ')
watch([current, selected, () => data.value?.minute, () => data.value?.scenario], () => { if (current.value === 'forecast') loadForecast() })
watch(current, () => { if (current.value !== 'dispatch' && current.value !== 'overview') playing.value = false; if (current.value === 'records') loadRiderRecords(); if (current.value === 'data') loadData() })
onMounted(async () => { window.addEventListener('hashchange', onHash); if (!adminToken.value) { loading.value = false; return } await load(); if (current.value === 'forecast') loadForecast(); if (current.value === 'records') loadRiderRecords(); if (current.value === 'data') loadData(); if (new URLSearchParams(location.search).has('compare')) comparePlans();
  checkPlanner(); const plannerTimer = setInterval(() => { checkPlanner(); if (plannerReady.value) clearInterval(plannerTimer) }, 1500);
  if (new URLSearchParams(location.search).has('open')) regionListOpen.value = true;
  if (new URLSearchParams(location.search).has('focus')) { const active = orderedTasks.value.find(t => t.status === 'PLANNED' || t.status === 'RUNNING'); if (active) focusedTask.value = active.id; } interval = setInterval(() => { if (playing.value && !busy.value) advance(playSpeed.value) }, 260) })
onUnmounted(() => { clearInterval(interval); clearTimeout(toastTimer); window.removeEventListener('hashchange', onHash) })
</script>

<template>
  <div v-if="!adminToken" class="admin-login-screen">
    <div class="admin-login-card">
      <div class="admin-login-brand"><span class="admin-login-mark">交</span><div><strong>交点</strong><span>共享单车智能调度</span></div></div>
      <h1>运营控制台登录</h1>
      <p class="muted">演示账号 admin / operator / viewer，密码统一为 123456</p>
      <label>账号<input v-model="adminName" autocomplete="username" @keyup.enter="loginAdmin"/></label>
      <label>密码<input v-model="adminPassword" type="password" autocomplete="current-password" @keyup.enter="loginAdmin"/></label>
      <p v-if="adminError" class="form-error" role="alert">{{ adminError }}</p>
      <button class="btn primary" :disabled="adminBusy" @click="loginAdmin">{{ adminBusy ? '登录中…' : '登录控制台' }}</button>
      <div class="admin-login-demo"><button v-for="name in ['admin','operator','viewer']" :key="name" @click="adminName = name; adminPassword = '123456'">{{ name }}</button></div>
      <a class="admin-login-rider" href="/rider">打开用户端 →</a>
    </div>
  </div>
  <div v-else class="app-shell">
    <div v-if="mobileNav" class="sidebar-scrim" @click="mobileNav = false"></div>
    <aside class="sidebar" :class="{ 'is-open': mobileNav }">
      <a class="brand" href="#overview" @click="navigate('overview')"><div class="brand-icon"><Bike :size="27" stroke-width="1.8"/></div><div><strong>交点<span>CycleFlow</span></strong><small>共享单车智能调度</small></div></a>
      <div class="workspace-label">工作空间 <span>01</span></div>
      <nav aria-label="主导航"><button v-for="tab in tabs" :key="tab.id" :class="['nav-item', { active: current === tab.id }]" :aria-current="current === tab.id ? 'page' : undefined" @click="navigate(tab.id)"><component :is="tab.icon" :size="20"/><span>{{ tab.label }}</span><span v-if="tab.id === 'dispatch' && activeTasks.length" class="nav-badge">{{ activeTasks.length }}</span></button><a class="nav-item nav-rider" href="/rider" target="_blank" rel="noopener"><Bike :size="20"/><span>骑行赚奖励</span><ArrowUpRight :size="14" class="nav-rider-arrow"/></a></nav>
      <div class="sidebar-bottom"><div class="profile"><div class="avatar">{{ adminUser?.nickname?.slice(0, 1) || '交' }}</div><div><strong>{{ adminUser?.nickname || '演示工作台' }}</strong><span>{{ adminRoleNames[adminUser?.role] || '本地运行' }} · {{ adminUser?.username || 'demo' }}</span></div><button class="text-button admin-logout" @click="logoutAdmin">退出</button><span class="connection" :class="{ offline: !!error }"></span></div></div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb"><button class="mobile-menu icon-button" aria-label="打开导航" @click="mobileNav = !mobileNav"><Menu :size="22"/></button><span>调度工作台</span><ChevronRight :size="14"/><strong>{{ page.label }}</strong></div><div class="topbar-right"><span class="demo-badge"><span></span>仿真演示</span><span class="header-separator"></span><MapPin :size="15"/><span class="location-label">成都 · 犀浦</span></div></header>
      <main>
        <div v-if="loading" class="skeleton-page" aria-busy="true" aria-label="正在加载调度工作台">
          <div class="skeleton skeleton-eyebrow"></div>
          <div class="skeleton skeleton-title"></div>
          <div class="skeleton skeleton-sub"></div>
          <div class="skeleton-stats"><div v-for="i in 4" :key="i" class="skeleton skeleton-stat"></div></div>
          <div class="skeleton skeleton-map"></div>
        </div>
        <div v-else-if="error" class="loading-panel"><CircleAlert :size="40"/><h2>服务暂不可用</h2><p>{{ error }}</p><button class="btn primary" @click="load">重新连接</button></div>
        <template v-else-if="data">
          <transition name="page" mode="out-in">
            <div :key="current" class="page-view">
              <div v-if="!canOperate" class="readonly-banner">当前是只读账号（viewer），可以查看数据，但不能生成任务、推进时间或编辑区域。</div>
              <div v-if="!plannerReady" class="warming-banner">路径求解器正在预热，生成方案与运行实验暂时不可用，通常需要 10–20 秒。</div>
          <section class="page-heading"><div><span class="eyebrow">{{ page.en }}</span><h1>{{ page.label }}<span v-if="current === 'overview'" class="heading-dot"></span></h1><p>{{ { overview: '从供需变化，到每一次精准调度。', regions: '管理服务区域的停车容量与安全库存。', forecast: '提前看见需求，安排下一次补车。', dispatch: '将补车建议转化为可执行的搬运任务。', records: '查看每一次补车从生成到送达的完整记录。', data: '账号、收益与骑行记录的统一查询。', experiments: '用相同的出行请求，比较不同调度策略。' }[current] }}</p></div><div class="heading-actions"><template v-if="current === 'dispatch'"><button class="btn" :disabled="busy || !canOperate" @click="requestReset()"><RefreshCw :size="16"/>重置场景</button><button class="btn primary" :disabled="busy || !plannerReady || !canOperate" @click="generate"><Route :size="17"/>{{ busy ? '处理中…' : '生成调度方案' }}</button></template><template v-else-if="current === 'overview'"><button class="btn primary" :disabled="busy || !plannerReady || !canOperate" @click="generate"><Route :size="17"/>{{ busy ? '处理中…' : '生成调度方案' }}</button></template><template v-else-if="current === 'records'"><button class="btn" @click="downloadTasksCsv"><Download :size="16"/>导出任务 CSV</button></template><button v-else class="btn" @click="infoDialog.showModal()"><Info :size="16"/>数据说明</button></div></section>

          <template v-if="current === 'overview'">
            <div class="status-strip">
              <p class="status-summary"><strong><AnimatedNumber :value="alerts.length"/></strong> 个区域待补 <strong><AnimatedNumber :value="shortage"/></strong> 辆<span v-if="activeTasks.length" class="status-extra"> · 进行中 <AnimatedNumber :value="activeTasks.length"/> 项</span></p>
              <div class="status-actions"><span class="status-clock"><Clock3 :size="14"/>{{ time(data.minute) }}</span><button class="text-button" @click="demoDialog.showModal()"><Settings2 :size="16"/>演示控制</button></div>
            </div>
            <section class="overview-grid">
              <article class="panel map-panel map-panel-full">
                <div class="map-stage">
                  <RegionMap :regions="regions" :tasks="tasks" :minute="data.minute" :selected="mapDetail ? selected : null" @select="pickRegion" @clear="mapDetail = false"/>
                  <div class="map-overlay-title"><h2>区域分布</h2><span>12 个区域 · 点击圆点查看</span></div>
                  <button class="map-overlay-action" @click="navigate('regions')">区域管理<ArrowUpRight :size="15"/></button>
                  <transition name="fade">
                    <div v-if="mapDetail && selectedRegion" class="map-detail-card">
                      <button class="map-detail-close" aria-label="关闭区域详情" @click="mapDetail = false"><X :size="15"/></button>
                      <span class="eyebrow">{{ typeNames[selectedRegion.kind] }}</span>
                      <h3>{{ selectedRegion.name }}</h3>
                      <div class="map-detail-numbers"><strong>{{ selectedRegion.stock }}</strong><span>/ 目标 {{ selectedRegion.target }} 辆</span></div>
                      <p>容量 {{ selectedRegion.capacity }} 辆<span v-if="selectedRegion.shortage"> · 待补 {{ selectedRegion.shortage }} 辆</span></p>
                    </div>
                  </transition>
                </div>
              </article>
              <aside class="panel priority-panel">
                <button class="priority-head" :aria-expanded="priorityOpen" @click="priorityOpen = !priorityOpen">
                  <div><h2>补车优先级</h2><span class="priority-sub">{{ alerts.length }} 个区域 · 待补 {{ shortage }} 辆</span></div>
                  <ChevronDown :size="18" :class="['chev', { open: priorityOpen }]"/>
                </button>
                <div v-if="priorityOpen" class="priority-list">
                  <button v-for="(r,i) in alerts" :key="r.id" class="priority-row" @click="pickRegion(r.id)">
                    <span class="priority-rank">{{ String(i+1).padStart(2,'0') }}</span>
                    <span class="priority-name">{{ r.name }}</span>
                    <span class="priority-shortage">待补 {{ r.shortage }}</span>
                    <ChevronRight :size="15"/>
                  </button>
                  <p v-if="!alerts.length" class="priority-empty">当前没有待补区域</p>
                </div>
                <button class="text-button priority-more" @click="navigate('dispatch')">进入调度中心 <ChevronRight :size="15"/></button>
              </aside>
            </section>
          </template>

          <template v-if="current === 'regions'">
            <section class="region-explorer" aria-label="停车区域地图与详情">
              <article class="panel region-explorer-map"><div class="panel-heading"><div><h2>地图选点</h2><span>点击圆点查看区域库存与补车建议</span></div><span class="tiny-label">{{ regions.length }} POINTS</span></div><RegionMap :regions="regions" :selected="selected" :tasks="tasks" :minute="data.minute" :focused-task="focusedTask" :follow="followTruck" @select="selected = $event; focusedTask = null" @clear="focusedTask = null"/></article>
              <article v-if="selectedRegion" class="panel region-inspector"><div class="region-inspector-top"><span class="eyebrow">SELECTED REGION · {{ String(selectedRegion.id).padStart(2,'0') }}</span><span :class="['status-pill', selectedRegion.shortage ? 'orange-pill' : 'green-pill']">{{ selectedRegion.shortage ? '待补 ' + selectedRegion.shortage + ' 辆' : '库存充足' }}</span></div><h2>{{ selectedRegion.name }}</h2><p>{{ typeNames[selectedRegion.kind] }} · 示范停车区域</p><div class="region-inspector-stock"><strong>{{ selectedRegion.stock }}<small> / {{ selectedRegion.capacity }}</small></strong><span>当前库存 / 停车容量</span></div><div class="stock-track"><div :style="{ width: Math.min(100, selectedRegion.stock / selectedRegion.capacity * 100) + '%' }"></div></div><div class="region-inspector-metrics"><div><span>30 分钟目标</span><strong>{{ selectedRegion.target }} 辆</strong></div><div><span>安全库存</span><strong>{{ selectedRegion.safetyStock }} 辆</strong></div><div><span>已安排调入</span><strong>{{ selectedRegion.reservedIn }} 辆</strong></div></div><div class="region-inspector-actions"><button class="btn" :disabled="!canOperate" @click="openEdit(selectedRegion)"><Settings2 :size="16"/>编辑区域</button><button class="btn primary" @click="navigate('forecast')">查看预测<ArrowRight :size="16"/></button></div></article>
            </section>
            <details class="panel table-panel" :open="regionListOpen" @toggle="regionListOpen = $event.target.open">
              <summary class="table-summary"><span>区域清单</span><span class="table-summary-meta">{{ filteredRegions.length }} 个区域 · {{ alerts.length }} 个待补</span><ChevronDown :size="18" class="chev"/></summary>
<div class="table-toolbar"><div class="search-field"><Search :size="18"/><input v-model="search" placeholder="搜索停车区域" aria-label="搜索停车区域"/></div><select v-model="filter" aria-label="筛选停车区域"><option value="all">全部区域</option><option value="shortage">待补车区域</option><option v-for="(name,key) in typeNames" :value="key" :key="key">{{ name }}</option></select><span class="table-count">共 {{ filteredRegions.length }} 个区域</span></div><div class="list-rows">
                <div v-for="r in filteredRegions" :key="r.id" :class="['list-row', { 'is-selected': selected === r.id }]" role="button" tabindex="0" :aria-label="`区域 ${r.name}，库存 ${r.stock} 辆`" @click="selected = r.id" @keydown.enter.prevent="selected = r.id">
                  <span class="row-icon"><MapPin :size="18"/></span>
                  <div class="row-main"><strong>{{ r.name }}</strong><span>{{ typeNames[r.kind] }} · 容量 {{ r.capacity }} 辆 · 安全库存 {{ r.safetyStock }} 辆</span></div>
                  <div class="row-side"><span class="row-figure">{{ r.stock }}<small> / {{ r.capacity }}</small></span><span :class="['status-pill', r.shortage ? 'orange-pill' : 'green-pill']">{{ r.shortage ? '待补 ' + r.shortage + ' 辆' : r.reservedIn ? '补车已安排' : '库存充足' }}</span></div>
                  <button class="text-button" @click.stop="openEdit(r)">编辑</button>
                </div>
                <p v-if="!filteredRegions.length" class="empty-table">没有找到匹配的停车区域</p>
              </div><div class="table-footer"><Info :size="16"/>区域为示范点位；编辑容量与安全库存后，补车建议会重新计算。</div></details>
          </template>

          <template v-if="current === 'forecast'">
            <div class="notice"><Info :size="18"/><span>当前为规则需求模型，展示场景中的预期借还趋势；尚未使用犀浦实测订单训练。</span></div><div class="forecast-layout"><article class="panel forecast-main"><div class="panel-heading"><h2>未来 2 小时借还趋势</h2><select v-model.number="selected" aria-label="选择预测区域"><option v-for="r in regions" :key="r.id" :value="r.id">{{ r.name }}</option></select></div><div class="chart-legend"><span><i class="legend-dot green"></i>预计借出</span><span><i class="legend-dot blue"></i>预计归还</span><span class="muted">每 15 分钟 · 单位：辆</span></div><div v-if="forecasting" class="chart-loading">正在计算预测…</div><svg v-else class="forecast-chart" viewBox="0 0 740 310" role="img" aria-label="未来两小时每15分钟预计借出和归还车辆折线图"><defs><linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0aa580" stop-opacity=".18"/><stop offset="1" stop-color="#0aa580" stop-opacity="0"/></linearGradient></defs><g v-for="i in 5" :key="i"><line x1="55" x2="700" :y1="255-(i-1)*50" :y2="255-(i-1)*50" stroke="#e7eeeb" stroke-dasharray="4 5"/><text x="35" :y="260-(i-1)*50" text-anchor="end" class="chart-label">{{ Math.round((i-1)*forecastMax/4) }}</text></g><polygon v-if="forecast.length" :points="`65,255 ${plotLine('outflow')} 660,255`" fill="url(#chartGradient)"/><polyline :points="plotLine('outflow')" fill="none" stroke="#079b78" stroke-width="3" stroke-linejoin="round"/><polyline :points="plotLine('inflow')" fill="none" stroke="#7190c9" stroke-width="3" stroke-dasharray="7 5"/><g v-for="(p,i) in forecast" :key="p.time"><circle :cx="65+i*85" :cy="255-p.outflow/forecastMax*200" r="4" fill="#fff" stroke="#079b78" stroke-width="2"/><text :x="65+i*85" y="286" text-anchor="middle" class="chart-label">{{ p.time }}</text></g></svg><div class="forecast-note">基于当前场景推演；“供需余额”为现有库存加累计归还、减累计借出，负值代表潜在缺口。</div></article><article class="panel forecast-summary"><div class="panel-heading"><h2>30 分钟供需判断</h2></div><span class="large-number">{{ selectedRegion?.shortage || 0 }}<small>辆</small></span><p class="muted">建议补充数量</p><button class="summary-toggle" :aria-expanded="forecastDetail" @click="forecastDetail = !forecastDetail">查看依据 <ChevronDown :size="15" :class="['chev', { open: forecastDetail }]"/></button><div v-if="forecastDetail" class="summary-rows"><div class="summary-row"><span>当前可用车辆</span><strong>{{ selectedRegion?.stock }} 辆</strong></div><div class="summary-row"><span>预计借出 / 归还</span><strong>{{ selectedRegion?.predictedOut }} / {{ selectedRegion?.predictedIn }} 辆</strong></div><div class="summary-row"><span>安全库存</span><strong>{{ selectedRegion?.safetyStock }} 辆</strong></div><div class="summary-row"><span>已安排调入</span><strong>{{ selectedRegion?.reservedIn }} 辆</strong></div></div><button class="btn primary full-width" :disabled="busy || !plannerReady || !canOperate" @click="generate"><Route :size="17"/>生成调度方案</button></article></div><details class="panel disclosure forecast-table"><summary><span><strong>分时段预测明细</strong><small>不含新调度的供需余额</small></span><ChevronRight :size="18" class="disclosure-icon"/></summary><div class="disclosure-body table-scroll"><table><thead><tr><th>时段结束</th><th>预计借出</th><th>预计归还</th><th>累计供需余额</th><th>提示</th></tr></thead><tbody><tr v-for="p in forecast" :key="p.time"><td>{{ p.time }}</td><td>{{ p.outflow }} 辆</td><td>{{ p.inflow }} 辆</td><td>{{ p.projectedStock }} 辆</td><td><span :class="p.projectedStock < 0 ? 'orange-pill' : 'green-pill'">{{ p.projectedStock < 0 ? '存在潜在缺口' : '余额非负' }}</span></td></tr></tbody></table></div></details>
          </template>

          <template v-if="current === 'dispatch'">
            <div class="dispatch-toolbar"><div><span class="live-dot"></span><strong>运输资源</strong><span>2 辆运输车 · 每车载量 20 辆</span><span v-if="!plannerReady" class="warming-chip">路径求解器预热中…</span></div><div class="clock-controls"><Clock3 :size="17"/><strong>{{ time(data.minute) }}</strong><button class="btn compact" :disabled="busy" @click="advance()">+5 分钟</button><button class="btn compact" @click="togglePlay"><Pause v-if="playing" :size="15"/><Play v-else :size="15"/>{{ playing ? '暂停' : '播放' }}</button><button class="btn compact" @click="playSpeed = playSpeed === 1 ? 2 : playSpeed === 2 ? 4 : 1">{{ playSpeed }}×</button><button class="btn compact" :class="{ 'is-on': followTruck }" @click="followTruck = !followTruck">跟随</button></div></div>
            <div class="plan-summary"><div class="plan-summary-icon"><Activity :size="18"/></div><div class="plan-summary-copy"><strong>{{ activeTasks.length ? '任务已占用运输资源' : alerts.length ? '当前存在待补缺口' : '当前供需平衡' }}</strong><p>{{ alerts.length ? alerts.slice(0, 3).map(r => r.name + ' ' + r.shortage + ' 辆').join(' · ') : '所有区域的可用库存均不低于 30 分钟目标。' }}</p></div><div class="plan-summary-metrics"><span>待补区域 <b><AnimatedNumber :value="alerts.length"/></b></span><span>剩余缺口 <b><AnimatedNumber :value="shortage"/></b> 辆</span><span>在途 <b><AnimatedNumber :value="data.transitBikes"/></b> 辆</span></div></div>
            <div class="dispatch-layout"><div class="dispatch-map-full">
              <RegionMap :regions="regions" :selected="selected" :tasks="tasks" :minute="data.minute" :focused-task="focusedTask" :follow="followTruck" @select="selected = $event; focusedTask = null" @clear="focusedTask = null"/>
              <div class="map-overlay-title"><h2>搬运路线</h2><span>多点取送 · 高德道路路线</span></div>
              <div class="map-overlay-foot">缺口优先 · 距离口径 {{ data.distanceModel }}</div>
            </div><div class="task-list"><div v-if="!tasks.length" class="panel task-empty"><Route :size="42"/><h2>准备好下一次调度</h2><p>根据区域缺口、富余库存和运输车载量，生成可执行任务。</p><button class="btn primary" :disabled="busy || !plannerReady || !canOperate" @click="generate">生成调度方案</button></div><article v-for="task in orderedTasks" :key="task.id" :class="['panel task-card', { 'is-focused': focusedTask === task.id }]" role="button" tabindex="0" :aria-label="`任务 ${task.id}，${regionName(task.sourceId)} 到 ${regionName(task.destinationId)}，${statusNames[task.status]}`" @click="focusedTask = task.id" @keydown.enter.prevent="focusedTask = task.id"><div class="task-header"><span class="task-icon"><Truck :size="17"/></span><span class="task-id">{{ task.id }}<template v-if="tripLegs(task).length > 1"> · 第 {{ task.sequence }} / {{ tripLegs(task).length }} 单</template></span><span :class="['task-status', task.status.toLowerCase() ]">{{ statusNames[task.status] }}</span></div><div class="task-route"><div class="route-line"><i></i><span></span><i></i></div><div><strong>{{ regionName(task.sourceId) }}</strong><span>取车点</span><strong>{{ regionName(task.destinationId) }}</strong><span>送车点</span></div><div class="task-quantity"><AnimatedNumber :value="task.quantity"/><small>辆</small></div></div><div class="task-meta"><span><Truck :size="14"/>运输车 0{{ task.vehicle }}</span><span>{{ task.distance.toFixed(1) }} km</span><span><Clock3 :size="14"/>{{ task.duration }} 分钟</span></div><div v-if="task.deadlineMinute" :class="['task-deadline', { late: task.completedMinute != null && task.completedMinute > task.deadlineMinute }]">最晚 {{ time(task.deadlineMinute) }} 送达<template v-if="task.completedMinute != null && task.completedMinute > task.deadlineMinute"> · 已超时</template></div><details v-if="task.reason" class="task-reason"><summary><Info :size="14"/>调度依据</summary><p>{{ task.reason }}</p></details><div class="task-actions" v-if="task.status === 'PLANNED' && task.sequence === 1 && canOperate"><button class="btn compact" :disabled="busy" @click="mutate(`/dispatch/${task.id}/cancel`,null,'任务已取消，预留库存已释放')">取消{{ tripLegs(task).length > 1 ? '行程' : '任务' }}</button><button class="btn primary compact" :disabled="busy" @click="mutate(`/dispatch/${task.id}/start`,null,'取车完成，单车进入运输状态')"><Play :size="14"/>开始行程</button></div><div v-else-if="task.status === 'PLANNED'" class="task-waiting">等待取车<button v-if="canOperate" class="text-button task-cancel-link" :disabled="busy" @click.stop="mutate(`/dispatch/${task.id}/cancel`,null,'已取消剩余站点')">取消</button></div><div v-else-if="task.status === 'RUNNING'" class="task-progress"><span>预计 {{ time(task.startedMinute + task.duration) }} 送达</span><div class="stock-track"><div :style="{ width:Math.min(100,(data.minute-task.startedMinute)/task.duration*100)+'%' }"></div></div></div><div v-else-if="task.status === 'COMPLETED'" class="task-completed"><Check :size="15"/>{{ time(task.completedMinute) }} 已送达并更新库存</div></article></div></div>
          </template>

          <template v-if="current === 'records'">
            <div class="status-strip">
              <p class="status-summary"><strong>{{ taskStats.total }}</strong> 项任务<span v-if="taskStats.planned"> · 待执行 {{ taskStats.planned }}</span><span v-if="taskStats.running"> · 运输中 {{ taskStats.running }}</span><span v-if="taskStats.completed"> · 已完成 {{ taskStats.completed }}</span><span v-if="taskStats.cancelled"> · 已取消 {{ taskStats.cancelled }}</span></p>
            </div>
            <article class="panel table-panel records-panel">
              <div class="table-toolbar">
                <div class="search-field"><Search :size="18"/><input v-model="taskSearch" placeholder="搜索任务编号或区域" aria-label="搜索任务记录"/></div>
                <select v-model="taskFilter" aria-label="筛选任务状态"><option value="all">全部状态</option><option v-for="(name,key) in statusNames" :key="key" :value="key">{{ name }}</option></select>
                <span class="table-count">共 {{ filteredTasks.length }} 条记录</span>
              </div>
              <div class="list-rows">
                <template v-for="task in filteredTasks" :key="task.id">
                  <div class="list-row" role="button" tabindex="0" :aria-expanded="expandedTask === task.id" :aria-label="`任务 ${task.id}，${statusNames[task.status]}`" @click="toggleTask(task.id)" @keydown.enter.prevent="toggleTask(task.id)">
                    <span class="row-icon"><Truck :size="18"/></span>
                    <div class="row-main"><strong>{{ regionName(task.sourceId) }} → {{ regionName(task.destinationId) }}</strong><span>{{ task.id }} · {{ task.quantity }} 辆 · 运输车 0{{ task.vehicle }} · 创建 {{ time(task.createdMinute) }}</span></div>
                    <span :class="['task-status', task.status.toLowerCase()]">{{ statusNames[task.status] }}</span>
                    <ChevronDown :size="16" :class="['chev', { open: expandedTask === task.id }]"/>
                  </div>
                  <div v-if="expandedTask === task.id" class="record-detail record-detail-inline">
                    <div><span>运输车</span><strong>0{{ task.vehicle }}</strong></div>
                    <div><span>开始 / 完成</span><strong>{{ task.startedMinute != null ? time(task.startedMinute) : '未开始' }}<template v-if="task.completedMinute != null"> → {{ time(task.completedMinute) }}</template></strong></div>
                    <div><span>里程 / 时长</span><strong>{{ task.distance.toFixed(1) }} km / {{ task.duration }} 分钟</strong></div>
                    <div class="record-reason-cell"><span>生成原因</span><p>{{ task.reason || '旧记录未保存原因' }}</p></div>
                  </div>
                </template>
                <p v-if="!filteredTasks.length" class="empty-table">没有符合条件的任务记录。生成调度方案并推进时间后，这里会出现完整轨迹。</p>
              </div>
              <div class="table-footer"><Info :size="16"/>记录由后端持久化；时间随工作台 +5 分钟推进，重置场景会清除全部任务。</div>
            </article>

            <article class="panel table-panel rider-records-panel">
              <div class="table-toolbar">
                <div><strong>骑行奖励记录</strong><span class="table-count">累计发放 ¥{{ ((riderRecords?.walletCents || 0) / 100).toFixed(2) }} · {{ riderRecords?.completedRides || 0 }} 次完成</span></div>
                <button class="text-button" @click="loadRiderRecords">刷新</button>
              </div>
              <div class="list-rows">
                <div v-for="ride in riderRecords?.rides || []" :key="ride.id" class="list-row">
                  <span class="row-icon"><Bike :size="18"/></span>
                  <div class="row-main"><strong>{{ regionName(ride.fromId) }} → {{ regionName(ride.toId) }}</strong><span>{{ time(ride.createdMinute) }} 接单<template v-if="ride.completedMinute != null"> · {{ time(ride.completedMinute) }} 完成</template></span></div>
                  <span class="rider-record-amount">+¥{{ (ride.amountCents / 100).toFixed(1) }}</span>
                  <span :class="['task-status', ride.status === 'COMPLETED' ? 'completed' : 'running']">{{ ride.status === 'COMPLETED' ? '已发放' : '进行中' }}</span>
                </div>
                <p v-if="!riderRecords?.rides.length" class="empty-table">还没有普通用户接单。打开用户端 /rider 接一单后，这里会出现奖励记录。</p>
              </div>
              <div class="table-footer"><Info :size="16"/>来自 C 端「骑行赚奖励」；金额为演示币，完成时从取车点扣 1 辆、目的站点加 1 辆。</div>
            </article>
          </template>

          <template v-if="current === 'data'">
            <div class="data-stats">
              <article class="panel data-stat"><span>演示账户</span><strong>{{ appUsers.length }}</strong></article>
              <article class="panel data-stat"><span>累计发放</span><strong>¥{{ ((riderRecords?.walletCents || 0) / 100).toFixed(2) }}</strong></article>
              <article class="panel data-stat"><span>完成骑行</span><strong>{{ riderRecords?.completedRides || 0 }}</strong></article>
              <article class="panel data-stat"><span>进行中</span><strong>{{ activeRiderCount }}</strong></article>
            </div>

            <article class="panel table-panel">
              <div class="table-toolbar">
                <div class="search-field"><Search :size="18"/><input v-model="userSearch" placeholder="搜索昵称、账号或手机号" aria-label="搜索用户账户"/></div>
                <span class="table-count">共 {{ filteredUsers.length }} 个账户</span>
                <button class="text-button" @click="loadData">{{ dataLoading ? '读取中…' : '刷新' }}</button>
              </div>
              <div class="list-rows">
                <div v-for="user in filteredUsers" :key="user.id" class="list-row data-user-row">
                  <span class="row-icon data-avatar">{{ user.nickname.slice(0, 1) }}</span>
                  <div class="row-main"><strong>{{ user.nickname }} <em>@{{ user.username }}</em></strong><span>{{ user.phone }} · 完成 {{ user.completedRides }} 次<template v-if="user.activeRides"> · 进行中 {{ user.activeRides }}</template></span></div>
                  <span class="data-wallet">¥{{ (user.walletCents / 100).toFixed(2) }}</span>
                  <span class="data-password" title="密码仅以哈希保存，不展示明文">••••••</span>
                  <span class="task-status completed">已加密</span>
                </div>
                <p v-if="!filteredUsers.length" class="empty-table">没有匹配的账户。</p>
              </div>
              <div class="table-footer"><Info :size="16"/>演示账户统一密码为 123456，服务端仅保存 SHA-256 哈希；正式环境应改为 BCrypt/Argon2 并接入登录会话。</div>
            </article>

            <article class="panel table-panel">
              <div class="table-toolbar"><div><strong>骑行收益流水</strong><span class="table-count">最近 {{ (riderRecords?.rides || []).length }} 条</span></div><button class="text-button" @click="loadData">刷新</button></div>
              <div class="list-rows">
                <div v-for="ride in riderRecords?.rides || []" :key="ride.id" class="list-row">
                  <span class="row-icon"><Bike :size="18"/></span>
                  <div class="row-main"><strong>{{ userById(ride.userId) }} · {{ regionName(ride.fromId) }} → {{ regionName(ride.toId) }}</strong><span>{{ time(ride.createdMinute) }} 接单<template v-if="ride.completedMinute != null"> · {{ time(ride.completedMinute) }} 完成</template></span></div>
                  <span class="rider-record-amount">+¥{{ (ride.amountCents / 100).toFixed(1) }}</span>
                  <span :class="['task-status', ride.status === 'COMPLETED' ? 'completed' : 'running']">{{ ride.status === 'COMPLETED' ? '已发放' : '进行中' }}</span>
                </div>
                <p v-if="!riderRecords?.rides.length" class="empty-table">还没有骑行收益记录。</p>
              </div>
            </article>
          </template>

          <template v-if="current === 'experiments'">
            <div class="notice"><FlaskConical :size="18"/><span>独立回放，不改变工作台库存。三种策略使用相同初始库存、请求序列及 2 辆运输车。距离口径：{{ data.distanceModel }}。</span><button class="text-button" :disabled="matrixRefreshing" @click="refreshMatrix">{{ matrixRefreshing ? '刷新中…' : '刷新矩阵' }}</button></div><article class="panel experiment-config"><div class="experiment-config-title"><div class="section-icon"><FlaskConical :size="24"/></div><div><h2>策略对比实验</h2><p>3 小时 · 1,260 次模拟借车请求 · 可重复多种子回放</p></div></div><button class="experiment-settings-toggle" :aria-expanded="experimentSettings" @click="experimentSettings = !experimentSettings">{{ experimentSettings ? '收起设置' : `种子 ${seed} · 重复 ${runs} 次` }}<ChevronDown :size="15" :class="['chev', { open: experimentSettings }]"/></button><button class="btn primary" :disabled="experimenting || activeTasks.length > 0 || !plannerReady" @click="runExperiment"><Play :size="16"/>{{ experimenting ? '正在运行…' : '运行对比实验' }}</button><div v-if="experimentSettings" class="experiment-settings-body"><label>随机种子<input type="number" v-model.number="seed" min="0" max="999999" aria-label="随机种子"/></label><label>重复次数<select v-model.number="runs" aria-label="重复次数" :disabled="experimenting"><option v-for="n in [1,3,5,10]" :key="n" :value="n">{{ n }} 次</option></select></label></div></article><p v-if="activeTasks.length" class="inline-warning">请先完成或取消调度任务，确保实验从完整的地面库存开始。</p>
            <article class="panel plan-compare">
              <div class="plan-compare-head"><div><h2>路径规划对比</h2><p>三个场景、每场景按库存 ±5% 各取三个样本，比较「贪心串行」与「OR-Tools 取送路径」的总里程和用时。</p></div><div class="plan-compare-actions"><button v-if="planCompare" class="btn" @click="downloadCompareCsv">导出 CSV</button><button class="btn primary" :disabled="comparing" @click="comparePlans">{{ comparing ? '计算中…' : '运行对比' }}</button></div></div>
              <div v-if="planCompare" class="list-rows compare-rows">
                <div v-for="row in planCompare" :key="row.scenario" class="list-row compare-row">
                  <span class="row-icon"><ChartNoAxesCombined :size="18"/></span>
                  <div class="row-main"><strong>{{ scenarioNames[row.scenario] }}</strong><span>{{ row.samples }} 个样本 · {{ row.legs }} 单<template v-if="row.ortoolsKm != null"> · 贪心 {{ row.greedyKmMin }}–{{ row.greedyKmMax }} km</template></span></div>
                  <div class="row-side">
                    <span class="compare-metric">贪心 <b>{{ row.greedyKm }}</b> km</span>
                    <span class="compare-metric">OR-Tools <b>{{ row.ortoolsKm ?? '—' }}</b> km</span>
                    <strong :class="['compare-delta', rowDelta(row) != null && rowDelta(row) <= 0 ? 'good' : '']">{{ rowDelta(row) == null ? '—' : (rowDelta(row) > 0 ? '+' : '') + rowDelta(row) + '%' }}</strong>
                  </div>
                </div>
                <div class="compare-chart-legend"><span><i class="legend-dot blue"></i>贪心串行</span><span><i class="legend-dot green"></i>OR-Tools</span><span class="muted">柱高为均值，细线为样本范围</span></div>
                <svg class="compare-chart" viewBox="0 0 720 250" role="img" aria-label="各场景贪心与 OR-Tools 总里程对比">
                  <line v-for="tick in [0,1,2,3]" :key="tick" x1="40" :x2="700" :y1="200 - tick * 50" :y2="200 - tick * 50" stroke="#f0f0f2"/>
                  <g v-for="(row,i) in planCompare" :key="row.scenario">
                    <line :x1="91 + i * 220" :x2="91 + i * 220" :y1="chartY(row.greedyKmMax)" :y2="chartY(row.greedyKmMin)" stroke="#a1a1a6" stroke-width="1"/>
                    <line :x1="86 + i * 220" :x2="96 + i * 220" :y1="chartY(row.greedyKmMax)" :y2="chartY(row.greedyKmMax)" stroke="#a1a1a6" stroke-width="1"/>
                    <line :x1="86 + i * 220" :x2="96 + i * 220" :y1="chartY(row.greedyKmMin)" :y2="chartY(row.greedyKmMin)" stroke="#a1a1a6" stroke-width="1"/>
                    <rect :x="70 + i * 220" :y="chartY(row.greedyKm)" width="42" :height="200 - chartY(row.greedyKm)" rx="4" fill="#d2d2d7"/>
                    <text :x="91 + i * 220" :y="chartY(row.greedyKmMax) - 7" text-anchor="middle" class="chart-value">{{ row.greedyKm }}</text>
                    <template v-if="row.ortoolsKm != null">
                      <line :x1="141 + i * 220" :x2="141 + i * 220" :y1="chartY(row.ortoolsKmMax)" :y2="chartY(row.ortoolsKmMin)" stroke="#0a7c5a" stroke-width="1"/>
                      <line :x1="136 + i * 220" :x2="146 + i * 220" :y1="chartY(row.ortoolsKmMax)" :y2="chartY(row.ortoolsKmMax)" stroke="#0a7c5a" stroke-width="1"/>
                      <line :x1="136 + i * 220" :x2="146 + i * 220" :y1="chartY(row.ortoolsKmMin)" :y2="chartY(row.ortoolsKmMin)" stroke="#0a7c5a" stroke-width="1"/>
                      <rect :x="120 + i * 220" :y="chartY(row.ortoolsKm)" width="42" :height="200 - chartY(row.ortoolsKm)" rx="4" fill="#0a7c5a"/>
                      <text :x="141 + i * 220" :y="chartY(row.ortoolsKmMax) - 7" text-anchor="middle" class="chart-value">{{ row.ortoolsKm }}</text>
                    </template>
                    <text :x="120 + i * 220" y="236" text-anchor="middle" class="chart-label">{{ scenarioNames[row.scenario] }}</text>
                  </g>
                </svg>
              </div>
              <p v-else class="plan-compare-hint">候选对按当前供需临时生成，不改变工作台里的任务；OR-Tools 不可用时该行显示「不可用」。</p>
            </article>
            <div v-if="!summary" class="panel experiment-placeholder"><div class="experiment-placeholder-icon"><ChartNoAxesCombined :size="42"/></div><h2>让数据回答，哪种策略更合适</h2><p>对比借车成功率、缺车时长与搬运成本。多种子重复运行，同时给出均值与波动范围。</p><div class="strategy-chips"><span>01 不调度</span><span>02 阈值就近调度</span><span>03 预测优先调度</span></div></div>
            <template v-else>
              <div class="experiment-meta"><span>种子 {{ summary.runCount > 1 ? `${summary.baseSeed}–${summary.baseSeed + summary.runCount - 1}` : summary.baseSeed }}</span><span>{{ scenarioNames[summary.scenario] }}</span><span>{{ summary.durationMinutes }} 分钟 · {{ summary.runCount }} 次独立回放</span></div>
              <div class="experiment-results"><article v-for="(result,i) in summary.results" :key="result.strategy" :class="['panel result-card', { featured:i===2 }]"><span class="result-index">POLICY 0{{ i+1 }}</span><h2>{{ strategyNames[result.strategy] }}</h2><div class="result-rate">{{ result.serviceRateAvg.toFixed(1) }}<small>%</small></div><p>{{ summary.runCount > 1 ? '平均借车成功率' : '借车成功率' }}</p><div class="result-bar"><div :style="{ width:result.serviceRateAvg+'%' }"></div></div><div v-if="summary.runCount > 1" class="result-range">范围 {{ result.serviceRateMin.toFixed(1) }}–{{ result.serviceRateMax.toFixed(1) }}%</div><div class="summary-row"><span>{{ summary.runCount > 1 ? '平均缺车总时长' : '区域缺车总时长' }}</span><strong>{{ result.shortageMinutesAvg }} 分钟</strong></div><div class="summary-row"><span>{{ summary.runCount > 1 ? '平均搬运里程' : '搬运里程' }}</span><strong>{{ result.distanceAvg }} km</strong></div><div class="summary-row"><span>{{ summary.runCount > 1 ? '平均搬运单车' : '搬运单车' }}</span><strong>{{ result.movedAvg }} 辆</strong></div></article></div>
              <details class="panel disclosure seed-table"><summary><span><strong>逐种子成功率</strong><small>同一种子下三种策略共享请求序列</small></span><ChevronRight :size="18" class="disclosure-icon"/></summary><div class="disclosure-body table-scroll"><table><thead><tr><th>种子</th><th>请求数</th><th>不调度</th><th>阈值就近</th><th>预测优先</th></tr></thead><tbody><tr v-for="run in summary.runs" :key="run.seed"><td>{{ run.seed }}</td><td>{{ run.results[0].requested }}</td><td>{{ run.results[0].serviceRate.toFixed(1) }}%</td><td>{{ run.results[1].serviceRate.toFixed(1) }}%</td><td>{{ run.results[2].serviceRate.toFixed(1) }}%</td></tr></tbody></table></div></details>
              <article class="panel experiment-report"><div><h2><CheckCircle2 :size="19"/>实验完成 · 车辆守恒检查通过</h2><p>种子 {{ summary.baseSeed }}{{ summary.runCount > 1 ? ' 起连续 ' + summary.runCount + ' 个' : '' }} · {{ scenarioNames[summary.scenario] }} · {{ summary.durationMinutes }} 分钟</p></div><button class="btn" @click="downloadCsv"><Download :size="16"/>导出 CSV</button></article>
              <p class="experiment-caveat">{{ summary.model }}。结果由仿真计算，不预设提升幅度；统计包含运行结束时仍在途的车辆。</p>
            </template>
          </template>
          <footer class="page-footer"><span>交点 CycleFlow <span class="footer-dot">·</span> 让校园周边出行更有序</span><span>真实地区背景 / 模拟运营数据</span></footer>
            </div>
          </transition>
        </template>
      </main>
    </div>
    <div v-if="toast" class="toast" role="status"><Info :size="18"/><span>{{ toast }}</span><button aria-label="关闭提示" @click="toast = ''"><X :size="16"/></button></div>
    <dialog v-if="data" ref="demoDialog" class="modal demo-modal"><div class="modal-heading"><div><span class="eyebrow">DEMO CONTROL</span><h2>演示控制</h2></div><button type="button" class="icon-button" aria-label="关闭演示控制" @click="demoDialog.close()"><X :size="21"/></button></div><div class="demo-clock"><div><span>场景时钟</span><strong>{{ time(data.minute) }}</strong></div><span class="muted">播放时每分钟推进</span></div><div class="demo-actions"><button class="btn" :disabled="busy || !canOperate" @click="advance()">+5 分钟</button><button class="btn primary" :disabled="!canOperate" @click="togglePlay"><Pause v-if="playing" :size="16"/><Play v-else :size="16"/>{{ playing ? '暂停播放' : '播放搬运过程' }}</button><button class="btn" @click="playSpeed = playSpeed === 1 ? 2 : playSpeed === 2 ? 4 : 1">{{ playSpeed }}× 速度</button></div><label>演示场景<select :value="data.scenario" @change="changeScenario($event.target.value)" aria-label="选择演示场景"><option v-for="(name,id) in scenarioNames" :key="id" :value="id">{{ name }}</option></select></label><p class="muted">{{ activeTasks.length ? '已有任务占用运输资源，在途补车量已计入缺口计算。' : '优先处理缺口较大的区域，再匹配附近有富余库存的停车点。' }}</p><p class="muted">切换场景会重置区域、库存和任务；这里的播放只推进搬运过程，不生成骑行订单。</p><div class="demo-matrix"><span>距离口径：{{ data.distanceModel }}</span><button class="text-button" :disabled="matrixRefreshing" @click="refreshMatrix">{{ matrixRefreshing ? '刷新中…' : '刷新矩阵' }}</button></div><div class="modal-actions"><button class="btn" @click="demoDialog.close()">关闭</button><button class="btn primary" @click="openExperimentsFromDemo"><FlaskConical :size="16"/>运行对比实验</button></div></dialog>
    <dialog ref="editDialog" class="modal"><form @submit.prevent="saveEdit"><div class="modal-heading"><div><span class="eyebrow">REGION SETTINGS</span><h2>编辑停车区域</h2></div><button type="button" class="icon-button" aria-label="关闭编辑" @click="editDialog.close()"><X :size="21"/></button></div><label>区域名称<input v-model="edit.name" required maxlength="40"/></label><div class="form-row"><label>停车容量（辆）<input v-model.number="edit.capacity" type="number" required min="1" max="500"/></label><label>安全库存（辆）<input v-model.number="edit.safetyStock" type="number" required min="0" :max="edit.capacity"/></label></div><p class="muted">容量需容纳现有车辆和已安排的调入车辆。保存后自动更新供需判断。</p><p v-if="formError" class="form-error" role="alert">{{ formError }}</p><div class="modal-actions"><button type="button" class="btn" @click="editDialog.close()">取消</button><button class="btn primary" type="submit" :disabled="busy || !canOperate">保存区域</button></div></form></dialog>
    <dialog ref="resetDialog" class="modal"><div class="modal-heading"><h2>重置演示场景</h2><button class="icon-button" aria-label="关闭重置窗口" @click="resetDialog.close()"><X :size="21"/></button></div><p>将切换为「{{ scenarioNames[pendingScenario] }}」，恢复 12 个初始区域与 500 辆单车，清除本地调度任务和区域修改。</p><div class="modal-actions"><button class="btn" @click="resetDialog.close()">保留当前状态</button><button class="btn primary" :disabled="busy || !canOperate" @click="reset">确认重置</button></div></dialog>
    <dialog ref="infoDialog" class="modal info-modal"><div class="modal-heading"><h2>关于这一版</h2><button class="icon-button" aria-label="关闭数据说明" @click="infoDialog.close()"><X :size="21"/></button></div><span class="green-pill">毕业设计原型 · V0.1</span><h3>研究地区</h3><p>以西南交通大学犀浦校区周边为背景。校门、轨道接驳和社区区域用于设计演示场景；真实街道底图默认高德地图、加载失败时回退 OpenStreetMap；示范停车点位与容量未实测，绿色路线为演示用驾车规划。</p><h3>数据与算法</h3><p>12 个区域、500 辆单车、2 辆载量为 20 的运输车。预测采用场景规则，调度采用“缺口优先＋就近匹配”；每项任务保存生成原因；仿真实验可用多个连续种子重复运行，报告均值与范围。尚未接入运营商数据或训练模型。</p><h3>两种演示方式</h3><p>工作台播放搬运任务，时间前进时更新到达库存；仿真实验另外回放借还请求，只有成功借车才生成还车事件。距离优先取高德道路矩阵，未就绪时回退网格估算；运输车假定在取车点就近待命。</p><h3>本地保存</h3><p>Spring Boot 后端将区域设置和任务保存在 H2 文件数据库中，刷新页面不会丢失；重置场景会恢复初始数据。</p><div class="modal-actions"><button class="btn primary" @click="infoDialog.close()">了解了</button></div></dialog>
  </div>
</template>
