<script setup>
import { computed, ref } from 'vue'
import { Plus, Minus, LocateFixed } from 'lucide-vue-next'
const props = defineProps({ regions: { type: Array, default: () => [] }, selected: Number, tasks: { type: Array, default: () => [] } })
const emit = defineEmits(['select'])
const zoom = ref(1)
const paths = computed(() => props.tasks.filter(t => ['PLANNED', 'RUNNING'].includes(t.status)).map(t => ({ ...t, source: props.regions.find(r => r.id === t.sourceId), destination: props.regions.find(r => r.id === t.destinationId) })).filter(t => t.source && t.destination))
const color = r => r.shortage > 0 ? '#e79939' : r.status === 'crowded' ? '#6385cd' : '#0b9c79'
</script>

<template>
  <div class="region-map">
    <div class="map-caption"><span class="live-dot"></span> 犀浦校区周边 <span class="map-tag">空间示意</span></div>
    <svg class="map-svg" viewBox="0 0 1000 650" role="img" aria-label="犀浦校区周边停车区域示意地图，点位和道路为演示布局">
      <defs>
        <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse"><path d="M 40 0 L 0 0 0 40" fill="none" stroke="#dce6e1" stroke-width=".6"/></pattern>
        <pattern id="campusGrid" width="60" height="60" patternUnits="userSpaceOnUse"><rect x="7" y="7" width="38" height="22" rx="3" fill="#d4e3d6"/></pattern>
        <marker id="routeArrow" markerWidth="7" markerHeight="7" refX="5" refY="3.5" orient="auto"><path d="M0,0 L7,3.5 L0,7" fill="#0c9975"/></marker>
      </defs>
      <rect width="1000" height="650" fill="#edf2ed"/><rect width="1000" height="650" fill="url(#grid)"/>
      <g :transform="`translate(${500 * (1 - zoom)} ${325 * (1 - zoom)}) scale(${zoom})`">
        <g fill="#e0e7df" stroke="#d8e1d8">
          <rect x="30" y="44" width="190" height="115" rx="12"/><rect x="290" y="45" width="205" height="85" rx="10"/>
          <rect x="60" y="222" width="215" height="78" rx="8"/><rect x="342" y="202" width="160" height="90" rx="12"/>
          <rect x="40" y="428" width="125" height="56" rx="8"/><rect x="285" y="429" width="225" height="52" rx="7"/>
          <rect x="310" y="564" width="190" height="65" rx="10"/><rect x="560" y="526" width="342" height="99" rx="12"/>
        </g>
        <path d="M0 180 H535 Q563 180 563 210 V650 M0 325 H1000 M0 510 H1000 M260 0 V650 M0 610 L690 0" stroke="#dce2d9" stroke-width="28" fill="none"/>
        <path d="M0 180 H535 Q563 180 563 210 V650 M0 325 H1000 M0 510 H1000 M260 0 V650 M0 610 L690 0" stroke="#fffefa" stroke-width="21" fill="none"/>
        <path d="M0 610 L690 0" stroke="#e9dfc5" stroke-width="2" stroke-dasharray="8 9" fill="none"/>
        <path d="M601 0 V650" stroke="#fefefd" stroke-width="16"/>
        <rect x="650" y="128" width="330" height="340" rx="24" fill="#dfeadc" stroke="#cdddc9" stroke-width="2"/>
        <rect x="680" y="157" width="272" height="280" rx="12" fill="url(#campusGrid)"/>
        <path d="M815 138 V457 M661 296 H966" stroke="#f0f5eb" stroke-width="12"/>
        <ellipse cx="858" cy="355" rx="53" ry="31" fill="#c4e3e3"/>
        <rect x="663" y="237" width="301" height="74" rx="6" fill="#e5ede0" opacity=".94"/>
        <text x="814" y="267" text-anchor="middle" class="campus-name">西南交通大学</text><text x="814" y="291" text-anchor="middle" class="campus-sub">犀浦校区 · 范围示意</text>
        <text x="352" y="321" class="road-name">校园路（示意）</text><text x="566" y="475" transform="rotate(-90 566 475)" class="road-name">校区周边道路</text>
        <text x="317" y="502" class="road-name">兴业北街接驳方向</text><text x="66" y="82" class="area-name">社区生活区</text><text x="658" y="590" class="area-name">周边居住与商业区</text>
        <g v-for="task in paths" :key="task.id">
          <path :d="`M${task.source.x},${task.source.y} L${task.destination.x},${task.source.y} L${task.destination.x},${task.destination.y}`" fill="none" stroke="#fff" stroke-width="9" stroke-linejoin="round"/>
          <path :d="`M${task.source.x},${task.source.y} L${task.destination.x},${task.source.y} L${task.destination.x},${task.destination.y}`" fill="none" stroke="#0c9975" stroke-width="4" stroke-linejoin="round" :class="{ 'moving-route': task.status === 'RUNNING' }" stroke-dasharray="8 5" marker-end="url(#routeArrow)"/>
        </g>
        <g v-for="r in regions" :key="r.id" class="map-point" tabindex="0" role="button" :aria-label="`${r.name}，${r.stock} 辆，${r.shortage ? '待补充 ' + r.shortage + ' 辆' : '库存充足'}`" @click="emit('select', r.id)" @keydown.enter="emit('select', r.id)" @keydown.space.prevent="emit('select', r.id)">
          <title>{{ r.name }} · {{ r.stock }} 辆</title>
          <circle v-if="selected === r.id" :cx="r.x" :cy="r.y" r="29" :fill="color(r)" opacity=".13"/>
          <circle :cx="r.x" :cy="r.y" :r="selected === r.id ? 22 : 19" fill="white" :stroke="color(r)" :stroke-width="selected === r.id ? 3 : 2"/>
          <text :x="r.x" :y="r.y + 5" text-anchor="middle" :fill="color(r)" class="point-count">{{ r.stock }}</text>
          <rect v-if="selected === r.id || r.id <= 3" :x="r.x - 71" :y="r.y + 30" width="142" height="27" rx="5" fill="white" fill-opacity=".95"/>
          <text v-if="selected === r.id || r.id <= 3" :x="r.x" :y="r.y + 48" text-anchor="middle" class="point-name">{{ r.name }}</text>
        </g>
      </g>
    </svg>
    <div class="map-controls"><button aria-label="放大地图" :disabled="zoom >= 1.6" @click="zoom = Math.min(1.6, zoom + .2)"><Plus :size="17"/></button><button aria-label="缩小地图" :disabled="zoom <= .8" @click="zoom = Math.max(.8, zoom - .2)"><Minus :size="17"/></button><button aria-label="恢复地图视图" @click="zoom = 1"><LocateFixed :size="17"/></button></div>
    <div class="map-legend"><span><i class="legend-dot green"></i>库存充足</span><span><i class="legend-dot orange"></i>待补车</span><span><i class="legend-dot blue"></i>较拥挤</span></div>
    <div class="map-disclaimer">示范点位与模拟路网 · 非实测地图</div>
  </div>
</template>
