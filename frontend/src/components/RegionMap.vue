<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Plus, Minus, LocateFixed } from 'lucide-vue-next'

const props = defineProps({ regions: { type: Array, default: () => [] }, selected: Number, tasks: { type: Array, default: () => [] }, minute: { type: Number, default: 0 }, focusedTask: String })
const emit = defineEmits(['select', 'clear'])
const container = ref(null)
const tileError = ref(false)
const loadNote = ref('')
const legendOpen = ref(false)
const mapEngine = ref('')
const campus = [30.7660599, 103.9844831]
const amapKey = import.meta.env.VITE_AMAP_KEY
const amapSecurity = import.meta.env.VITE_AMAP_SECURITY_CODE

let L = null
let map
let amap
let leafletMarkers
let leafletRoutes
let leafletTrucks
let amapTraveled = []
const amapTruckState = new Map()
const flowLines = new Map()
let flowFrame = 0
const grownRoutes = new Set()
let leafletTraveled
let fitted = false
let fitTimer
let markerClickAt = 0
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
const toneColor = region => tone(region) === 'shortage' ? '#d9822b' : tone(region) === 'crowded' ? '#7f8fa6' : '#2f9e6b'
function runningByVehicle() {
  const byVehicle = new Map()
  for (const task of props.tasks.filter(item => item.status === 'RUNNING')) {
    const current = byVehicle.get(task.vehicle)
    if (!current || (task.startedMinute ?? 0) >= (current.startedMinute ?? 0)) byVehicle.set(task.vehicle, task)
  }
  return [...byVehicle.values()]
}
const progressOf = task => Math.max(0, Math.min(1, (props.minute - (task.startedMinute ?? props.minute)) / Math.max(1, task.duration)))
const asPair = point => Array.isArray(point) ? point : [point.getLng ? point.getLng() : point.lng, point.getLat ? point.getLat() : point.lat]

function along(points, ratio) {
  if (points.length === 1) return points[0]
  const lengths = []
  let total = 0
  for (let i = 1; i < points.length; i++) {
    const length = Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
    lengths.push(length); total += length
  }
  let target = total * ratio
  for (let i = 0; i < lengths.length; i++) {
    if (target <= lengths[i] || i === lengths.length - 1) {
      const r = lengths[i] ? Math.min(1, target / lengths[i]) : 0
      return [points[i][0] + (points[i + 1][0] - points[i][0]) * r, points[i][1] + (points[i + 1][1] - points[i][1]) * r]
    }
    target -= lengths[i]
  }
  return points[points.length - 1]
}

function slicePath(points, ratio) {
  if (!points.length) return [[], []]
  const lengths = []
  let total = 0
  for (let i = 1; i < points.length; i++) {
    const length = Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
    lengths.push(length); total += length
  }
  let target = total * Math.max(0, Math.min(1, ratio))
  const traveled = [points[0]]
  for (let i = 0; i < lengths.length; i++) {
    if (target >= lengths[i]) { traveled.push(points[i + 1]); target -= lengths[i] }
    else {
      const r = lengths[i] ? target / lengths[i] : 0
      const point = [points[i][0] + (points[i + 1][0] - points[i][0]) * r, points[i][1] + (points[i + 1][1] - points[i][1]) * r]
      traveled.push(point)
      return [traveled, [point, ...points.slice(i + 1)]]
    }
  }
  return [traveled, [points[points.length - 1]]]
}

function stationSvg(region, active) {
  const color = toneColor(region)
  return '<svg class="station-svg" width="34" height="42" viewBox="0 0 34 42" xmlns="http://www.w3.org/2000/svg">'
    + '<path d="M17 41S32 26.8 32 15.6A15 15 0 1 0 2 15.6C2 26.8 17 41 17 41Z" fill="' + color + '" stroke="#fff" stroke-width="2.5"/>'
    + '<circle cx="17" cy="16" r="9.4" fill="#fff"/>'
    + '<g fill="none" stroke="' + color + '" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">'
    + '<circle cx="12.4" cy="18.8" r="2.9"/><circle cx="21.6" cy="18.8" r="2.9"/>'
    + '<path d="M12.4 18.8l3.4-5.2h3l2.8 5.2M15.8 18.8l2.3-5.2M14.4 13.6h2.8"/></g></svg>'
    + (active ? '' : '')
}

