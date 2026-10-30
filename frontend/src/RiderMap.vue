<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const props = defineProps({
  regions: { type: Array, default: () => [] },
  offers: { type: Array, default: () => [] },
  activeRide: { type: Object, default: null },
  selected: { type: String, default: '' },
})
const el = ref()
let amap = null
let map = null
let group = null
let overlays = []
let engine = ''
let fitted = false

const outOfChina = (lng, lat) => lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
const transformLat = (x, y) => -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x)) + (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3 + (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3 + (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3
const transformLng = (x, y) => 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x)) + (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3 + (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3 + (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3
function wgs84ToGcj02(lng, lat) {
  if (outOfChina(lng, lat)) return [lng, lat]
  const a = 6378245.0, ee = 0.00669342162296594323
  let dLat = transformLat(lng - 105.0, lat - 35.0)
  let dLng = transformLng(lng - 105.0, lat - 35.0)
  const radLat = lat / 180.0 * Math.PI
  let magic = Math.sin(radLat)
  magic = 1 - ee * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
  dLng = (dLng * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
  return [lng + dLng, lat + dLat]
}
const gcjPair = region => wgs84ToGcj02(region.longitude, region.latitude)
const leafletPoint = region => { const [lng, lat] = gcjPair(region); return [lat, lng] }
const regionById = id => props.regions.find(region => region.id === id)
const dotColor = region => region.shortage > 0 ? '#d9822b' : '#0a7c5a'

function loadAmapApi() {
  return new Promise((resolve, reject) => {
    if (window.AMap) return resolve(window.AMap)
    const key = import.meta.env.VITE_AMAP_KEY
    if (!key) return reject(new Error('缺少 VITE_AMAP_KEY'))
    const security = import.meta.env.VITE_AMAP_SECURITY_CODE
    if (security) window._AMapSecurityConfig = { securityJsCode: security }
    const script = document.createElement('script')
    script.async = true
    script.src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(key)
    script.onload = () => window.AMap ? resolve(window.AMap) : reject(new Error('高德未初始化'))
    script.onerror = () => reject(new Error('高德脚本加载失败'))
    document.head.appendChild(script)
  })
}

function clearAmap() {
  if (overlays.length) amap.remove(overlays)
  overlays = []
}

function drawAmap() {
  if (!amap) return
  clearAmap()
  const markers = []
  for (const region of props.regions) {
    const marker = new window.AMap.Marker({
      position: gcjPair(region),
      content: `<div class="rider-dot" style="background:${dotColor(region)}"></div>`,
      offset: new window.AMap.Pixel(-8, -8),
      title: `${region.name} · ${region.stock} 辆`,
      zIndex: 120,
    })
    markers.push(marker)
    overlays.push(marker)
  }
  for (const offer of props.offers) {
    const from = regionById(offer.fromId), to = regionById(offer.toId)
    if (!from || !to) continue
    const highlighted = offer.id === props.selected
    overlays.push(new window.AMap.Polyline({
      path: [gcjPair(from), gcjPair(to)],
      strokeColor: highlighted ? '#0a7c5a' : '#8fbcaa',
      strokeWeight: highlighted ? 4 : 2,
      strokeOpacity: highlighted ? 1 : .45,
      strokeStyle: 'dashed',
      strokeDasharray: [6, 6],
      zIndex: 110,
    }))
  }
  if (props.activeRide) {
    const from = regionById(props.activeRide.fromId), to = regionById(props.activeRide.toId)
    if (from && to) overlays.push(new window.AMap.Polyline({
      path: [gcjPair(from), gcjPair(to)], strokeColor: '#0a7c5a', strokeWeight: 4, strokeOpacity: 1, zIndex: 115,
    }))
  }
  if (overlays.length) amap.add(overlays)
  if (!fitted && props.regions.length) {
    const [lng, lat] = wgs84ToGcj02(103.985, 30.756)
    amap.setZoomAndCenter(14, [lng, lat])
    fitted = true
  }
  const ride = props.activeRide || props.offers.find(offer => offer.id === props.selected)
  if (ride) {
    const from = regionById(ride.fromId), to = regionById(ride.toId)
    if (from && to) {
      const [aLng, aLat] = gcjPair(from), [bLng, bLat] = gcjPair(to)
      amap.setZoomAndCenter(16, [(aLng + bLng) / 2, (aLat + bLat) / 2], false, 500)
    }
  }
}

function drawLeaflet() {
  if (!map || !group) return
  group.clearLayers()
  for (const region of props.regions) {
    L.circleMarker(leafletPoint(region), { radius: 8, color: '#fff', weight: 2, fillOpacity: 1, fillColor: dotColor(region) })
      .bindTooltip(`${region.name} · ${region.stock} 辆`, { direction: 'top', offset: [0, -8] }).addTo(group)
  }
  for (const offer of props.offers) {
    const from = regionById(offer.fromId), to = regionById(offer.toId)
    if (!from || !to) continue
    const highlighted = offer.id === props.selected
    L.polyline([leafletPoint(from), leafletPoint(to)], {
      color: highlighted ? '#0a7c5a' : '#8fbcaa', weight: highlighted ? 4 : 2,
      opacity: highlighted ? 1 : .45, dashArray: '6 8',
    }).addTo(group)
  }
  if (props.activeRide) {
    const from = regionById(props.activeRide.fromId), to = regionById(props.activeRide.toId)
    if (from && to) L.polyline([leafletPoint(from), leafletPoint(to)], { color: '#0a7c5a', weight: 4, opacity: 1 }).addTo(group)
  }
  if (!fitted && props.regions.length) {
    const bounds = L.latLngBounds(props.regions.map(leafletPoint))
    map.fitBounds(bounds, { padding: [32, 56] })
    fitted = true
  }
  const ride = props.activeRide || props.offers.find(offer => offer.id === props.selected)
  if (ride) {
    const from = regionById(ride.fromId), to = regionById(ride.toId)
    if (from && to) {
      const a = leafletPoint(from), b = leafletPoint(to)
      map.flyTo([(a[0] + b[0]) / 2, (a[1] + b[1]) / 2], 16, { duration: .6 })
    }
  }
}

function draw() {
  if (engine === 'amap') drawAmap()
  else if (engine === 'leaflet') drawLeaflet()
}

function initLeaflet() {
  const [lng, lat] = wgs84ToGcj02(103.985, 30.756)
  map = L.map(el.value, { zoomControl: false, attributionControl: false, scrollWheelZoom: false }).setView([lat, lng], 15)
  L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
    subdomains: ['1', '2', '3', '4'], maxZoom: 18,
  }).addTo(map)
  group = L.layerGroup().addTo(map)
  engine = 'leaflet'
  draw()
  setTimeout(() => map.invalidateSize(), 350)
}

onMounted(async () => {
  try {
    await loadAmapApi()
    const [lng, lat] = wgs84ToGcj02(103.985, 30.756)
    amap = new window.AMap.Map(el.value, {
      center: [lng, lat], zoom: 15, viewMode: '2D', mapStyle: 'amap://styles/whitesmoke',
      zoomControl: false, scrollWheel: false, resizeEnable: true,
    })
    engine = 'amap'
    draw()
  } catch {
    initLeaflet()
  }
})
watch([() => props.regions, () => props.offers, () => props.selected, () => props.activeRide], draw, { deep: true })
onUnmounted(() => { amap?.destroy(); map?.remove() })
</script>

<template><div ref="el" class="rider-map"></div></template>
