<script setup>
import { computed, ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { Bike, LayoutDashboard, MapPin, ChartNoAxesCombined, Route, FlaskConical, ArrowUpRight, ArrowRight, ChevronRight, Search, SlidersHorizontal, RefreshCw, Play, Pause, Download, X, Check, Clock3, Truck, CircleAlert, Info, PanelLeftClose, Menu, Activity, Leaf, Settings2, CircleHelp, CheckCircle2 } from 'lucide-vue-next'
import RegionMap from './components/RegionMap.vue'

const tabs = [
  { id: 'overview', label: '运营总览', icon: LayoutDashboard, en: 'OVERVIEW' },
  { id: 'regions', label: '停车区域', icon: MapPin, en: 'REGIONS' },
  { id: 'forecast', label: '需求预测', icon: ChartNoAxesCombined, en: 'FORECAST' },
  { id: 'dispatch', label: '智能调度', icon: Route, en: 'DISPATCH' },
  { id: 'experiments', label: '仿真实验', icon: FlaskConical, en: 'EXPERIMENT' },
]
const current = ref(tabs.some(t => t.id === location.hash.slice(1)) ? location.hash.slice(1) : 'overview')
const page = computed(() => tabs.find(t => t.id === current.value))
const data = ref(null), loading = ref(true), busy = ref(false), error = ref(''), toast = ref(''), selected = ref(1), search = ref(''), filter = ref('all'), mobileNav = ref(false)
const forecasting = ref(false), forecast = ref([]), experiment = ref(null), seed = ref(42), experimenting = ref(false), playing = ref(false)
const editDialog = ref(null), resetDialog = ref(null), infoDialog = ref(null), edit = ref({}), pendingScenario = ref('morning'), formError = ref('')
const scenarioNames = { morning: '工作日早高峰', evening: '工作日晚高峰', weekend: '周末出行' }
const typeNames = { gate: '校门接驳', metro: '轨道接驳', residential: '居住区域', commercial: '商业区域' }
const statusNames = { PLANNED: '待执行', RUNNING: '运输中', COMPLETED: '已完成', CANCELLED: '已取消' }
const strategyNames = { none: '不调度', threshold: '阈值就近调度', predictive: '预测优先调度' }
const regions = computed(() => data.value?.regions || [])
const tasks = computed(() => data.value?.tasks || [])
const activeTasks = computed(() => tasks.value.filter(t => ['PLANNED', 'RUNNING'].includes(t.status)))
const alerts = computed(() => [...regions.value].filter(r => r.shortage > 0).sort((a,b) => b.shortage - a.shortage))
const shortage = computed(() => alerts.value.reduce((sum,r) => sum + r.shortage, 0))
const selectedRegion = computed(() => regions.value.find(r => r.id === selected.value) || regions.value[0])
const filteredRegions = computed(() => regions.value.filter(r => r.name.includes(search.value) && (filter.value === 'all' || (filter.value === 'shortage' ? r.shortage > 0 : r.kind === filter.value))))
const time = minute => `${String(Math.floor(minute / 60) % 24).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`
const regionName = id => regions.value.find(r => r.id === id)?.name || '未知区域'
let interval, toastTimer, forecastRequest = 0

async function api(path, method = 'GET', body) {
  const response = await fetch('/api' + path, { method, headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined })
  const result = await response.json()
  if (!response.ok) throw new Error(result.detail || result.message || '操作失败，请稍后重试')
  return result
}
function notify(message) { toast.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => toast.value = '', 4500) }
async function load() {
  loading.value = true; error.value = ''
  try { data.value = await api('/state') } catch { error.value = '暂时无法连接调度服务，请确认后端已启动后重试。' }
  finally { loading.value = false }
}
async function mutate(path, body, message, method = 'POST') {
  if (busy.value) return false
  busy.value = true
  try { data.value = await api(path, method, body); experiment.value = null; if (message) notify(message); return true }
  catch (e) { notify(e.message); return false }
  finally { busy.value = false }
}
function navigate(id) { current.value = id; location.hash = id; mobileNav.value = false }
function onHash() { const id = location.hash.slice(1); if (tabs.some(t => t.id === id)) current.value = id }
async function generate() { if (await mutate('/dispatch/generate', null, '已生成调度方案，车辆与停车容量已检查')) navigate('dispatch') }
async function advance() { await mutate('/simulation/advance'); if (!tasks.value.some(t => t.status === 'RUNNING')) playing.value = false }
function togglePlay() {
  if (!tasks.value.some(t => t.status === 'RUNNING')) return notify('请先在调度中心开始一个任务')
  playing.value = !playing.value
}
function requestReset(scenario = data.value.scenario) { pendingScenario.value = scenario; playing.value = false; resetDialog.value.showModal() }
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
  experimenting.value = true
  try { experiment.value = await api('/experiments?seed=' + seed.value); notify('三种策略已完成相同请求序列的独立回放') }
  catch (e) { notify(e.message) } finally { experimenting.value = false }
}
function downloadCsv() {
  const rows = [['策略','请求数','成功借车','借车成功率(%)','区域缺车分钟','取送里程(km)','搬运数量','车辆总数'], ...experiment.value.results.map(r => [strategyNames[r.strategy],r.requested,r.served,r.serviceRate,r.shortageMinutes,r.distance,r.moved,r.endingTotal])]
  const blob = new Blob(['\ufeff' + rows.map(r => r.join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = `交点-仿真实验-${experiment.value.scenario}-seed${experiment.value.seed}.csv`; a.click(); URL.revokeObjectURL(url)
}
const forecastMax = computed(() => Math.max(20, ...forecast.value.flatMap(p => [p.outflow, p.inflow])) )
const plotLine = key => forecast.value.map((p,i) => `${65+i*85},${255-p[key]/forecastMax.value*200}`).join(' ')
watch([current, selected, () => data.value?.minute, () => data.value?.scenario], () => { if (current.value === 'forecast') loadForecast() })
watch(current, () => { if (current.value !== 'dispatch' && current.value !== 'overview') playing.value = false })
onMounted(async () => { window.addEventListener('hashchange', onHash); await load(); if (current.value === 'forecast') loadForecast(); interval = setInterval(() => { if (playing.value && !busy.value) advance() }, 1400) })
onUnmounted(() => { clearInterval(interval); clearTimeout(toastTimer); window.removeEventListener('hashchange', onHash) })
</script>

<template>
  <div class="app-shell">
    <div v-if="mobileNav" class="sidebar-scrim" @click="mobileNav = false"></div>
    <aside class="sidebar" :class="{ 'is-open': mobileNav }">
      <a class="brand" href="#overview" @click="navigate('overview')"><div class="brand-icon"><Bike :size="27" stroke-width="1.8"/></div><div><strong>交点<span>CycleFlow</span></strong><small>共享单车智能调度</small></div></a>
      <div class="workspace-label">工作空间 <span>01</span></div>
      <nav aria-label="主导航"><button v-for="tab in tabs" :key="tab.id" :class="['nav-item', { active: current === tab.id }]" :aria-current="current === tab.id ? 'page' : undefined" @click="navigate(tab.id)"><component :is="tab.icon" :size="20"/><span>{{ tab.label }}</span><span v-if="tab.id === 'dispatch' && activeTasks.length" class="nav-badge">{{ activeTasks.length }}</span></button></nav>
      <div class="sidebar-bottom"><div class="project-card"><div class="project-top"><Leaf :size="19"/><span>校园周边 · 微出行</span></div><strong>西南交通大学</strong><p>犀浦校区研究区域</p><div class="project-rule"></div><span class="version">毕业设计原型 <b>V 0.1</b></span></div><button class="help-link" @click="infoDialog.showModal()"><CircleHelp :size="18"/> 数据与模型说明 <ChevronRight :size="15"/></button><div class="profile"><div class="avatar">交</div><div><strong>演示工作台</strong><span>本地运行 · 单用户</span></div><span class="connection" :class="{ offline: !!error }"></span></div></div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb"><button class="mobile-menu icon-button" aria-label="打开导航" @click="mobileNav = !mobileNav"><Menu :size="22"/></button><span>调度工作台</span><ChevronRight :size="14"/><strong>{{ page.label }}</strong></div><div class="topbar-right"><span class="demo-badge"><span></span>仿真演示</span><span class="header-separator"></span><MapPin :size="15"/><span class="location-label">成都 · 犀浦</span></div></header>
      <main>
        <div v-if="loading" class="loading-panel"><div class="spinner"></div><h2>正在加载调度工作台</h2><p>读取停车区域与任务记录</p></div>
        <div v-else-if="error" class="loading-panel"><CircleAlert :size="40"/><h2>服务暂不可用</h2><p>{{ error }}</p><button class="btn primary" @click="load">重新连接</button></div>
        <template v-else-if="data">
          <section class="page-heading"><div><span class="eyebrow">{{ page.en }}</span><h1>{{ page.label }}<span v-if="current === 'overview'" class="heading-dot"></span></h1><p>{{ { overview: '从供需变化，到每一次精准调度。', regions: '管理服务区域的停车容量与安全库存。', forecast: '提前看见需求，安排下一次补车。', dispatch: '将补车建议转化为可执行的搬运任务。', experiments: '用相同的出行请求，比较不同调度策略。' }[current] }}</p></div><div class="heading-actions"><template v-if="current === 'overview' || current === 'dispatch'"><button class="btn" :disabled="busy" @click="requestReset()"><RefreshCw :size="16"/>重置场景</button><button class="btn primary" :disabled="busy" @click="generate"><Route :size="17"/>{{ busy ? '处理中…' : '生成调度方案' }}</button></template><button v-else class="btn" @click="infoDialog.showModal()"><Info :size="16"/>数据说明</button></div></section>

          <template v-if="current === 'overview'">
            <div class="stats-grid">
              <article class="stat-card"><div class="stat-head"><span>区域单车总量</span><Bike :size="19"/></div><div class="stat-value">{{ data.totalBikes }}<small>辆</small></div><div class="stat-foot"><span class="neutral-pill">{{ data.transitBikes ? data.transitBikes + ' 辆运输中' : '库存守恒' }}</span><span>覆盖 {{ regions.length }} 个区域</span></div></article>
              <article class="stat-card"><div class="stat-head"><span>待补车区域</span><MapPin :size="19"/></div><div class="stat-value">{{ alerts.length }}<small>个</small><span class="mini-bars"><i></i><i></i><i></i><i></i><i></i><i></i></span></div><div class="stat-foot"><span class="orange-pill">预计缺口 {{ shortage }} 辆</span><span>未来 30 分钟</span></div></article>
              <article class="stat-card"><div class="stat-head"><span>进行中的调度</span><Truck :size="19"/></div><div class="stat-value">{{ activeTasks.length }}<small>项</small></div><div class="stat-foot"><span class="green-pill">{{ 2 - activeTasks.length }} 辆运输车空闲</span><span>载量 20 辆 / 车</span></div></article>
              <article class="stat-card"><div class="stat-head"><span>已完成任务</span><CheckCircle2 :size="19"/></div><div class="stat-value">{{ data.completedTasks }}<small>项</small></div><div class="stat-foot"><span class="neutral-pill">搬运里程 {{ data.totalDistance.toFixed(1) }} km</span><span>本次场景</span></div></article>
            </div>
            <section class="overview-grid">
              <article class="panel map-panel"><div class="panel-heading"><div><h2>区域供需分布</h2><span>{{ regions.length }} 个示范停车区域</span></div><button class="text-button" @click="navigate('regions')">区域管理<ArrowUpRight :size="16"/></button></div><RegionMap :regions="regions" :selected="selected" :tasks="tasks" @select="selected = $event"/><div class="map-detail" v-if="selectedRegion"><div class="detail-pin"><MapPin :size="21"/></div><div><strong>{{ selectedRegion.name }}</strong><span>{{ typeNames[selectedRegion.kind] }} · 容量 {{ selectedRegion.capacity }} 辆</span></div><div class="map-detail-metric"><strong>{{ selectedRegion.stock }}<small> / {{ selectedRegion.target }}</small></strong><span>现有 / 目标库存</span></div><button class="icon-button" aria-label="查看所选区域预测" @click="navigate('forecast')"><ChevronRight :size="21"/></button></div></article>
              <aside class="panel alert-panel"><div class="panel-heading"><h2>补车优先级<span class="count-badge">{{ alerts.length }}</span></h2><span class="tiny-label">30 MIN</span></div><p class="panel-description">结合预期借还量与安全库存排序</p><div class="alert-list"><button v-for="(r,i) in alerts.slice(0,4)" :key="r.id" :class="['alert-item', { selected: selected === r.id }]" @click="selected = r.id"><div class="alert-item-top"><span class="rank">{{ String(i+1).padStart(2,'0') }}</span><strong>{{ r.name }}</strong><ChevronRight :size="15"/></div><div class="alert-item-body"><span>现有 <b>{{ r.stock }}</b> 辆</span><span class="shortage-label">待补 {{ r.shortage }} 辆</span></div><div class="stock-track"><div :style="{ width: Math.min(100,r.stock/r.target*100)+'%' }"></div></div></button><div v-if="!alerts.length" class="empty-mini"><CheckCircle2 :size="32"/><strong>补车需求已覆盖</strong><p>现有库存与在途任务已覆盖当前目标。</p></div></div><div class="insight"><Activity :size="18"/><div><strong>调度提示</strong><p>{{ activeTasks.length ? '已有任务占用运输资源，在途补车量已计入缺口计算。' : '优先处理缺口较大的区域，再匹配附近有富余库存的停车点。' }}</p></div></div><button class="btn full-width" @click="navigate('dispatch')">进入调度中心<ChevronRight :size="16"/></button></aside>
            </section>
            <section class="bottom-grid"><article class="panel scenario-panel"><div class="section-icon"><Clock3 :size="23"/></div><div class="scenario-info"><h2>场景时钟 <span>{{ time(data.minute) }}</span></h2><p>每步推进 5 分钟 · 此处仅播放搬运过程</p></div><select aria-label="选择演示场景" :value="data.scenario" @change="requestReset($event.target.value)"><option v-for="(name,id) in scenarioNames" :key="id" :value="id">{{ name }}</option></select><button class="icon-button play-button" :aria-label="playing ? '暂停播放' : '播放搬运过程'" @click="togglePlay"><Pause v-if="playing" :size="18"/><Play v-else :size="18"/></button><button class="btn compact" :disabled="busy" @click="advance">+5 分钟</button></article><article class="experiment-entry" @click="navigate('experiments')" @keydown.enter="navigate('experiments')" tabindex="0" role="button"><FlaskConical :size="24"/><div><h2>调度真的有效吗？</h2><p>运行 3 小时仿真，比较三种策略</p></div><ArrowUpRight :size="21"/></article></section>
          </template>

          <template v-if="current === 'regions'">
            <article class="panel table-panel"><div class="table-toolbar"><div class="search-field"><Search :size="18"/><input v-model="search" placeholder="搜索停车区域" aria-label="搜索停车区域"/></div><select v-model="filter" aria-label="筛选停车区域"><option value="all">全部区域</option><option value="shortage">待补车区域</option><option v-for="(name,key) in typeNames" :value="key" :key="key">{{ name }}</option></select><span class="table-count">共 {{ filteredRegions.length }} 个区域</span></div><div class="table-scroll"><table><thead><tr><th>停车区域</th><th>区域类型</th><th>当前库存 / 容量</th><th>安全库存</th><th>目标库存</th><th>供需状态</th><th>操作</th></tr></thead><tbody><tr v-for="r in filteredRegions" :key="r.id"><td><span class="region-number">{{ String(r.id).padStart(2,'0') }}</span><strong>{{ r.name }}</strong></td><td>{{ typeNames[r.kind] }}</td><td><div class="table-stock"><span>{{ r.stock }}<small> / {{ r.capacity }}</small></span><div class="stock-track"><div :style="{ width:r.stock/r.capacity*100+'%', background:r.shortage?'#e7a14c':'#14a583' }"></div></div></div></td><td>{{ r.safetyStock }} 辆</td><td>{{ r.target }} 辆</td><td><span :class="['status-pill', r.shortage ? 'orange-pill' : 'green-pill']">{{ r.shortage ? '待补 ' + r.shortage + ' 辆' : r.reservedIn ? '补车已安排' : '库存充足' }}</span></td><td><button class="text-button" @click="openEdit(r)">编辑</button></td></tr><tr v-if="!filteredRegions.length"><td colspan="7" class="empty-table">没有找到匹配的停车区域</td></tr></tbody></table></div><div class="table-footer"><Info :size="16"/>区域为示范点位；编辑容量与安全库存后，补车建议会重新计算。</div></article>
          </template>

          <template v-if="current === 'forecast'">
            <div class="notice"><Info :size="18"/><span>当前为规则需求模型，展示场景中的预期借还趋势；尚未使用犀浦实测订单训练。</span></div><div class="forecast-layout"><article class="panel forecast-main"><div class="panel-heading"><h2>未来 2 小时借还趋势</h2><select v-model.number="selected" aria-label="选择预测区域"><option v-for="r in regions" :key="r.id" :value="r.id">{{ r.name }}</option></select></div><div class="chart-legend"><span><i class="legend-dot green"></i>预计借出</span><span><i class="legend-dot blue"></i>预计归还</span><span class="muted">每 15 分钟 · 单位：辆</span></div><div v-if="forecasting" class="chart-loading">正在计算预测…</div><svg v-else class="forecast-chart" viewBox="0 0 740 310" role="img" aria-label="未来两小时每15分钟预计借出和归还车辆折线图"><defs><linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0aa580" stop-opacity=".18"/><stop offset="1" stop-color="#0aa580" stop-opacity="0"/></linearGradient></defs><g v-for="i in 5" :key="i"><line x1="55" x2="700" :y1="255-(i-1)*50" :y2="255-(i-1)*50" stroke="#e7eeeb" stroke-dasharray="4 5"/><text x="35" :y="260-(i-1)*50" text-anchor="end" class="chart-label">{{ Math.round((i-1)*forecastMax/4) }}</text></g><polygon v-if="forecast.length" :points="`65,255 ${plotLine('outflow')} 660,255`" fill="url(#chartGradient)"/><polyline :points="plotLine('outflow')" fill="none" stroke="#079b78" stroke-width="3" stroke-linejoin="round"/><polyline :points="plotLine('inflow')" fill="none" stroke="#7190c9" stroke-width="3" stroke-dasharray="7 5"/><g v-for="(p,i) in forecast" :key="p.time"><circle :cx="65+i*85" :cy="255-p.outflow/forecastMax*200" r="4" fill="#fff" stroke="#079b78" stroke-width="2"/><text :x="65+i*85" y="286" text-anchor="middle" class="chart-label">{{ p.time }}</text></g></svg><div class="forecast-note">基于当前场景推演；“供需余额”为现有库存加累计归还、减累计借出，负值代表潜在缺口。</div></article><article class="panel forecast-summary"><div class="panel-heading"><h2>30 分钟供需判断</h2></div><span class="large-number">{{ selectedRegion?.shortage || 0 }}<small>辆</small></span><p class="muted">建议补充数量</p><div class="summary-row"><span>当前可用车辆</span><strong>{{ selectedRegion?.stock }} 辆</strong></div><div class="summary-row"><span>预计借出 / 归还</span><strong>{{ selectedRegion?.predictedOut }} / {{ selectedRegion?.predictedIn }} 辆</strong></div><div class="summary-row"><span>安全库存</span><strong>{{ selectedRegion?.safetyStock }} 辆</strong></div><div class="summary-row"><span>已安排调入</span><strong>{{ selectedRegion?.reservedIn }} 辆</strong></div><button class="btn primary full-width" :disabled="busy" @click="generate"><Route :size="17"/>生成调度方案</button></article></div><article class="panel table-panel forecast-table"><div class="panel-heading"><h2>分时段预测明细</h2><span>不含新调度的供需余额</span></div><div class="table-scroll"><table><thead><tr><th>时段结束</th><th>预计借出</th><th>预计归还</th><th>累计供需余额</th><th>提示</th></tr></thead><tbody><tr v-for="p in forecast" :key="p.time"><td>{{ p.time }}</td><td>{{ p.outflow }} 辆</td><td>{{ p.inflow }} 辆</td><td>{{ p.projectedStock }} 辆</td><td><span :class="p.projectedStock < 0 ? 'orange-pill' : 'green-pill'">{{ p.projectedStock < 0 ? '存在潜在缺口' : '余额非负' }}</span></td></tr></tbody></table></div></article>
          </template>

          <template v-if="current === 'dispatch'">
            <div class="dispatch-toolbar"><div><span class="live-dot"></span><strong>运输资源</strong><span>2 辆运输车 · 每车载量 20 辆</span></div><div class="clock-controls"><Clock3 :size="17"/><strong>{{ time(data.minute) }}</strong><button class="btn compact" :disabled="busy" @click="advance">+5 分钟</button><button class="btn compact" @click="togglePlay"><Pause v-if="playing" :size="15"/><Play v-else :size="15"/>{{ playing ? '暂停' : '播放' }}</button></div></div>
            <div class="dispatch-layout"><article class="panel dispatch-map"><div class="panel-heading"><h2>搬运路线</h2><span>两点取送 · 估算距离</span></div><RegionMap :regions="regions" :selected="selected" :tasks="tasks" @select="selected = $event"/><div class="table-footer"><Info :size="15"/>按缺口优先、就近供给生成；路线为模拟路网，不代表实测导航。</div></article><div class="task-list"><div v-if="!tasks.length" class="panel task-empty"><Route :size="42"/><h2>准备好下一次调度</h2><p>根据区域缺口、富余库存和运输车载量，生成可执行任务。</p><button class="btn primary" :disabled="busy" @click="generate">生成调度方案</button></div><article v-for="task in tasks" :key="task.id" class="panel task-card"><div class="task-header"><span>{{ task.id }}</span><span :class="['task-status', task.status.toLowerCase() ]">{{ statusNames[task.status] }}</span></div><div class="task-route"><div class="route-line"><i></i><span></span><i></i></div><div><strong>{{ regionName(task.sourceId) }}</strong><span>取车点</span><strong>{{ regionName(task.destinationId) }}</strong><span>送车点</span></div><div class="task-quantity">{{ task.quantity }}<small>辆</small></div></div><div class="task-meta"><span><Truck :size="14"/>运输车 0{{ task.vehicle }}</span><span>{{ task.distance.toFixed(1) }} km</span><span><Clock3 :size="14"/>{{ task.duration }} 分钟</span></div><div class="task-actions" v-if="task.status === 'PLANNED'"><button class="btn compact" :disabled="busy" @click="mutate(`/dispatch/${task.id}/cancel`,null,'任务已取消，预留库存已释放')">取消任务</button><button class="btn primary compact" :disabled="busy" @click="mutate(`/dispatch/${task.id}/start`,null,'取车完成，单车进入运输状态')"><Play :size="14"/>开始执行</button></div><div v-else-if="task.status === 'RUNNING'" class="task-progress"><span>预计 {{ time(task.startedMinute + task.duration) }} 送达</span><div class="stock-track"><div :style="{ width:Math.min(100,(data.minute-task.startedMinute)/task.duration*100)+'%' }"></div></div></div><div v-else-if="task.status === 'COMPLETED'" class="task-completed"><Check :size="15"/>{{ time(task.completedMinute) }} 已送达并更新库存</div></article></div></div>
          </template>

          <template v-if="current === 'experiments'">
            <div class="notice"><FlaskConical :size="18"/><span>独立回放，不改变工作台库存。三种策略使用相同初始库存、请求序列及 2 辆运输车。</span></div><article class="panel experiment-config"><div class="experiment-config-title"><div class="section-icon"><FlaskConical :size="24"/></div><div><h2>策略对比实验</h2><p>3 小时 · 1,260 次模拟借车请求 · 单次确定性回放</p></div></div><label>随机种子<input type="number" v-model.number="seed" min="0" max="999999" aria-label="随机种子"/></label><button class="btn primary" :disabled="experimenting || activeTasks.length > 0" @click="runExperiment"><Play :size="16"/>{{ experimenting ? '正在运行…' : '运行对比实验' }}</button></article><p v-if="activeTasks.length" class="inline-warning">请先完成或取消调度任务，确保实验从完整的地面库存开始。</p>
            <div v-if="!experiment" class="panel experiment-placeholder"><div class="experiment-placeholder-icon"><ChartNoAxesCombined :size="42"/></div><h2>让数据回答，哪种策略更合适</h2><p>对比借车成功率、缺车时长与搬运成本。结果由仿真计算，不预设提升幅度。</p><div class="strategy-chips"><span>01 不调度</span><span>02 阈值就近调度</span><span>03 预测优先调度</span></div></div>
            <template v-else><div class="experiment-results"><article v-for="(result,i) in experiment.results" :key="result.strategy" :class="['panel result-card', { featured:i===2 }]"><span class="result-index">POLICY 0{{ i+1 }}</span><h2>{{ strategyNames[result.strategy] }}</h2><div class="result-rate">{{ result.serviceRate.toFixed(1) }}<small>%</small></div><p>借车成功率</p><div class="result-bar"><div :style="{ width:result.serviceRate+'%' }"></div></div><div class="summary-row"><span>成功借车</span><strong>{{ result.served }} / {{ result.requested }}</strong></div><div class="summary-row"><span>区域缺车总时长</span><strong>{{ result.shortageMinutes }} 分钟</strong></div><div class="summary-row"><span>搬运里程</span><strong>{{ result.distance }} km</strong></div><div class="summary-row"><span>搬运单车</span><strong>{{ result.moved }} 辆</strong></div></article></div><article class="panel experiment-report"><div><h2><CheckCircle2 :size="19"/>实验完成 · 车辆守恒检查通过</h2><p>种子 {{ experiment.seed }} · {{ scenarioNames[experiment.scenario] }} · {{ experiment.durationMinutes }} 分钟</p></div><button class="btn" @click="downloadCsv"><Download :size="16"/>导出 CSV</button></article><p class="experiment-caveat">{{ experiment.model }}。单次结果仅用于演示；论文需增加多种子实验与实测数据验证。统计包含运行结束时仍在途的车辆。</p></template>
          </template>
          <footer class="page-footer"><span>交点 CycleFlow <span class="footer-dot">·</span> 让校园周边出行更有序</span><span>真实地区背景 / 模拟运营数据</span></footer>
        </template>
      </main>
    </div>
    <div v-if="toast" class="toast" role="status"><Info :size="18"/><span>{{ toast }}</span><button aria-label="关闭提示" @click="toast = ''"><X :size="16"/></button></div>
    <dialog ref="editDialog" class="modal"><form @submit.prevent="saveEdit"><div class="modal-heading"><div><span class="eyebrow">REGION SETTINGS</span><h2>编辑停车区域</h2></div><button type="button" class="icon-button" aria-label="关闭编辑" @click="editDialog.close()"><X :size="21"/></button></div><label>区域名称<input v-model="edit.name" required maxlength="40"/></label><div class="form-row"><label>停车容量（辆）<input v-model.number="edit.capacity" type="number" required min="1" max="500"/></label><label>安全库存（辆）<input v-model.number="edit.safetyStock" type="number" required min="0" :max="edit.capacity"/></label></div><p class="muted">容量需容纳现有车辆和已安排的调入车辆。保存后自动更新供需判断。</p><p v-if="formError" class="form-error" role="alert">{{ formError }}</p><div class="modal-actions"><button type="button" class="btn" @click="editDialog.close()">取消</button><button class="btn primary" type="submit" :disabled="busy">保存区域</button></div></form></dialog>
    <dialog ref="resetDialog" class="modal"><div class="modal-heading"><h2>重置演示场景</h2><button class="icon-button" aria-label="关闭重置窗口" @click="resetDialog.close()"><X :size="21"/></button></div><p>将切换为「{{ scenarioNames[pendingScenario] }}」，恢复 12 个初始区域与 500 辆单车，清除本地调度任务和区域修改。</p><div class="modal-actions"><button class="btn" @click="resetDialog.close()">保留当前状态</button><button class="btn primary" :disabled="busy" @click="reset">确认重置</button></div></dialog>
    <dialog ref="infoDialog" class="modal info-modal"><div class="modal-heading"><h2>关于这一版</h2><button class="icon-button" aria-label="关闭数据说明" @click="infoDialog.close()"><X :size="21"/></button></div><span class="green-pill">毕业设计原型 · V0.1</span><h3>研究地区</h3><p>以西南交通大学犀浦校区周边为背景。校门、轨道接驳和社区区域用于设计演示场景；真实街道底图来自 OpenStreetMap；示范停车点位与容量未实测，调度连线不代表道路导航。</p><h3>数据与算法</h3><p>12 个区域、500 辆单车、2 辆载量为 20 的运输车。预测采用场景规则，调度采用“缺口优先＋就近匹配”；尚未接入运营商、道路路线服务或训练模型。</p><h3>两种演示方式</h3><p>工作台播放搬运任务，时间前进时更新到达库存；仿真实验另外回放借还请求，只有成功借车才生成还车事件。距离为网格估算，运输车假定在取车点就近待命。</p><h3>本地保存</h3><p>Spring Boot 后端将区域设置和任务保存在 H2 文件数据库中，刷新页面不会丢失；重置场景会恢复初始数据。</p><div class="modal-actions"><button class="btn primary" @click="infoDialog.close()">了解了</button></div></dialog>
  </div>
</template>