function truckSvg() {
  return '<svg width="38" height="38" viewBox="0 0 38 38" xmlns="http://www.w3.org/2000/svg">'
    + '<circle cx="19" cy="19" r="16.5" fill="#0a7c5a" stroke="#fff" stroke-width="2.6"/>'
    + '<g fill="none" stroke="#fff" stroke-width="1.7" stroke-linejoin="round" stroke-linecap="round">'
    + '<rect x="9" y="14" width="12" height="8.5" rx="1"/>'
    + '<path d="M21 17.5h3.3L27 20.8v1.7h-6"/>'
    + '<circle cx="12.8" cy="24" r="1.9" fill="#fff"/><circle cx="23" cy="24" r="1.9" fill="#fff"/></g></svg>'
}

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
  if (!map || !L) return
  leafletMarkers.clearLayers()
  for (const region of props.regions.filter(isMapped)) {
    const active = props.selected === region.id
    const icon = L.divIcon({
      className: 'station-marker ' + tone(region) + (active ? ' selected' : ''),
      html: stationSvg(region, active),
      iconSize: [34, 42],
      iconAnchor: [17, 41]
    })
    const description = region.name + '，' + region.stock + ' 辆；演示停车点，位置未实测'
    L.marker(position(region), { icon, title: description, alt: description, keyboard: true })
      .on('click', () => { markerClickAt = Date.now(); emit('select', region.id) }).addTo(leafletMarkers)
  }
}

function drawLeafletTrucks() {
  if (!map || !L) return
  leafletTrucks.clearLayers()
  leafletTraveled.clearLayers()
  const runningTrucks = focusActive() ? runningByVehicle().filter(task => task.id === props.focusTask) : runningByVehicle()
  for (const task of runningTrucks) {
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    const point = along([position(source), position(destination)], progressOf(task))
    L.polyline([position(source), point], { color: '#0a7c5a', weight: 5, opacity: 1, className: 'demo-map-route' }).addTo(leafletTraveled)
    L.marker(point, {
      icon: L.divIcon({ className: 'truck-marker', html: truckSvg(), iconSize: [38, 38], iconAnchor: [19, 19] }),
      interactive: false, keyboard: false, zIndexOffset: 500
    }).addTo(leafletTrucks)
  }
}

function drawLeafletRoutes() {
  if (!map || !L) return
  leafletRoutes.clearLayers()
  const isolatedLeaflet = focusActive()
  for (const task of props.tasks.filter(item => ['PLANNED', 'RUNNING'].includes(item.status))) {
    if (isolatedLeaflet && task.id !== props.focusTask) continue
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    const focused = props.focusTask === task.id
    const emphasized = focused || props.selected === task.sourceId || props.selected === task.destinationId
    L.polyline([position(source), position(destination)], {
      color: '#078763', weight: focused ? 7 : (emphasized ? 6 : 4),
      opacity: focused ? 1 : (task.status === 'RUNNING' ? .22 : (emphasized ? .9 : .45)),
      dashArray: task.status === 'PLANNED' ? '9 7' : undefined,
      className: task.status === 'RUNNING' ? 'demo-map-route running' : 'demo-map-route'
    }).bindTooltip('调度任务示意连线，非实际道路路线').addTo(leafletRoutes)
  }
}

function fitLeafletArea() {
  if (!map || !L) return
  const points = props.regions.filter(isMapped).map(position)
  if (points.length) { map.fitBounds(L.latLngBounds(points), { padding: [45, 45], maxZoom: 15, animate: false }); fitted = true }
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

async function initLeaflet() {
  if (!L) {
    const module = await import('leaflet')
    await import('leaflet/dist/leaflet.css')
    L = module.default
  }
  map = L.map(container.value, { zoomControl: false, scrollWheelZoom: false, minZoom: 11, maxZoom: 19 })
  addTileLayer(0)
  leafletRoutes = L.layerGroup().addTo(map)
  leafletMarkers = L.layerGroup().addTo(map)
  leafletTrucks = L.layerGroup().addTo(map)
  leafletTraveled = L.layerGroup().addTo(map)
  drawLeafletRoutes()
  drawLeafletMarkers()
  drawLeafletTrucks()
  fitLeafletArea()
  map.on('click', () => { if (Date.now() - markerClickAt > 250) emit('clear') })
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
    if (amap) { drawAmapRoutes(); drawAmapTrucks() }
  })
}

