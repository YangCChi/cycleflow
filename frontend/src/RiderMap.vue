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
let map, group, fitted = false
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
const point = region => { const [lng, lat] = wgs84ToGcj02(region.longitude, region.latitude); return [lat, lng] }
const regionById = id => props.regions.find(region => region.id === id)

function draw() {
  if (!map || !group) return
  group.clearLayers()
  for (const region of props.regions) {
    L.circleMarker(point(region), {
      radius: 8, color: '#fff', weight: 2, fillOpacity: 1,
      fillColor: region.shortage > 0 ? '#d9822b' : '#0a7c5a',
    }).bindTooltip(`${region.name} · ${region.stock} 辆`, { direction: 'top', offset: [0, -8] }).addTo(group)
  }
  for (const offer of props.offers) {
    const from = regionById(offer.fromId), to = regionById(offer.toId)
    if (!from || !to) continue
    const active = offer.id === props.selected
    L.polyline([point(from), point(to)], {
      color: active ? '#0a7c5a' : '#8fbcaa', weight: active ? 4 : 2,
      opacity: active ? 1 : .45, dashArray: '6 8',
    }).addTo(group)
  }
  if (props.activeRide) {
    const from = regionById(props.activeRide.fromId), to = regionById(props.activeRide.toId)
    if (from && to) L.polyline([point(from), point(to)], { color: '#0a7c5a', weight: 4, opacity: 1 }).addTo(group)
  }
  if (!fitted && props.regions.length) {
    const bounds = L.latLngBounds(props.regions.map(point))
    const height = el.value?.clientHeight || window.innerHeight
    map.fitBounds(bounds, { paddingTopLeft: [30, 96], paddingBottomRight: [30, Math.round(height * 0.5)] })
    fitted = true
  }
  if (props.selected || props.activeRide) {
    const ride = props.activeRide || props.offers.find(offer => offer.id === props.selected)
    const from = ride && regionById(ride.fromId), to = ride && regionById(ride.toId)
    if (from && to) map.flyTo([(from.latitude + to.latitude) / 2, (from.longitude + to.longitude) / 2], 16, { duration: .6 })
  }
}

onMounted(() => {
  const [lng, lat] = wgs84ToGcj02(103.985, 30.756)
  map = L.map(el.value, { zoomControl: false, attributionControl: false, scrollWheelZoom: false })
    .setView([lat, lng], 15)
  L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
    subdomains: ['1', '2', '3', '4'], maxZoom: 18,
  }).addTo(map)
  group = L.layerGroup().addTo(map)
  draw()
  setTimeout(() => map.invalidateSize(), 350)
})
watch([() => props.regions, () => props.offers, () => props.selected, () => props.activeRide], draw, { deep: true })
onUnmounted(() => map?.remove())
</script>

<template><div ref="el" class="rider-map"></div></template>
