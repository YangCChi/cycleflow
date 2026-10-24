<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Plus, Minus, LocateFixed } from 'lucide-vue-next'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const props = defineProps({ regions: { type: Array, default: () => [] }, selected: Number, tasks: { type: Array, default: () => [] } })
const emit = defineEmits(['select'])
const container = ref(null)
const tileError = ref(false)
const loadNote = ref('')
const legendOpen = ref(false)
const mapEngine = ref('')
const campus = [30.7660599, 103.9844831]
const amapKey = import.meta.env.VITE_AMAP_KEY
const amapSecurity = import.meta.env.VITE_AMAP_SECURITY_CODE

let map
let amap
let leafletMarkers
let leafletRoutes
let amapMarkers = []
let amapRoutes = []
let tileLayer
let providerIndex = 0
let switchingProvider = false
let loadedTile = false
let failedTiles = 0
let amapLoader
let pluginRequested = false
const routePaths = new Map()
const routeRequests = new Set()
const routeFailed = new Set()

const tileProviders = [
  { url: 'https://tile.openstreetmap.de/{z}/{x}/{y}.png', attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors' },
  { url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}', attribution: 'Tiles &copy; Esri' }
]

const position = region => [region.latitude, region.longitude]
const isMapped = region => Number.isFinite(region?.latitude) && Number.isFinite(region?.longitude)
const tone = region => region.shortage > 0 ? 'shortage' : region.status === 'crowded' ? 'crowded' : 'balanced'

function wgs84ToGcj02(lng, lat) {
  if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return [lng, lat]
  const pi = Math.PI, axis = 6378245.0, ee = 0.00669342162296594323
  const transformLat = (x, y) => {
    let ret = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
    ret += (20 * Math.sin(6 * x * pi) + 20 * Math.sin(2 * x * pi)) * 2 / 3
    ret += (20 * Math.sin(y * pi) + 40 * Math.sin(y / 3 * pi)) * 2 / 3
    ret += (160 * Math.sin(y / 12 * pi) + 320 * Math.sin(y * pi / 30)) * 2 / 3
    return ret
  }
  const transformLng = (x, y) => {
    let ret = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
    ret += (20 * Math.sin(6 * x * pi) + 20 * Math.sin(2 * x * pi)) * 2 / 3
    ret += (20 * Math.sin(x * pi) + 40 * Math.sin(x / 3 * pi)) * 2 / 3
    ret += (150 * Math.sin(x / 12 * pi) + 300 * Math.sin(x / 30 * pi)) * 2 / 3
    return ret
  }
  let dLat = transformLat(lng - 105, lat - 35), dLng = transformLng(lng - 105, lat - 35)
  const radLat = lat / 180 * pi
  let magic = Math.sin(radLat)
  magic = 1 - ee * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180) / ((axis * (1 - ee)) / (magic * sqrtMagic) * pi)
  dLng = (dLng * 180) / (axis / sqrtMagic * Math.cos(radLat) * pi)
  return [lng + dLng, lat + dLat]
}

const gcjPosition = region => wgs84ToGcj02(region.longitude, region.latitude)

function drawLeafletMarkers() {
  if (!map) return
  leafletMarkers.clearLayers()
  for (const region of props.regions.filter(isMapped)) {
    const active = props.selected === region.id
    const size = active ? 18 : 14
    const icon = L.divIcon({
      className: 'demo-map-marker ' + tone(region) + (active ? ' selected' : ''),
      html: '',
      iconSize: [size, size],
      iconAnchor: [size / 2, size / 2]
    })
    const description = region.name + '，' + region.stock + ' 辆；演示停车点，位置未实测'
    L.marker(position(region), { icon, title: description, alt: description, keyboard: true })
      .on('click', () => emit('select', region.id)).addTo(leafletMarkers)
  }
}

function drawLeafletRoutes() {
  if (!map) return
  leafletRoutes.clearLayers()
  for (const task of props.tasks.filter(item => ['PLANNED', 'RUNNING'].includes(item.status))) {
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    L.polyline([position(source), position(destination)], {
      color: '#078763', weight: 4, opacity: .9, dashArray: '9 7',
      className: task.status === 'RUNNING' ? 'demo-map-route running' : 'demo-map-route'
    }).bindTooltip('调度任务示意连线，非实际道路路线').addTo(leafletRoutes)
  }
}

function fitLeafletArea() {
  if (!map) return
  const points = props.regions.filter(isMapped).map(position)
  if (points.length) map.fitBounds(L.latLngBounds(points), { padding: [45, 45], maxZoom: 15, animate: false })
  else map.setView(campus, 14)
}