function animateRoute(line, points) {
  const started = performance.now()
  const duration = 620
  const step = now => {
    const k = Math.min(1, (now - started) / duration)
    const [partial] = slicePath(points, 1 - Math.pow(1 - k, 2))
    line.setPath(partial)
    if (k < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

function drawAmapRoutes() {
  if (!amap) return
  if (amapRoutes.length) amap.remove(amapRoutes)
  amapRoutes = []
  const growing = []
  const activeIds = new Set()
  const isolated = focusActive()
  for (const task of props.tasks.filter(item => ['PLANNED', 'RUNNING'].includes(item.status))) {
    if (isolated && task.id !== props.focusTask) continue
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    activeIds.add(task.id)
    const cached = routePaths.get(task.id)
    const points = cached && cached.length ? cached.map(asPair) : [gcjPosition(source), gcjPosition(destination)]
    const focused = props.focusTask === task.id
    const emphasized = focused || props.selected === task.sourceId || props.selected === task.destinationId
    const running = task.status === 'RUNNING'
    const first = !grownRoutes.has(task.id)
    const path = first ? [points[0]] : points
    if (cached && !first) {
      amapRoutes.push(new window.AMap.Polyline({ path, strokeColor: '#ffffff', strokeWeight: 9, strokeOpacity: .95, strokeStyle: 'solid', zIndex: 58 }))
    }
    const line = new window.AMap.Polyline({
      path,
      strokeColor: '#078763',
      strokeWeight: focused ? 7 : (emphasized ? 6 : 4),
      strokeOpacity: focused ? 1 : (running ? .22 : (emphasized ? .9 : .45)),
      strokeStyle: task.status === 'PLANNED' ? 'dashed' : 'solid',
      strokeDasharray: task.status === 'PLANNED' ? [9, 7] : undefined,
      showDir: !running && emphasized,
      zIndex: 60
    })
    amapRoutes.push(line)
    if (first) growing.push({ line, points, id: task.id })
    if (!cached) planAmapRoute(task, source, destination)
  }
  if (amapRoutes.length) amap.add(amapRoutes)
  for (const id of grownRoutes) if (!activeIds.has(id)) grownRoutes.delete(id)
  for (const item of growing) { grownRoutes.add(item.id); animateRoute(item.line, item.points) }
}

const focusActive = () => !!props.focusTask && props.tasks.some(task => task.id === props.focusTask && ['PLANNED', 'RUNNING'].includes(task.status))

function sliceBetween(points, from, to) {
  if (points.length < 2) return points
  const segments = []
  let total = 0
  for (let i = 1; i < points.length; i++) {
    const length = Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
    segments.push(length); total += length
  }
  if (!total) return points.slice(0, 1)
  const out = []
  let acc = 0
  for (let i = 0; i < segments.length; i++) {
    const start = acc / total, end = (acc + segments[i]) / total
    acc += segments[i]
    if (end < from || start > to) continue
    const t0 = Math.max(0, (from - start) / (end - start))
    const t1 = Math.min(1, (to - start) / (end - start))
    const a = [points[i][0] + (points[i + 1][0] - points[i][0]) * t0, points[i][1] + (points[i + 1][1] - points[i][1]) * t0]
    const b = [points[i][0] + (points[i + 1][0] - points[i][0]) * t1, points[i][1] + (points[i + 1][1] - points[i][1]) * t1]
    if (!out.length) out.push(a)
    out.push(b)
  }
  return out
}

function flowTick() {
  if (!amap) { flowFrame = 0; return }
  const running = focusActive() ? runningByVehicle().filter(task => task.id === props.focusTask) : runningByVehicle()
  const seen = new Set()
  const phase = ((performance.now() % 2600) / 2600) * 0.93
  for (const task of running) {
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    seen.add(task.id)
    const cached = routePaths.get(task.id)
    const points = cached && cached.length ? cached.map(asPair) : [gcjPosition(source), gcjPosition(destination)]
    let line = flowLines.get(task.id)
    if (!line) {
      line = new window.AMap.Polyline({ path: [], strokeColor: '#ffffff', strokeWeight: 3, strokeOpacity: .75, strokeStyle: 'solid', zIndex: 133 })
      amap.add(line)
      flowLines.set(task.id, line)
    }
    line.setPath(sliceBetween(points, phase, Math.min(1, phase + 0.07)))
  }
  for (const [id, line] of flowLines) {
    if (seen.has(id)) continue
    amap.remove(line)
    flowLines.delete(id)
  }
  flowFrame = seen.size ? requestAnimationFrame(flowTick) : 0
}

function syncFlow() {
  if (!amap) return
  if (runningByVehicle().length) {
    if (!flowFrame) flowFrame = requestAnimationFrame(flowTick)
  } else {
    if (flowFrame) cancelAnimationFrame(flowFrame)
    flowFrame = 0
    for (const line of flowLines.values()) amap.remove(line)
    flowLines.clear()
  }
}

function animateTruck(entry, target) {
  cancelAnimationFrame(entry.raf)
  const start = entry.point || target
  const began = performance.now()
  const step = now => {
    const k = Math.min(1, (now - began) / 700)
    const eased = 1 - Math.pow(1 - k, 3)
    const point = [start[0] + (target[0] - start[0]) * eased, start[1] + (target[1] - start[1]) * eased]
    entry.marker.setPosition(point)
    entry.point = point
    if (k < 1) entry.raf = requestAnimationFrame(step)
  }
  entry.raf = requestAnimationFrame(step)
}

function drawAmapTrucks() {
  if (!amap) return
  if (amapTraveled.length) amap.remove(amapTraveled)
  amapTraveled = []
  const seen = new Set()
  const runningTrucks = focusActive() ? runningByVehicle().filter(task => task.id === props.focusTask) : runningByVehicle()
  for (const task of runningTrucks) {
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    const cached = routePaths.get(task.id)
    const points = cached && cached.length
      ? cached.map(asPair)
      : [gcjPosition(source), gcjPosition(destination)]
    const progress = progressOf(task)
    const target = along(points, progress)
    const key = task.vehicle
    seen.add(key)
    let entry = amapTruckState.get(key)
    if (!entry) {
      const element = document.createElement('div')
      element.className = 'truck-marker'
      element.innerHTML = truckSvg()
      const marker = new window.AMap.Marker({
        position: target, content: element, offset: new window.AMap.Pixel(-19, -19), zIndex: 140, bubble: true
      })
      amap.add(marker)
      entry = { marker, point: target, raf: 0 }
      amapTruckState.set(key, entry)
    } else {
      animateTruck(entry, target)
    }
    const [traveled] = slicePath(points, progress)
    if (traveled.length > 1) {
      amapTraveled.push(new window.AMap.Polyline({
        path: traveled, strokeColor: '#0a7c5a', strokeWeight: 5, strokeOpacity: 1, strokeStyle: 'solid', zIndex: 132
      }))
    }
  }
  for (const [key, entry] of amapTruckState) {
    if (seen.has(key)) continue
    cancelAnimationFrame(entry.raf)
    amap.remove(entry.marker)
    amapTruckState.delete(key)
  }
  if (amapTraveled.length) amap.add(amapTraveled)
  syncFlow()
}

function drawAmap(fit) {
  if (!amap) return
  if (amapMarkers.length) amap.remove(amapMarkers)
  amapMarkers = []
  for (const region of props.regions.filter(isMapped)) {
    const active = props.selected === region.id
    const wrapper = document.createElement('div')
    wrapper.className = 'station-marker ' + tone(region) + (active ? ' selected' : '')
    wrapper.innerHTML = stationSvg(region, active)
    wrapper.tabIndex = 0
    wrapper.setAttribute('role', 'button')
    wrapper.setAttribute('aria-label', region.name + '，现有 ' + region.stock + ' 辆，待补 ' + Math.max(0, region.shortage) + ' 辆')
    wrapper.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); emit('select', region.id) }
    })
    const marker = new window.AMap.Marker({
      position: gcjPosition(region),
      content: wrapper,
      offset: new window.AMap.Pixel(-17, -41),
      title: region.name + '，' + region.stock + ' 辆；演示停车点，位置未实测',
      zIndex: active ? 120 : 100
    })
    marker.on('click', () => { markerClickAt = Date.now(); emit('select', region.id) })
    amapMarkers.push(marker)
  }
  if (amapMarkers.length) amap.add(amapMarkers)
  drawAmapRoutes()
  drawAmapTrucks()
  if (fit) fitAmapArea()
}

