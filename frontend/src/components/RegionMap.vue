<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Plus, Minus, LocateFixed } from 'lucide-vue-next'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const props = defineProps({ regions: { type: Array, default: () => [] }, selected: Number, tasks: { type: Array, default: () => [] } })
const emit = defineEmits(['select'])
const container = ref(null)
const tileError = ref(false)
const campus = [30.7660599, 103.9844831]
let map
let markers
let routes
let loadedTile = false
let failedTiles = 0

const position = region => [region.latitude, region.longitude]
const isMapped = region => Number.isFinite(region?.latitude) && Number.isFinite(region?.longitude)
const tone = region => region.shortage > 0 ? 'shortage' : region.status === 'crowded' ? 'crowded' : 'balanced'

function redrawMarkers() {
  if (!map) return
  markers.clearLayers()
  for (const region of props.regions.filter(isMapped)) {
    const active = props.selected === region.id
    const size = active ? 46 : 40
    const icon = L.divIcon({
      className: 'demo-map-marker ' + tone(region) + (active ? ' selected' : ''),
      html: '<span>' + Number(region.stock) + '</span>',
      iconSize: [size, size],
      iconAnchor: [size / 2, size / 2]
    })
    const description = region.name + '，' + region.stock + ' 辆；演示停车点，位置未实测'
    const marker = L.marker(position(region), {
      icon, title: description, alt: description, keyboard: true
    }).on('click', () => emit('select', region.id)).addTo(markers)
    if (active || region.id <= 3) {
      const label = document.createElement('span')
      label.textContent = region.name
      marker.bindTooltip(label, { permanent: true, direction: 'bottom', offset: [0, 12], className: 'demo-map-tooltip' + (active ? ' selected-label' : '') })
    }
  }
}

function redrawRoutes() {
  if (!map) return
  routes.clearLayers()
  for (const task of props.tasks.filter(item => ['PLANNED', 'RUNNING'].includes(item.status))) {
    const source = props.regions.find(region => region.id === task.sourceId)
    const destination = props.regions.find(region => region.id === task.destinationId)
    if (!isMapped(source) || !isMapped(destination)) continue
    L.polyline([position(source), position(destination)], {
      color: '#078763', weight: 4, opacity: .9, dashArray: '9 7',
      className: task.status === 'RUNNING' ? 'demo-map-route running' : 'demo-map-route'
    }).bindTooltip('调度任务示意连线，非实际道路路线').addTo(routes)
  }
}

function fitDemoArea() {
  if (!map) return
  const points = props.regions.filter(isMapped).map(position)
  if (points.length) map.fitBounds(L.latLngBounds(points), { padding: [45, 45], maxZoom: 14, animate: false })
  else map.setView(campus, 14)
}

onMounted(async () => {
  await nextTick()
  map = L.map(container.value, { zoomControl: false, scrollWheelZoom: false, minZoom: 11, maxZoom: 19 })
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).on('tileload', () => { loadedTile = true; tileError.value = false })
    .on('tileerror', () => { if (!loadedTile && ++failedTiles >= 3) tileError.value = true })
    .addTo(map)
  routes = L.layerGroup().addTo(map)
  markers = L.layerGroup().addTo(map)
  redrawRoutes()
  redrawMarkers()
  fitDemoArea()
  window.setTimeout(() => map?.invalidateSize(), 120)
})

watch(() => props.regions, () => { redrawMarkers(); redrawRoutes() }, { deep: true })
watch(() => props.tasks, redrawRoutes, { deep: true })
watch(() => props.selected, id => {
  redrawMarkers()
  const region = props.regions.find(item => item.id === id)
  if (map && isMapped(region)) map.panTo(position(region))
})
onBeforeUnmount(() => { map?.remove(); map = null })
</script>

<template>
  <div class="region-map">
    <div ref="container" class="leaflet-map" aria-label="犀浦校区周边真实街道底图及演示停车点"></div>
    <div class="map-caption"><span class="live-dot"></span> 犀浦校区周边 <span class="map-tag">真实街道底图</span></div>
    <div v-if="tileError" class="map-tile-error" role="status">地图底图暂时无法加载，请检查网络后刷新页面。</div>
    <div class="map-controls"><button aria-label="放大地图" @click="map?.zoomIn()"><Plus :size="17"/></button><button aria-label="缩小地图" @click="map?.zoomOut()"><Minus :size="17"/></button><button aria-label="恢复地图视图" @click="fitDemoArea"><LocateFixed :size="17"/></button></div>
    <div class="map-legend"><span><i class="legend-dot green"></i>库存充足</span><span><i class="legend-dot orange"></i>待补车</span><span><i class="legend-dot blue"></i>较拥挤</span></div>
    <div class="map-disclaimer">停车点位未实测 · 连线非道路导航</div>
  </div>
</template>