function addTileLayer(index) {
  if (!map || index >= tileProviders.length) {
    tileError.value = true
    return
  }
  providerIndex = index
  const provider = tileProviders[index]
  if (tileLayer) map.removeLayer(tileLayer)
  failedTiles = 0
  tileLayer = L.tileLayer(provider.url, { maxZoom: 19, attribution: provider.attribution })
    .on('tileload', () => { loadedTile = true; tileError.value = false })
    .on('tileerror', () => {
      if (loadedTile || switchingProvider) return
      if (++failedTiles >= 3 && providerIndex + 1 < tileProviders.length) {
        switchingProvider = true
        window.setTimeout(() => { switchingProvider = false; addTileLayer(providerIndex + 1) }, 0)
      } else if (failedTiles >= 3) {
        tileError.value = true
      }
    })
    .addTo(map)
}

function initLeaflet() {
  map = L.map(container.value, { zoomControl: false, scrollWheelZoom: false, minZoom: 11, maxZoom: 19 })
  addTileLayer(0)
  leafletRoutes = L.layerGroup().addTo(map)
  leafletMarkers = L.layerGroup().addTo(map)
  drawLeafletRoutes()
  drawLeafletMarkers()
  fitLeafletArea()
  mapEngine.value = 'leaflet'
  window.setTimeout(() => map?.invalidateSize(), 120)
}

function planAmapRoute(task, source, destination) {
  if (routeRequests.has(task.id) || routeFailed.has(task.id)) return
  if (!window.AMap?.Driving) {
    if (!pluginRequested && window.AMap?.plugin) {
      pluginRequested = true
      window.AMap.plugin('AMap.Driving', () => drawAmapRoutes())
    }
    return
  }
  routeRequests.add(task.id)
  const options = {}
  if (window.AMap.DrivingPolicy?.LEAST_TIME != null) options.policy = window.AMap.DrivingPolicy.LEAST_TIME
  const driving = new window.AMap.Driving(options)
  driving.search(new window.AMap.LngLat(...gcjPosition(source)), new window.AMap.LngLat(...gcjPosition(destination)), (status, result) => {
    routeRequests.delete(task.id)
    if (status !== 'complete' || !result?.routes?.length) { routeFailed.add(task.id); return }
    const route = result.routes[0]
    const path = route.path?.length ? route.path : (route.steps || []).flatMap(step => step.path || [])
    if (!path.length) {
      routeFailed.add(task.id)
      return
    }
    routePaths.set(task.id, path)
    if (amap) drawAmapRoutes()
  })
}

function drawAmapRoutes() {
  if (!amap) return
  if (amapRoutes.length) amap.remove(amapRoutes)
  amapRoutes = []
  for (const task of props.tasks.filter(item => ['PLANNED', 'RUNNING'].includes(item.status))) {
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    const cached = routePaths.get(task.id)
    const path = cached || [gcjPosition(source), gcjPosition(destination)]
    if (cached) {
      amapRoutes.push(new window.AMap.Polyline({ path, strokeColor: '#ffffff', strokeWeight: 9, strokeOpacity: .95, strokeStyle: 'solid', zIndex: 58 }))
      amapRoutes.push(new window.AMap.Polyline({ path, strokeColor: '#078763', strokeWeight: 5, strokeOpacity: 1, strokeStyle: 'solid', showDir: true, zIndex: 60 }))
    } else {
      amapRoutes.push(new window.AMap.Polyline({ path, strokeColor: '#078763', strokeWeight: 4, strokeOpacity: .9, strokeStyle: 'dashed', strokeDasharray: [9, 7], zIndex: 60 }))
    }
    if (!cached) planAmapRoute(task, source, destination)
  }
  if (amapRoutes.length) amap.add(amapRoutes)
}

function drawAmap(fit) {
  if (!amap) return
  if (amapMarkers.length) amap.remove(amapMarkers)
  amapMarkers = []
  for (const region of props.regions.filter(isMapped)) {
    const active = props.selected === region.id
    const size = active ? 18 : 14
    const wrapper = document.createElement('div')
    wrapper.className = 'amap-marker-shell'
    wrapper.style.width = size + 'px'
    wrapper.innerHTML = '<div class="demo-map-marker ' + tone(region) + (active ? ' selected' : '') + '" style="width:' + size + 'px;height:' + size + 'px"></div>'
    wrapper.tabIndex = 0
    wrapper.setAttribute('role', 'button')
    wrapper.setAttribute('aria-label', region.name + '，现有 ' + region.stock + ' 辆，待补 ' + Math.max(0, region.shortage) + ' 辆')
    wrapper.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); emit('select', region.id) }
    })
    const marker = new window.AMap.Marker({
      position: gcjPosition(region),
      content: wrapper,
      offset: new window.AMap.Pixel(-size / 2, -size / 2),
      title: region.name + '，' + region.stock + ' 辆；演示停车点，位置未实测',
      zIndex: active ? 120 : 100
    })
    marker.on('click', () => emit('select', region.id))
    amapMarkers.push(marker)
  }
  if (amapMarkers.length) amap.add(amapMarkers)
  drawAmapRoutes()
  if (fit) fitAmapArea()
}