function scheduleAmapFit() {
  window.clearTimeout(fitTimer)
  fitTimer = window.setTimeout(() => fitAmapArea(), 350)
}

function fitZoomFor(points, width, height, padding) {
  let minLng = Infinity, maxLng = -Infinity, minLat = Infinity, maxLat = -Infinity
  for (const point of points) {
    minLng = Math.min(minLng, point[0]); maxLng = Math.max(maxLng, point[0])
    minLat = Math.min(minLat, point[1]); maxLat = Math.max(maxLat, point[1])
  }
  const midLng = (minLng + maxLng) / 2, midLat = (minLat + maxLat) / 2
  const lngSpan = Math.max(maxLng - minLng, 1e-6), latSpan = Math.max(maxLat - minLat, 1e-6)
  const zoomLng = Math.log2(((width - padding * 2) * 360) / (256 * lngSpan))
  const metersPerPixel = 156543.03392 * Math.cos(midLat * Math.PI / 180)
  const zoomLat = Math.log2(((height - padding * 2) * metersPerPixel) / (latSpan * 111320))
  const zoom = Math.max(13, Math.min(16, Math.floor(Math.min(zoomLng, zoomLat))))
  return { zoom, center: [midLng, midLat] }
}

function fitAmapArea() {
  if (!amap) return
  if (amapMarkers.length) {
    const points = amapMarkers.map(marker => {
      const point = marker.getPosition()
      return [point.getLng ? point.getLng() : point.lng, point.getLat ? point.getLat() : point.lat]
    })
    const { zoom, center } = fitZoomFor(points, container.value?.clientWidth || 640, container.value?.clientHeight || 480, 70)
    amap.setZoomAndCenter(zoom, center)
    fitted = true
  } else {
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
  amap.on('click', () => { if (Date.now() - markerClickAt > 250) emit('clear') })
  drawAmap(true)
  scheduleAmapFit()
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
  if (mapEngine.value === 'amap') {
    drawAmap(false)
    if (!fitted && amapMarkers.length) { fitAmapArea(); scheduleAmapFit() }
  } else if (map) {
    drawLeafletMarkers(); drawLeafletRoutes()
    if (!fitted && props.regions.length) fitLeafletArea()
  }
}, { deep: true })
watch(() => props.tasks, () => {
  if (mapEngine.value === 'amap') { drawAmapRoutes(); drawAmapTrucks() }
  else { drawLeafletRoutes(); drawLeafletTrucks() }
}, { deep: true })
watch(() => props.focusTask, id => {
  if (mapEngine.value === 'amap') {
    drawAmap(false)
    const task = props.tasks.find(item => item.id === id)
    const source = task && props.regions.find(region => region.id === task.sourceId)
    const destination = task && props.regions.find(region => region.id === task.destinationId)
    if (source && destination) {
      const cached = routePaths.get(id)
      const points = cached && cached.length ? cached.map(asPair) : [gcjPosition(source), gcjPosition(destination)]
      const { zoom, center } = fitZoomFor(points, container.value?.clientWidth || 640, container.value?.clientHeight || 480, 80)
      amap?.setZoomAndCenter(Math.max(zoom, 14), center, false, 600)
    }
  } else if (map) {
    drawLeafletMarkers(); drawLeafletRoutes()
    const task = props.tasks.find(item => item.id === id)
    const source = task && props.regions.find(region => region.id === task.sourceId)
    const destination = task && props.regions.find(region => region.id === task.destinationId)
    if (source && destination) map.flyToBounds(L.latLngBounds([position(source), position(destination)]), { padding: [70, 70], maxZoom: 16, duration: .7 })
  }
})
watch(() => props.minute, () => {
  if (mapEngine.value === 'amap') drawAmapTrucks()
  else drawLeafletTrucks()
})
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
  if (flowFrame) cancelAnimationFrame(flowFrame)
  flowFrame = 0
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
    <div v-if="legendOpen" class="map-legend"><span><i class="legend-dot green"></i>库存充足</span><span><i class="legend-dot orange"></i>待补车</span><span><i class="legend-dot blue"></i>较拥挤</span><span v-if="tasks.some(t => t.status === 'RUNNING')"><i class="legend-truck"></i>运输车</span></div>
  </div>
</template>