function fitAmapArea() {
  if (!amap) return
  if (amapMarkers.length) amap.setFitView(amapMarkers, false, [45, 45, 45, 45], 15)
  else {
    const [lng, lat] = wgs84ToGcj02(campus[1], campus[0])
    amap.setZoomAndCenter(14, [lng, lat])
  }
}

function initAmap() {
  const [lng, lat] = wgs84ToGcj02(campus[1], campus[0])
  amap = new window.AMap.Map(container.value, {
    center: [lng, lat], zoom: 14, viewMode: '2D', mapStyle: 'amap://styles/whitesmoke',
    scrollWheel: false, zoomControl: false, resizeEnable: true
  })
  mapEngine.value = 'amap'
  drawAmap(true)
}

function loadAmap() {
  if (window.AMap) return Promise.resolve()
  if (amapLoader) return amapLoader
  if (amapSecurity) window._AMapSecurityConfig = { securityJsCode: amapSecurity }
  amapLoader = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(amapKey)
    script.async = true
    script.onload = () => window.AMap ? resolve() : reject(new Error('AMap unavailable'))
    script.onerror = () => reject(new Error('AMap script failed'))
    document.head.appendChild(script)
  })
  return amapLoader
}

function zoomIn() { mapEngine.value === 'amap' ? amap?.zoomIn() : map?.zoomIn() }
function zoomOut() { mapEngine.value === 'amap' ? amap?.zoomOut() : map?.zoomOut() }
function fitDemoArea() { mapEngine.value === 'amap' ? fitAmapArea() : fitLeafletArea() }

onMounted(async () => {
  await nextTick()
  if (amapKey) {
    try { await loadAmap(); initAmap(); return }
    catch { loadNote.value = '高德地图加载失败，已回退到 OpenStreetMap 底图。' }
  }
  initLeaflet()
})

watch(() => props.regions, () => {
  if (mapEngine.value === 'amap') drawAmap(false)
  else if (map) { drawLeafletMarkers(); drawLeafletRoutes() }
}, { deep: true })
watch(() => props.tasks, () => {
  if (mapEngine.value === 'amap') drawAmapRoutes()
  else drawLeafletRoutes()
}, { deep: true })
watch(() => props.selected, id => {
  const region = props.regions.find(item => item.id === id)
  if (mapEngine.value === 'amap') {
    drawAmap(false)
    if (region && isMapped(region)) amap?.panTo(gcjPosition(region))
  } else if (map) {
    drawLeafletMarkers()
    if (region && isMapped(region)) map.panTo(position(region))
  }
})
onBeforeUnmount(() => {
  if (mapEngine.value === 'amap') amap?.destroy()
  else map?.remove()
  map = null; amap = null
})
</script>

<template>
  <div class="region-map" :class="{ 'is-amap': mapEngine === 'amap' }">
    <div ref="container" class="leaflet-map" aria-label="犀浦校区周边真实街道底图及演示停车点"></div>
    <div v-if="tileError" class="map-tile-error" role="status">地图底图暂时无法加载，请检查网络后刷新页面。</div>
    <div v-if="loadNote" class="map-tile-error" role="status">{{ loadNote }}</div>
    <div class="map-controls"><button aria-label="放大地图" @click="zoomIn"><Plus :size="17"/></button><button aria-label="缩小地图" @click="zoomOut"><Minus :size="17"/></button><button aria-label="恢复地图视图" @click="fitDemoArea"><LocateFixed :size="17"/></button></div>
    <button class="map-legend-toggle" :aria-expanded="legendOpen" @click="legendOpen = !legendOpen">图例</button>
    <div v-if="legendOpen" class="map-legend"><span><i class="legend-dot green"></i>库存充足</span><span><i class="legend-dot orange"></i>待补车</span><span><i class="legend-dot blue"></i>较拥挤</span></div>
  </div>
</template>
